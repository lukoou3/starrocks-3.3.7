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

package com.starrocks.statistic;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.SubfieldExpr;
import com.starrocks.analysis.TypeDef;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveMetaStoreTable;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.StructField;
import com.starrocks.catalog.StructType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.util.DateUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.connector.ConnectorPartitionTraits;
import com.starrocks.load.EtlStatus;
import com.starrocks.load.loadv2.LoadJobFinalOperation;
import com.starrocks.load.pipe.filelist.RepoExecutor;
import com.starrocks.load.streamload.StreamLoadTxnCommitAttachment;
import com.starrocks.privilege.PrivilegeBuiltinConstants;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.ColumnDef;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.optimizer.statistics.StatisticsEstimateCoefficient;
import com.starrocks.transaction.InsertOverwriteJobStats;
import com.starrocks.transaction.InsertTxnCommitAttachment;
import com.starrocks.transaction.TransactionState;
import com.starrocks.transaction.TxnCommitAttachment;
import com.starrocks.warehouse.Warehouse;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.Utils.getLongFromDateTime;
import static com.starrocks.statistic.StatsConstants.AnalyzeType.SAMPLE;

public class StatisticUtils {
    private static final Logger LOG = LogManager.getLogger(StatisticUtils.class);

    private static final List<String> COLLECT_DATABASES_BLACKLIST = ImmutableList.<String>builder()
            .add(StatsConstants.STATISTICS_DB_NAME)
            .add("starrocks_monitor")
            .add("information_schema").build();

    public static ConnectContext buildConnectContext() {
        ConnectContext context = new ConnectContext();
        // Note: statistics query does not register query id to QeProcessorImpl::coordinatorMap,
        // but QeProcessorImpl::reportExecStatus will check query id,
        // So we must disable report query status from BE to FE
        context.getSessionVariable().setEnableProfile(false);
        context.getSessionVariable().setEnableLoadProfile(false);
        context.getSessionVariable().setParallelExecInstanceNum(1);
        context.getSessionVariable().setQueryTimeoutS((int) Config.statistic_collect_query_timeout);
        context.getSessionVariable().setEnablePipelineEngine(true);
        context.getSessionVariable().setCboCteReuse(true);
        context.getSessionVariable().setCboCTERuseRatio(0);

        WarehouseManager manager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
        Warehouse warehouse = manager.getBackgroundWarehouse();
        context.getSessionVariable().setWarehouseName(warehouse.getName());

        context.setStatisticsContext(true);
        context.setDatabase(StatsConstants.STATISTICS_DB_NAME);
        context.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        context.setCurrentUserIdentity(UserIdentity.ROOT);
        context.setCurrentRoleIds(Sets.newHashSet(PrivilegeBuiltinConstants.ROOT_ROLE_ID));
        context.setQualifiedUser(UserIdentity.ROOT.getUser());
        context.setQueryId(UUIDUtil.genUUID());
        context.setExecutionId(UUIDUtil.toTUniqueId(context.getQueryId()));
        context.setStartTime();

        return context;
    }

    private static StatsConstants.AnalyzeType parseAnalyzeType(TransactionState txnState, Table table) {
        Long loadRows = null;
        TxnCommitAttachment attachment = txnState.getTxnCommitAttachment();
        if (attachment instanceof LoadJobFinalOperation) {
            EtlStatus loadingStatus = ((LoadJobFinalOperation) attachment).getLoadingStatus();
            loadRows = loadingStatus.getLoadedRows(table.getId());
        } else if (attachment instanceof InsertTxnCommitAttachment) {
            loadRows = ((InsertTxnCommitAttachment) attachment).getLoadedRows();
        } else if (attachment instanceof StreamLoadTxnCommitAttachment) {
            loadRows = ((StreamLoadTxnCommitAttachment) attachment).getNumRowsNormal();
        }
        if (loadRows != null && loadRows > Config.statistic_sample_collect_rows) {
            return SAMPLE;
        }
        return StatsConstants.AnalyzeType.FULL;
    }

    public static void triggerCollectionOnInsertOverwrite(InsertOverwriteJobStats stats,
                                                          Database db,
                                                          Table table,
                                                          boolean sync,
                                                          boolean useLock) {
        StatisticsCollectionTrigger.triggerOnInsertOverwrite(stats, db, table, sync, useLock);
    }

    public static void triggerCollectionOnFirstLoad(TransactionState txnState,
                                                    Database db,
                                                    Table table,
                                                    boolean sync,
                                                    boolean useLock) {
        StatisticsCollectionTrigger.triggerOnFirstLoad(txnState, db, table, sync, useLock);
    }

    // check database in black list
    public static boolean statisticDatabaseBlackListCheck(String databaseName) {
        if (null == databaseName) {
            return true;
        }

        return COLLECT_DATABASES_BLACKLIST.stream().anyMatch(d -> databaseName.toLowerCase().contains(d.toLowerCase()));
    }

    public static boolean statisticTableBlackListCheck(long tableId) {
        if (null != ConnectContext.get() && ConnectContext.get().isStatisticsConnection()) {
            // avoid query statistics table when collect statistics
            return true;
        }

        for (String dbName : COLLECT_DATABASES_BLACKLIST) {
            Database db = GlobalStateMgr.getCurrentState().getDb(dbName);
            if (null != db && null != db.getTable(tableId)) {
                return true;
            }
        }

        return false;
    }

    public static boolean checkStatisticTableStateNormal() {
        if (FeConstants.runningUnitTest) {
            return true;
        }
        Database db = GlobalStateMgr.getCurrentState().getDb(StatsConstants.STATISTICS_DB_NAME);
        List<String> tableNameList = Lists.newArrayList(StatsConstants.SAMPLE_STATISTICS_TABLE_NAME,
                StatsConstants.FULL_STATISTICS_TABLE_NAME, StatsConstants.HISTOGRAM_STATISTICS_TABLE_NAME,
                StatsConstants.EXTERNAL_FULL_STATISTICS_TABLE_NAME);

        // check database
        if (db == null) {
            return false;
        }

        for (String tableName : tableNameList) {
            // check table
            Table table = db.getTable(tableName);
            if (table == null) {
                return false;
            }
            if (table.isCloudNativeTableOrMaterializedView()) {
                continue;
            }

            // check replicate miss
            for (Partition partition : table.getPartitions()) {
                if (partition.getBaseIndex().getTablets().stream()
                        .anyMatch(t -> ((LocalTablet) t).getNormalReplicaBackendIds().isEmpty())) {
                    return false;
                }
            }
        }

        return true;
    }

    public static LocalDateTime getTableLastUpdateTime(Table table) {
        if (table.isNativeTableOrMaterializedView()) {
            long maxTime = table.getPartitions().stream().map(Partition::getVisibleVersionTime)
                    .max(Long::compareTo).orElse(0L);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(maxTime), Clock.systemDefaultZone().getZone());
        } else {
            try {
                return ConnectorPartitionTraits.build(table).getTableLastUpdateTime(60);
            } catch (Exception e) {
                // ConnectorPartitionTraits do not support all type of table, ignore exception
                return null;
            }
        }
    }

    public static LocalDateTime getPartitionLastUpdateTime(Partition partition) {
        long time = partition.getVisibleVersionTime();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(time), Clock.systemDefaultZone().getZone());
    }

    public static boolean isEmptyTable(Table table) {
        if (!table.isNativeTableOrMaterializedView()) {
            // for external table, return false directly
            return false;
        }
        return table.getPartitions().stream().noneMatch(Partition::hasData);
    }

    public static List<ColumnDef> buildStatsColumnDef(String tableName) {
        ScalarType columnNameType = ScalarType.createVarcharType(65530);
        ScalarType tableNameType = ScalarType.createVarcharType(65530);
        ScalarType tableUUIDType = ScalarType.createVarcharType(65530);
        ScalarType partitionNameType = ScalarType.createVarcharType(65530);
        ScalarType dbNameType = ScalarType.createVarcharType(65530);
        ScalarType maxType = ScalarType.createOlapMaxVarcharType();
        ScalarType minType = ScalarType.createOlapMaxVarcharType();
        ScalarType bucketsType = ScalarType.createOlapMaxVarcharType();
        ScalarType mostCommonValueType = ScalarType.createOlapMaxVarcharType();
        ScalarType catalogNameType = ScalarType.createVarcharType(65530);

        if (tableName.equals(StatsConstants.SAMPLE_STATISTICS_TABLE_NAME)) {
            return ImmutableList.of(
                    new ColumnDef("table_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("column_name", new TypeDef(columnNameType)),
                    new ColumnDef("db_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("table_name", new TypeDef(tableNameType)),
                    new ColumnDef("db_name", new TypeDef(dbNameType)),
                    new ColumnDef("row_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("data_size", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("distinct_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("null_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("max", new TypeDef(maxType)),
                    new ColumnDef("min", new TypeDef(minType)),
                    new ColumnDef("update_time", new TypeDef(ScalarType.createType(PrimitiveType.DATETIME)))
            );
        } else if (tableName.equals(StatsConstants.FULL_STATISTICS_TABLE_NAME)) {
            return ImmutableList.of(
                    new ColumnDef("table_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("partition_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("column_name", new TypeDef(columnNameType)),
                    new ColumnDef("db_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("table_name", new TypeDef(tableNameType)),
                    new ColumnDef("partition_name", new TypeDef(partitionNameType)),
                    new ColumnDef("row_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("data_size", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("ndv", new TypeDef(ScalarType.createType(PrimitiveType.HLL))),
                    new ColumnDef("null_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("max", new TypeDef(maxType)),
                    new ColumnDef("min", new TypeDef(minType)),
                    new ColumnDef("update_time", new TypeDef(ScalarType.createType(PrimitiveType.DATETIME)))
            );
        } else if (tableName.equals(StatsConstants.EXTERNAL_FULL_STATISTICS_TABLE_NAME)) {
            return ImmutableList.of(
                    new ColumnDef("table_uuid", new TypeDef(tableUUIDType)),
                    new ColumnDef("partition_name", new TypeDef(partitionNameType)),
                    new ColumnDef("column_name", new TypeDef(columnNameType)),
                    new ColumnDef("catalog_name", new TypeDef(catalogNameType)),
                    new ColumnDef("db_name", new TypeDef(dbNameType)),
                    new ColumnDef("table_name", new TypeDef(tableNameType)),
                    new ColumnDef("row_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("data_size", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("ndv", new TypeDef(ScalarType.createType(PrimitiveType.HLL))),
                    new ColumnDef("null_count", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("max", new TypeDef(maxType)),
                    new ColumnDef("min", new TypeDef(minType)),
                    new ColumnDef("update_time", new TypeDef(ScalarType.createType(PrimitiveType.DATETIME)))
            );
        } else if (tableName.equals(StatsConstants.HISTOGRAM_STATISTICS_TABLE_NAME)) {
            return ImmutableList.of(
                    new ColumnDef("table_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("column_name", new TypeDef(columnNameType)),
                    new ColumnDef("db_id", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT))),
                    new ColumnDef("table_name", new TypeDef(tableNameType)),
                    new ColumnDef("buckets", new TypeDef(bucketsType), false, null,
                            true, ColumnDef.DefaultValueDef.NOT_SET, ""),
                    new ColumnDef("mcv", new TypeDef(mostCommonValueType), false, null,
                            true, ColumnDef.DefaultValueDef.NOT_SET, ""),
                    new ColumnDef("update_time", new TypeDef(ScalarType.createType(PrimitiveType.DATETIME)))
            );
        } else if (tableName.equals(StatsConstants.EXTERNAL_HISTOGRAM_STATISTICS_TABLE_NAME)) {
            return ImmutableList.of(
                    new ColumnDef("table_uuid",  new TypeDef(tableUUIDType)),
                    new ColumnDef("column_name", new TypeDef(columnNameType)),
                    new ColumnDef("catalog_name", new TypeDef(catalogNameType)),
                    new ColumnDef("db_name", new TypeDef(dbNameType)),
                    new ColumnDef("table_name", new TypeDef(tableNameType)),
                    new ColumnDef("buckets", new TypeDef(bucketsType), false, null,
                            true, ColumnDef.DefaultValueDef.NOT_SET, ""),
                    new ColumnDef("mcv", new TypeDef(mostCommonValueType), false, null,
                            true, ColumnDef.DefaultValueDef.NOT_SET, ""),
                    new ColumnDef("update_time", new TypeDef(ScalarType.createType(PrimitiveType.DATETIME)))
            );
        } else {
            throw new StarRocksPlannerException("Not support stats table " + tableName, ErrorType.INTERNAL_ERROR);
        }
    }

    public static Optional<Double> convertStatisticsToDouble(Type type, String statistic) {
        if (!type.canStatistic()) {
            throw new StarRocksPlannerException("Error statistic type : " + type.toSql(), ErrorType.INTERNAL_ERROR);
        }
        try {
            switch (type.getPrimitiveType()) {
                case BOOLEAN:
                    if (statistic.equalsIgnoreCase("TRUE")) {
                        return Optional.of(1D);
                    } else {
                        return Optional.of(0D);
                    }
                case DATE:
                    return Optional.of((double) getLongFromDateTime(DateUtils.parseStringWithDefaultHSM(
                            statistic, DateUtils.DATE_FORMATTER_UNIX)));
                case DATETIME:
                    return Optional.of((double) getLongFromDateTime(DateUtils.parseDatTimeString(statistic)));
                case CHAR:
                case VARCHAR:
                    return Optional.empty();
                default:
                    return Optional.of(Double.parseDouble(statistic));
            }
        } catch (Exception e) {
            LOG.warn(String.format("Statistic convert error, type %s, statistic %s, %s",
                    type.toSql(), statistic, e.getMessage()));
            return Optional.empty();
        }
    }

    // Get all the columns in the table that can be collected.
    // The list will only contain:
    // 1. non-aggregated column
    // 2. replace-aggregated columns which in primary key engine (unique engine has poor performance, we don't touch it)
    // This is because in aggregate type tables, metric columns generally do not participate in predicate.
    // Collecting these columns is not meaningful but time-consuming, so we exclude them.
    public static List<String> getCollectibleColumns(Table table) {
        boolean isPrimaryEngine = false;
        if (table instanceof OlapTable) {
            isPrimaryEngine = KeysType.PRIMARY_KEYS.equals(((OlapTable) table).getKeysType());
        }
        List<String> columns = new ArrayList<>();
        for (Column column : table.getBaseSchema()) {
            if (!column.isAggregated()) {
                columns.add(column.getName());
            } else if (isPrimaryEngine && column.getAggregationType().equals(AggregateType.REPLACE)) {
                columns.add(column.getName());
            }
        }
        return columns;
    }

    public static double multiplyRowCount(double left, double right) {
        left = Math.min(left, StatisticsEstimateCoefficient.MAXIMUM_ROW_COUNT);
        right = Math.min(right, StatisticsEstimateCoefficient.MAXIMUM_ROW_COUNT);
        double result;
        if (left > StatisticsEstimateCoefficient.MAXIMUM_ROW_COUNT / right) {
            result = StatisticsEstimateCoefficient.MAXIMUM_ROW_COUNT;
        } else {
            result = left * right;
        }
        return result;
    }

    public static double multiplyOutputSize(double left, double right) {
        left = Math.min(left, StatisticsEstimateCoefficient.MAXIMUM_OUTPUT_SIZE);
        right = Math.min(right, StatisticsEstimateCoefficient.MAXIMUM_OUTPUT_SIZE);
        double result;
        if (left > StatisticsEstimateCoefficient.MAXIMUM_OUTPUT_SIZE / right) {
            result = StatisticsEstimateCoefficient.MAXIMUM_OUTPUT_SIZE;
        } else {
            result = left * right;
        }
        return result;
    }

    public static String quoting(String... parts) {
        return Arrays.stream(parts).map(c -> "`" + c + "`").collect(Collectors.joining("."));
    }

    public static String quoting(Table table, String columnName) {
        if (!columnName.contains(".")) {
            return quoting(columnName);
        }
        Column c = table.getColumn(columnName);
        if (c != null) {
            return quoting(columnName);
        }

        int start = 0;
        int end;

        StringBuilder sb = new StringBuilder();
        while ((end = columnName.indexOf(".", start)) > 0) {
            start = end + 1;
            String name = columnName.substring(0, end);
            c = table.getColumn(name);
            if (c != null && c.getType().isStructType()) {
                sb.append(quoting(name));
                columnName = columnName.substring(end + 1);
                Type type = c.getType();
                if (!columnName.contains(".")) {
                    sb.append(".").append(quoting(columnName));
                } else {
                    int subStart = 0;
                    int pos = 0;
                    int subEnd;
                    while ((subEnd = columnName.indexOf(".", pos)) > 0 && type.isStructType()) {
                        String subName = columnName.substring(subStart, subEnd);
                        if (((StructType) type).containsField(subName)) {
                            sb.append(".").append(quoting(subName));
                            type = ((StructType) type).getField(subName).getType();
                            subStart = subEnd + 1;
                        }
                        pos = subEnd + 1;
                    }
                    sb.append(".").append(quoting(columnName.substring(subStart)));
                }
                break;
            }
        }
        Preconditions.checkState(sb.length() != 0, "column name is not found in table");
        return sb.toString();
    }

    public static void dropStatisticsAfterDropTable(Table table) {
        GlobalStateMgr.getCurrentState().getAnalyzeMgr().dropExternalAnalyzeStatus(table.getUUID());
        GlobalStateMgr.getCurrentState().getAnalyzeMgr().dropExternalBasicStatsData(table.getUUID());

        if (table.isHiveTable() || table.isHudiTable()) {
            HiveMetaStoreTable hiveMetaStoreTable = (HiveMetaStoreTable) table;
            GlobalStateMgr.getCurrentState().getAnalyzeMgr().removeExternalBasicStatsMeta(hiveMetaStoreTable.getCatalogName(),
                    hiveMetaStoreTable.getDbName(), hiveMetaStoreTable.getTableName());
            GlobalStateMgr.getCurrentState().getAnalyzeMgr().dropAnalyzeJob(hiveMetaStoreTable.getCatalogName(),
                    hiveMetaStoreTable.getDbName(), hiveMetaStoreTable.getTableName());
        } else if (table.isIcebergTable()) {
            IcebergTable icebergTable = (IcebergTable) table;
            GlobalStateMgr.getCurrentState().getAnalyzeMgr().removeExternalBasicStatsMeta(icebergTable.getCatalogName(),
                    icebergTable.getRemoteDbName(), icebergTable.getRemoteTableName());
            GlobalStateMgr.getCurrentState().getAnalyzeMgr().dropAnalyzeJob(icebergTable.getCatalogName(),
                    icebergTable.getRemoteDbName(), icebergTable.getRemoteTableName());
        } else {
            LOG.warn("drop statistics after drop table, table type is not supported, table type: {}",
                    table.getType().name());
        }

        List<String> columns = table.getBaseSchema().stream().map(Column::getName).collect(Collectors.toList());
        GlobalStateMgr.getCurrentState().getStatisticStorage().expireConnectorTableColumnStatistics(table, columns);
    }

    /**
     * Change the replication_num of system table according to cluster status
     * 1. When scale-out to greater than 3 nodes, change the replication_num to 3
     * 3. When scale-in to less than 3 node, change it to retainedBackendNum
     */
    public static boolean alterSystemTableReplicationNumIfNecessary(String tableName) {
        int expectedReplicationNum =
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getSystemTableExpectedReplicationNum();
        int replica = GlobalStateMgr.getCurrentState()
                .getLocalMetastore().mayGetTable(StatsConstants.STATISTICS_DB_NAME, tableName)
                .map(tbl -> ((OlapTable) tbl).getPartitionInfo().getMinReplicationNum())
                .orElse((short) 1);

        if (replica != expectedReplicationNum) {
            String sql = String.format("ALTER TABLE %s.%s SET ('replication_num'='%d')",
                    StatsConstants.STATISTICS_DB_NAME, tableName, expectedReplicationNum);
            if (StringUtils.isNotEmpty(sql)) {
                RepoExecutor.getInstance().executeDDL(sql);
            }
            LOG.info("changed replication_number of table {} from {} to {}",
                    tableName, replica, expectedReplicationNum);
            return true;
        }
        return false;
    }

    // only support collect statistics for slotRef and subfield expr
    public static String getColumnName(Table table, Expr column) {
        String colName;
        if (column instanceof SlotRef) {
            colName = table.getColumn(((SlotRef) column).getColumnName()).getName();
        } else {
            colName = ((SubfieldExpr) column).getPath();
        }
        return colName;
    }

    public static Type getQueryStatisticsColumnType(Table table, String column) {
        String[] parts = column.split("\\.");
        Preconditions.checkState(parts.length >= 1);
        Column base = table.getColumn(parts[0]);
        if (base == null) {
            ErrorReport.reportSemanticException(ErrorCode.ERR_BAD_FIELD_ERROR, column, table.getName());
        }

        Type baseColumnType = base.getType();
        for (int i = 1; i < parts.length; i++) {
            if (baseColumnType.isStructType()) {
                StructType baseStructType = (StructType) baseColumnType;
                StructField field = baseStructType.getField(parts[i]);
                if (field.getType().isStructType()) {
                    baseColumnType = field.getType();
                } else {
                    return field.getType();
                }
            }
        }
        return baseColumnType;
    }
}