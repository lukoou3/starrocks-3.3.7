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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/catalog/DropPartitionTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.starrocks.common.DdlException;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DDLStmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.RecoverPartitionStmt;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

public class DropPartitionTest {
    private static ConnectContext connectContext;

    @BeforeClass
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();

        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        // create database
        String createDbStmtStr = "create database test;";
        String createTableStr = "create table test.tbl1(d1 date, k1 int, k2 bigint) duplicate key(d1, k1) "
                + "PARTITION BY RANGE(d1) (PARTITION p20210201 VALUES [('2021-02-01'), ('2021-02-02')),"
                + "PARTITION p20210202 VALUES [('2021-02-02'), ('2021-02-03')),"
                + "PARTITION p20210203 VALUES [('2021-02-03'), ('2021-02-04'))) distributed by hash(k1) "
                + "buckets 1 properties('replication_num' = '1');";
        createDb(createDbStmtStr);
        createTable(createTableStr);
    }

    private static void waitPartitionClearFinished(long id, long time) {
        while (GlobalStateMgr.getCurrentState().getRecycleBin().getRecyclePartitionInfo(id) != null) {
            GlobalStateMgr.getCurrentState().getRecycleBin().erasePartition(time);
            try {
                Thread.sleep(100);
            } catch (Exception ignore) {
            }
        }
    }

    private static void createDb(String sql) throws Exception {
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        GlobalStateMgr.getCurrentState().getMetadata().createDb(createDbStmt.getFullDbName());
    }

    private static void createTable(String sql) throws Exception {
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        StarRocksAssert.utCreateTableWithRetry(createTableStmt);
    }

    private static void dropPartition(String sql) throws Exception {
        AlterTableStmt alterTableStmt = (AlterTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        DDLStmtExecutor.execute(alterTableStmt, connectContext);
    }

    @Test
    public void testNormalDropPartition() throws Exception {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        OlapTable table = (OlapTable) db.getTable("tbl1");
        Partition partition = table.getPartition("p20210201");
        long tabletId = partition.getBaseIndex().getTablets().get(0).getId();
        String dropPartitionSql = " alter table test.tbl1 drop partition p20210201;";
        dropPartition(dropPartitionSql);
        List<Replica> replicaList =
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getReplicasByTabletId(tabletId);
        partition = table.getPartition("p20210201");
        Assert.assertEquals(1, replicaList.size());
        Assert.assertNull(partition);
        String recoverPartitionSql = "recover partition p20210201 from test.tbl1";
        RecoverPartitionStmt recoverPartitionStmt =
                (RecoverPartitionStmt) UtFrameUtils.parseStmtWithNewParser(recoverPartitionSql, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().recoverPartition(recoverPartitionStmt);
        partition = table.getPartition("p20210201");
        Assert.assertNotNull(partition);
        Assert.assertEquals("p20210201", partition.getName());
    }

    @Test
    public void testForceDropPartition() throws Exception {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        OlapTable table = (OlapTable) db.getTable("tbl1");
        Partition partition = table.getPartition("p20210202");
        long partitionId = partition.getId();
        long tabletId = partition.getBaseIndex().getTablets().get(0).getId();
        String dropPartitionSql = " alter table test.tbl1 drop partition p20210202 force;";
        dropPartition(dropPartitionSql);
        List<Replica> replicaList;
        replicaList = GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getReplicasByTabletId(tabletId);
        partition = table.getPartition("p20210202");
        Assert.assertFalse(replicaList.isEmpty());
        Assert.assertNull(partition);
        String recoverPartitionSql = "recover partition p20210202 from test.tbl1";
        RecoverPartitionStmt recoverPartitionStmt =
                (RecoverPartitionStmt) UtFrameUtils.parseStmtWithNewParser(recoverPartitionSql, connectContext);
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "No partition named 'p20210202' in recycle bin that belongs to table 'tbl1'",
                () -> GlobalStateMgr.getCurrentState().getLocalMetastore().recoverPartition(recoverPartitionStmt));

        GlobalStateMgr.getCurrentState().getRecycleBin().erasePartition(System.currentTimeMillis());
        waitPartitionClearFinished(partitionId, System.currentTimeMillis());
        replicaList = GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getReplicasByTabletId(tabletId);
        Assert.assertTrue(replicaList.isEmpty());
    }

    @Test
    public void testDropPartitionAndReserveTablets() throws Exception {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        OlapTable table = (OlapTable) db.getTable("tbl1");
        Partition partition = table.getPartition("p20210203");
        long tabletId = partition.getBaseIndex().getTablets().get(0).getId();
        table.dropPartitionAndReserveTablet("p20210203");
        List<Replica> replicaList =
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getReplicasByTabletId(tabletId);
        partition = table.getPartition("p20210203");
        Assert.assertEquals(1, replicaList.size());
        Assert.assertNull(partition);
    }
}
