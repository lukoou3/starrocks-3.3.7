// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.scheduler.mv;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.analysis.BoolLiteral;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableProperty;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.util.concurrent.lock.LockTimeoutException;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.scheduler.MvTaskRunContext;
import com.starrocks.scheduler.TableSnapshotInfo;
import com.starrocks.scheduler.TaskRunContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.AlterTableClauseAnalyzer;
import com.starrocks.sql.ast.AddPartitionClause;
import com.starrocks.sql.ast.DistributionDesc;
import com.starrocks.sql.ast.MultiItemListPartitionDesc;
import com.starrocks.sql.ast.PartitionValue;
import com.starrocks.sql.common.DmlException;
import com.starrocks.sql.common.ListPartitionDiff;
import com.starrocks.sql.common.ListPartitionDiffResult;
import com.starrocks.sql.common.ListPartitionDiffer;
import com.starrocks.sql.common.PListCell;
import com.starrocks.sql.common.SyncPartitionUtils;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MVPCTRefreshListPartitioner extends MVPCTRefreshPartitioner {
    private static final Logger LOG = LogManager.getLogger(MVPCTRefreshListPartitioner.class);

    public MVPCTRefreshListPartitioner(MvTaskRunContext mvContext,
                                       TaskRunContext context,
                                       Database db,
                                       MaterializedView mv) {
        super(mvContext, context, db, mv);
    }

    @Override
    public boolean syncAddOrDropPartitions() throws LockTimeoutException {
        // collect mv partition items with lock
        Locker locker = new Locker();
        if (!locker.tryLockDatabase(db, LockType.READ, Config.mv_refresh_try_lock_timeout_ms)) {
            throw new LockTimeoutException("Failed to lock database: " + db.getFullName() + " in syncPartitionsForList");
        }

        ListPartitionDiffResult result;
        try {
            result = ListPartitionDiffer.computeListPartitionDiff(mv, false);
            if (result == null) {
                LOG.warn("compute list partition diff failed: mv: {}", mv.getName());
                return false;
            }
        } finally {
            locker.unLockDatabase(db, LockType.READ);
        }

        {
            ListPartitionDiff partitionDiff = result.listPartitionDiff;
            // We should delete the old partition first and then add the new one,
            // because the old and new partitions may overlap
            Map<String, PListCell> deletes = partitionDiff.getDeletes();
            for (String mvPartitionName : deletes.keySet()) {
                dropPartition(db, mv, mvPartitionName);
            }
            LOG.info("The process of synchronizing materialized view [{}] delete partitions list [{}]",
                    mv.getName(), deletes);

            // add partitions
            Map<String, String> partitionProperties = MvUtils.getPartitionProperties(mv);
            DistributionDesc distributionDesc = MvUtils.getDistributionDesc(mv);
            Map<String, PListCell> adds = partitionDiff.getAdds();

            // filter by partition_ttl_number
            int ttlNumber = mv.getTableProperty().getPartitionTTLNumber();
            filterPartitionsByNumber(adds, ttlNumber);
            // add partitions for mv
            addListPartitions(db, mv, adds, partitionProperties, distributionDesc);
            LOG.info("The process of synchronizing materialized view [{}] add partitions list [{}]",
                    mv.getName(), adds);

            // add into mv context
            result.mvListPartitionMap.putAll(adds);
        }
        {
            final Map<Table, List<Integer>> refBaseTableRefIdxMap = result.refBaseTableRefIdxMap;
            final Map<Table, Map<String, PListCell>> refBaseTablePartitionMap = result.refBaseTablePartitionMap;
            // base table -> Map<partition name -> mv partition names>
            Map<Table, Map<String, Set<String>>> baseToMvNameRef = ListPartitionDiffer
                    .generateBaseRefMap(refBaseTablePartitionMap, refBaseTableRefIdxMap, result.mvListPartitionMap);
            // mv partition name -> Map<base table -> base partition names>
            Map<String, Map<Table, Set<String>>> mvToBaseNameRef = ListPartitionDiffer
                    .generateMvRefMap(result.mvListPartitionMap, refBaseTableRefIdxMap, refBaseTablePartitionMap);
            mvContext.setRefBaseTableMVIntersectedPartitions(baseToMvNameRef);
            mvContext.setMvRefBaseTableIntersectedPartitions(mvToBaseNameRef);
            mvContext.setRefBaseTableListPartitionMap(refBaseTablePartitionMap);
            mvContext.setExternalRefBaseTableMVPartitionMap(result.getRefBaseTableMVPartitionMap());
        }
        return true;
    }

    @Override
    public Expr generatePartitionPredicate(Table refBaseTable, Set<String> refBaseTablePartitionNames,
                                           Expr mvPartitionSlotRef) throws AnalysisException {
        Map<Table, Map<String, PListCell>> basePartitionMaps = mvContext.getRefBaseTableListPartitionMap();
        if (basePartitionMaps.isEmpty()) {
            return null;
        }
        Map<String, PListCell> baseListPartitionMap = basePartitionMaps.get(refBaseTable);
        if (baseListPartitionMap == null) {
            LOG.warn("Generate incremental partition predicate failed, " +
                    "basePartitionMaps:{} contains no refBaseTable:{}", basePartitionMaps, refBaseTable);
            return null;
        }
        if (baseListPartitionMap.isEmpty()) {
            return new BoolLiteral(true);
        }

        List<Expr> sourceTablePartitionList = Lists.newArrayList();
        List<Column> partitionCols = refBaseTable.getPartitionColumns();
        Map<Table, Column> partitionTableAndColumn = mv.getRefBaseTablePartitionColumns();
        if (partitionTableAndColumn == null || !partitionTableAndColumn.containsKey(refBaseTable)) {
            LOG.warn("Generate incremental partition failed, partitionTableAndColumn {} contains no ref table {}",
                    partitionTableAndColumn, refBaseTable);
            return null;
        }
        Column refPartitionColumn = partitionTableAndColumn.get(refBaseTable);
        int refIndex = ListPartitionDiffer.getRefBaseTableIdx(refBaseTable, refPartitionColumn);
        Type partitionType = partitionCols.get(refIndex).getType();

        boolean isContainsNullPartition = false;
        for (String tablePartitionName : refBaseTablePartitionNames) {
            PListCell cell = baseListPartitionMap.get(tablePartitionName);
            for (List<String> values : cell.getPartitionItems()) {
                if (partitionCols.size() != values.size()) {
                    return null;
                }
                LiteralExpr partitionValue = new PartitionValue(values.get(refIndex)).getValue(partitionType);
                if (partitionValue.isConstantNull()) {
                    isContainsNullPartition = true;
                    continue;
                }
                sourceTablePartitionList.add(partitionValue);
            }
        }
        Expr inPredicate = MvUtils.convertToInPredicate(mvPartitionSlotRef, sourceTablePartitionList);
        // NOTE: If target partition values contain `null partition`, the generated predicate should
        // contain `is null` predicate rather than `in (null) or = null` because the later one is not correct.
        if (isContainsNullPartition) {
            IsNullPredicate isNullPredicate = new IsNullPredicate(mvPartitionSlotRef, false);
            return Expr.compoundOr(Lists.newArrayList(inPredicate, isNullPredicate));
        } else {
            return inPredicate;
        }
    }

    @Override
    public Set<String> getMVPartitionsToRefreshWithForce(int partitionTTLNumber) {
        return mv.getValidListPartitionMap(partitionTTLNumber).keySet();
    }

    @Override
    public Set<String> getMVPartitionsToRefresh(PartitionInfo mvPartitionInfo,
                                                Map<Long, TableSnapshotInfo> snapshotBaseTables,
                                                MVRefreshParams mvRefreshParams,
                                                Set<String> mvPotentialPartitionNames) {
        // list partitioned materialized view
        boolean isAutoRefresh = mvContext.getTaskType().isAutoRefresh();
        int partitionTTLNumber = mvContext.getPartitionTTLNumber();
        Set<String> mvListPartitionNames = getMVPartitionNamesWithTTL(mv, mvRefreshParams, partitionTTLNumber, isAutoRefresh);

        // check non-ref base tables
        if (mvRefreshParams.isForce() || needsRefreshBasedOnNonRefTables(snapshotBaseTables)) {
            if (mvRefreshParams.isCompleteRefresh()) {
                // if non-partition table changed, should refresh all partitions of materialized view
                return mvListPartitionNames;
            } else {
                // If the user specifies the start and end ranges, and the non-partitioned table still changes,
                // it should be refreshed according to the user-specified range, not all partitions.
                return getMvPartitionNamesToRefresh(mvListPartitionNames);
            }
        } else {
            // check the ref base table
            Set<String> needRefreshMvPartitionNames = getMvPartitionNamesToRefresh(mvListPartitionNames);
            Map<Table, Set<String>> baseChangedPartitionNames =
                    getBasePartitionNamesByMVPartitionNames(needRefreshMvPartitionNames);
            if (baseChangedPartitionNames.isEmpty()) {
                LOG.info("Cannot get associated base table change partitions from mv's refresh partitions {}, mv: {}",
                        needRefreshMvPartitionNames, mv.getName());
                return needRefreshMvPartitionNames;
            }
            // because the relation of partitions between materialized view and base partition table is n : m,
            // should calculate the candidate partitions recursively.
            if (isCalcPotentialRefreshPartition()) {
                LOG.info("Start calcPotentialRefreshPartition, needRefreshMvPartitionNames: {}," +
                        " baseChangedPartitionNames: {}", needRefreshMvPartitionNames, baseChangedPartitionNames);
                SyncPartitionUtils.calcPotentialRefreshPartition(needRefreshMvPartitionNames, baseChangedPartitionNames,
                        mvContext.getRefBaseTableMVIntersectedPartitions(),
                        mvContext.getMvRefBaseTableIntersectedPartitions(),
                        mvPotentialPartitionNames);
                LOG.info("Finish calcPotentialRefreshPartition, needRefreshMvPartitionNames: {}," +
                        " baseChangedPartitionNames: {}", needRefreshMvPartitionNames, baseChangedPartitionNames);
            }
            return needRefreshMvPartitionNames;
        }
    }

    public boolean isCalcPotentialRefreshPartition() {
        // TODO: If base table's list partitions contain multi values, should calculate potential partitions.
        // Only check base table's partition values intersection with mv's to-refresh partitions later.
        Map<Table, Map<String, PListCell>> refBaseTableRangePartitionMap =
                mvContext.getRefBaseTableListPartitionMap();
        return refBaseTableRangePartitionMap.entrySet()
                .stream()
                .anyMatch(e -> e.getValue().values().stream().anyMatch(l -> l.getItemSize() > 1));
    }

    @Override
    public Set<String> getMVPartitionNamesWithTTL(MaterializedView materializedView,
                                                  MVRefreshParams mvRefreshParams,
                                                  int partitionTTLNumber,
                                                  boolean isAutoRefresh) {
        int autoRefreshPartitionsLimit = materializedView.getTableProperty().getAutoRefreshPartitionsLimit();

        // if the user specifies the start and end ranges, only refresh the specified partitions
        boolean isCompleteRefresh = mvRefreshParams.isCompleteRefresh();
        if (!isCompleteRefresh) {
            Set<PListCell> pListCells = mvRefreshParams.getListValues();
            Map<String, PListCell> mvPartitions = materializedView.getListPartitionItems();
            Set<String> result = Sets.newHashSet();
            for (Map.Entry<String, PListCell> e : mvPartitions.entrySet()) {
                PListCell mvListCell = e.getValue();
                if (mvListCell.getItemSize() == 1) {
                    // if list value is a single value, check it directly
                    if (pListCells.contains(e.getValue()))  {
                        result.add(e.getKey());
                    }
                } else {
                    // if list values is multi values, split it into single values and check it then.
                    if (mvListCell.toSingleValueCells().stream().anyMatch(i -> pListCells.contains(i))) {
                        result.add(e.getKey());
                    }
                }
            }
            return result;
        }

        int lastPartitionNum;
        if (partitionTTLNumber > 0 && isAutoRefresh && autoRefreshPartitionsLimit > 0) {
            lastPartitionNum = Math.min(partitionTTLNumber, autoRefreshPartitionsLimit);
        } else if (isAutoRefresh && autoRefreshPartitionsLimit > 0) {
            lastPartitionNum = autoRefreshPartitionsLimit;
        } else if (partitionTTLNumber > 0) {
            lastPartitionNum = partitionTTLNumber;
        } else {
            lastPartitionNum = TableProperty.INVALID;
        }
        return materializedView.getValidListPartitionMap(lastPartitionNum).keySet();
    }

    /**
     * Filter partitions by partition_ttl_number, save the kept partitions and return the next task run partition values.
     * @param inputPartitions the partitions to refresh/add
     * @param filterNumber the number to filter/reserve
     * @return the next task run partition list cells after the reserved partition_ttl_number
     */
    private Set<PListCell> filterPartitionsByNumber(Map<String, PListCell> inputPartitions,
                                                    int filterNumber) {
        if (filterNumber <= 0 || filterNumber >= inputPartitions.size()) {
            return null;
        }
        // TODO: Sort by List Partition's value is weird because there maybe meaningless or un-sortable,
        // users should take care of `partition_ttl_number` for list partition.
        LinkedHashMap<String, PListCell> sortedPartition = inputPartitions.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue())) // reverse order(max heap)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        Iterator<Map.Entry<String, PListCell>> iter = sortedPartition.entrySet().iterator();
        // iterate partition_ttl_number times
        for (int i = 0; i < filterNumber; i++) {
            if (iter.hasNext()) {
                iter.next();
            }
        }

        // compute next partition list cells in the next task run
        Set<PListCell> nextPartitionCells = Sets.newHashSet();
        while (iter.hasNext()) {
            Map.Entry<String, PListCell> entry = iter.next();
            nextPartitionCells.add(entry.getValue());
            // remove the partition which is not reserved
            inputPartitions.remove(entry.getKey());
        }
        LOG.info("Filter partitions by partition_ttl_number, ttl_number:{}, result:{}, remains:{}",
                filterNumber, inputPartitions, nextPartitionCells);
        return nextPartitionCells;
    }

    @Override
    public void filterPartitionByRefreshNumber(Set<String> mvPartitionsToRefresh,
                                               Set<String> mvPotentialPartitionNames, boolean tentative) {
        Map<String, PListCell> mappedPartitionsToRefresh = Maps.newHashMap();
        Map<String, PListCell> listPartitionMap = mv.getListPartitionItems();
        for (String partitionName : mvPartitionsToRefresh) {
            PListCell listCell = listPartitionMap.get(partitionName);
            if (listCell == null) {
                LOG.warn("Partition {} is not found in materialized view {}", partitionName, mv.getName());
                continue;
            }
            mappedPartitionsToRefresh.put(partitionName, listCell);
        }
        int refreshNumber = mv.getTableProperty().getPartitionRefreshNumber();
        Set<PListCell> nextPartitionValues = filterPartitionsByNumber(mappedPartitionsToRefresh, refreshNumber);
        // do filter input mvPartitionsToRefresh since it's a reference
        mvPartitionsToRefresh.retainAll(mappedPartitionsToRefresh.keySet());
        if (CollectionUtils.isEmpty(nextPartitionValues)) {
            return;
        }
        if (!tentative) {
            // partitionNameIter has just been traversed, and endPartitionName is not updated
            // will cause endPartitionName == null
            mvContext.setNextPartitionValues(PListCell.batchSerialize(nextPartitionValues));
        }
    }

    private void addListPartitions(Database database, MaterializedView materializedView,
                                   Map<String, PListCell> adds, Map<String, String> partitionProperties,
                                   DistributionDesc distributionDesc) {
        if (adds == null || adds.isEmpty()) {
            return;
        }

        // TODO: support to add partitions by batch
        for (Map.Entry<String, PListCell> addEntry : adds.entrySet()) {
            String mvPartitionName = addEntry.getKey();
            PListCell partitionCell = addEntry.getValue();
            List<List<String>> partitionItems = partitionCell.getPartitionItems();
            // the order is not guaranteed
            MultiItemListPartitionDesc multiItemListPartitionDesc =
                    new MultiItemListPartitionDesc(false, mvPartitionName, partitionItems, partitionProperties);
            AddPartitionClause addPartitionClause =
                    new AddPartitionClause(multiItemListPartitionDesc, distributionDesc, partitionProperties, false);
            AlterTableClauseAnalyzer analyzer = new AlterTableClauseAnalyzer(materializedView);
            analyzer.analyze(mvContext.getCtx(), addPartitionClause);
            try {
                GlobalStateMgr.getCurrentState().getLocalMetastore().addPartitions(
                        mvContext.getCtx(), database, materializedView.getName(), addPartitionClause);
            } catch (Exception e) {
                throw new DmlException("add list partition failed: %s, db: %s, table: %s", e, e.getMessage(),
                        database.getFullName(), materializedView.getName());
            }
        }
    }
}
