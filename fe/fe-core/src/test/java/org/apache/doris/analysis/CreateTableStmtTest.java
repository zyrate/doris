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

package org.apache.doris.analysis;

import org.apache.doris.analysis.ColumnDef.DefaultValue;
import org.apache.doris.catalog.AggregateType;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.PrimitiveType;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.ExceptionChecker;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.PropertyAnalyzer;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.mysql.privilege.AccessControllerManager;
import org.apache.doris.mysql.privilege.MockedAuth;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.Lists;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test for create table.
 **/
public class CreateTableStmtTest {
    private static final Logger LOG = LoggerFactory.getLogger(CreateTableStmtTest.class);
    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    // used to get default db
    private TableName tblName;
    private TableName tblNameNoDb;
    private List<ColumnDef> cols;
    private List<ColumnDef> invalidCols;
    private List<String> colsName;
    private List<String> invalidColsName;
    private Analyzer analyzer;

    @Mocked
    private AccessControllerManager accessManager;
    @Mocked
    private ConnectContext ctx;

    /**
     * set default db is 'db1'
     * table name is table1
     * Column: [col1 int; col2 string]
     **/
    @Before
    public void setUp() {
        // analyzer
        analyzer = AccessTestUtil.fetchAdminAnalyzer(false);
        // table name
        tblName = new TableName(InternalCatalog.INTERNAL_CATALOG_NAME, "db1", "table1");
        tblNameNoDb = new TableName(InternalCatalog.INTERNAL_CATALOG_NAME, "", "table1");
        // col
        cols = Lists.newArrayList();
        cols.add(new ColumnDef("col1", new TypeDef(ScalarType.createType(PrimitiveType.INT))));
        cols.add(new ColumnDef("col2", new TypeDef(ScalarType.createChar(10))));
        colsName = Lists.newArrayList();
        colsName.add("col1");
        colsName.add("col2");
        // invalid col
        invalidCols = Lists.newArrayList();
        invalidCols.add(new ColumnDef("col1", new TypeDef(ScalarType.createType(PrimitiveType.INT))));
        invalidCols.add(new ColumnDef("col2", new TypeDef(ScalarType.createChar(10))));
        invalidCols.add(new ColumnDef("col2", new TypeDef(ScalarType.createChar(10))));
        invalidColsName = Lists.newArrayList();
        invalidColsName.add("col1");
        invalidColsName.add("col2");
        invalidColsName.add("col2");

        MockedAuth.mockedAccess(accessManager);
        MockedAuth.mockedConnectContext(ctx, "root", "192.168.1.1");
    }

    @Test
    public void testNormal() throws UserException, AnalysisException {
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col1")), null, null, "");
        stmt.analyze(analyzer);
        Assert.assertEquals("testCluster:db1", stmt.getDbName());
        Assert.assertEquals("table1", stmt.getTableName());
        Assert.assertNull(stmt.getProperties());
    }

    @Test
    public void testCreateTableWithRandomDistribution() throws UserException {
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.DUP_KEYS, colsName), null, new RandomDistributionDesc(6), null, null, "");
        stmt.analyze(analyzer);
        Assert.assertEquals("testCluster:db1", stmt.getDbName());
        Assert.assertEquals("table1", stmt.getTableName());
        Assert.assertNull(stmt.getProperties());
        Assert.assertTrue(stmt.toSql().contains("DISTRIBUTED BY RANDOM\nBUCKETS 6"));
    }

    @Test
    public void testCreateTableUniqueKeyNormal() throws UserException {
        // setup
        Map<String, String> properties = new HashMap<>();
        ColumnDef col3 = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT)));
        col3.setIsKey(false);
        cols.add(col3);
        ColumnDef col4 = new ColumnDef("col4", new TypeDef(ScalarType.createType(PrimitiveType.STRING)));
        col4.setIsKey(false);
        cols.add(col4);
        // test normal case
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.UNIQUE_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col1")), properties, null, "");
        stmt.analyze(analyzer);
        Assert.assertEquals(col3.getAggregateType(), AggregateType.REPLACE);
        Assert.assertEquals(col4.getAggregateType(), AggregateType.REPLACE);
        // clear
        cols.remove(col3);
        cols.remove(col4);
    }

    @Test
    public void testCreateTableUniqueKeyMoW() throws UserException {
        // setup
        Map<String, String> properties = new HashMap<>();
        properties.put(PropertyAnalyzer.ENABLE_UNIQUE_KEY_MERGE_ON_WRITE, "true");
        ColumnDef col3 = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT)));
        col3.setIsKey(false);
        cols.add(col3);
        ColumnDef col4 = new ColumnDef("col4", new TypeDef(ScalarType.createType(PrimitiveType.STRING)));
        col4.setIsKey(false);
        cols.add(col4);
        // test merge-on-write
        CreateTableStmt stmt1 = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.UNIQUE_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col3")), properties, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage("Distribution column[col3] is not key column");
        stmt1.analyze(analyzer);

        CreateTableStmt stmt2 = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.UNIQUE_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col3")), properties, null, "");
        stmt2.analyze(analyzer);

        Assert.assertEquals(col3.getAggregateType(), AggregateType.NONE);
        Assert.assertEquals(col4.getAggregateType(), AggregateType.NONE);
        // clear
        cols.remove(col3);
        cols.remove(col4);
    }

    @Test
    public void testCreateTableDuplicateWithoutKeys() throws UserException {
        // setup
        Map<String, String> properties = new HashMap<>();
        properties.put(PropertyAnalyzer.PROPERTIES_ENABLE_DUPLICATE_WITHOUT_KEYS_BY_DEFAULT, "true");
        ColumnDef col3 = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BIGINT)));
        col3.setIsKey(false);
        cols.add(col3);
        ColumnDef col4 = new ColumnDef("col4", new TypeDef(ScalarType.createType(PrimitiveType.STRING)));
        col4.setIsKey(false);
        cols.add(col4);
        // test merge-on-write
        CreateTableStmt stmt1 = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.DUP_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col3")), properties, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage("table property 'enable_duplicate_without_keys_by_default' only can "
                                                + "set 'true' when create olap table by default.");
        stmt1.analyze(analyzer);

        CreateTableStmt stmt2 = new CreateTableStmt(false, false, tblName, cols, "olap",
                null, null,
                new HashDistributionDesc(10, Lists.newArrayList("col3")), properties, null, "");
        stmt2.analyze(analyzer);

        Assert.assertEquals(col3.getAggregateType(), AggregateType.NONE);
        Assert.assertEquals(col4.getAggregateType(), AggregateType.NONE);
        // clear
        cols.remove(col3);
        cols.remove(col4);
    }

    @Test
    public void testCreateTableWithRollup() throws UserException {
        List<AlterClause> ops = Lists.newArrayList();
        ops.add(new AddRollupClause("index1", Lists.newArrayList("col1", "col2"), null, "table1", null));
        ops.add(new AddRollupClause("index2", Lists.newArrayList("col2", "col3"), null, "table1", null));
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblName, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col1")), null, null, "", ops);
        stmt.analyze(analyzer);
        Assert.assertEquals("testCluster:db1", stmt.getDbName());
        Assert.assertEquals("table1", stmt.getTableName());
        Assert.assertNull(stmt.getProperties());
        Assert.assertTrue(stmt.toSql()
                .contains("rollup( `index1` (`col1`, `col2`) FROM `table1`, `index2` (`col2`, `col3`) FROM `table1`)"));
    }

    @Test
    public void testDefaultDbNormal() throws UserException {
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null,
                new HashDistributionDesc(10, Lists.newArrayList("col1")), null, null, "");
        stmt.analyze(analyzer);
        Assert.assertEquals("testDb", stmt.getDbName());
        Assert.assertEquals("table1", stmt.getTableName());
        Assert.assertNull(stmt.getPartitionDesc());
        Assert.assertNull(stmt.getProperties());
    }

    @Test(expected = AnalysisException.class)
    public void testNoDb(@Mocked Analyzer noDbAnalyzer) throws UserException, AnalysisException {
        // make default db return empty;
        new Expectations() {
            {
                noDbAnalyzer.getDefaultDb();
                minTimes = 0;
                result = "";

                noDbAnalyzer.getClusterName();
                minTimes = 0;
                result = "cluster";
            }
        };
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null, new RandomDistributionDesc(10), null, null, "");
        stmt.analyze(noDbAnalyzer);
    }

    @Test(expected = AnalysisException.class)
    public void testEmptyCol() throws UserException, AnalysisException {
        // make default db return empty;
        List<ColumnDef> emptyCols = Lists.newArrayList();
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, emptyCols, "olap", new KeysDesc(), null,
                new RandomDistributionDesc(10), null, null, "");
        stmt.analyze(analyzer);
    }

    @Test(expected = AnalysisException.class)
    public void testDupCol() throws UserException, AnalysisException {
        // make default db return empty;
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, invalidCols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, invalidColsName), null, new RandomDistributionDesc(10), null, null, "");
        stmt.analyze(analyzer);
    }

    @Test(expected = AnalysisException.class)
    public void testTypeAll() throws UserException {
        final ArrayList<ColumnDef> colAllList = Lists.newArrayList();
        colAllList.add(new ColumnDef("colAll", new TypeDef(ScalarType.createType(PrimitiveType.ALL))));
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, colAllList, "olap", new KeysDesc(), null,
                new RandomDistributionDesc(10), null, null, "");
        stmt.analyze(analyzer);
    }


    @Test
    public void testBmpHllKey() throws Exception {
        ColumnDef bitmap = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BITMAP)));
        cols.add(bitmap);
        colsName.add("col3");

        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null, new RandomDistributionDesc(10), null, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage("Key column can not set bitmap or hll type:col3");
        stmt.analyze(analyzer);

        cols.remove(bitmap);

        ColumnDef hll = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.HLL)));
        cols.add(hll);
        stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap", new KeysDesc(KeysType.AGG_KEYS, colsName),
                null, new RandomDistributionDesc(10), null, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage("Key column can not set bitmap or hll type:col3");
        stmt.analyze(analyzer);
    }

    @Test
    public void testBmpHllNoAggTab() throws Exception {
        ColumnDef bitmap = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BITMAP)));
        cols.add(bitmap);
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap",
                new KeysDesc(KeysType.DUP_KEYS, colsName), null, new RandomDistributionDesc(10), null, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage(
                "Aggregate type `col3` bitmap NONE NOT NULL COMMENT \"\" is not compatible with primitive type bitmap");
        stmt.analyze(analyzer);

        cols.remove(bitmap);
        ColumnDef hll = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.HLL)));
        cols.add(hll);
        stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap", new KeysDesc(KeysType.DUP_KEYS, colsName),
                null, new RandomDistributionDesc(10), null, null, "");
        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage(
                "Aggregate type `col3` hll NONE NOT NULL COMMENT \"\" is not compatible with primitive type hll");
        stmt.analyze(analyzer);
    }

    @Test
    public void testBmpHllIncAgg() throws Exception {
        ColumnDef bitmap = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.BITMAP)));
        bitmap.setAggregateType(AggregateType.SUM);

        cols.add(bitmap);
        CreateTableStmt stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap",
                new KeysDesc(KeysType.AGG_KEYS, colsName), null, new RandomDistributionDesc(10), null, null, "");

        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage(
                String.format("Aggregate type %s is not compatible with primitive type %s", bitmap.toString(),
                        bitmap.getTypeDef().getType().toSql()));
        stmt.analyze(analyzer);

        cols.remove(bitmap);
        ColumnDef hll = new ColumnDef("col3", new TypeDef(ScalarType.createType(PrimitiveType.HLL)));
        hll.setAggregateType(AggregateType.SUM);
        cols.add(hll);
        stmt = new CreateTableStmt(false, false, tblNameNoDb, cols, "olap", new KeysDesc(KeysType.AGG_KEYS, colsName),
                null, new RandomDistributionDesc(10), null, null, "");

        expectedEx.expect(AnalysisException.class);
        expectedEx.expectMessage(
                String.format("Aggregate type %s is not compatible with primitive type %s", hll.toString(),
                        hll.getTypeDef().getType().toSql()));
        stmt.analyze(analyzer);
    }

    @Test
    public void testCreateIcebergTable() throws UserException {
        Config.disable_iceberg_hudi_table = false;
        Map<String, String> properties = new HashMap<>();
        properties.put("iceberg.database", "doris");
        properties.put("iceberg.table", "test");
        properties.put("iceberg.hive.metastore.uris", "thrift://127.0.0.1:9087");
        CreateTableStmt stmt = new CreateTableStmt(false, true, tblName, "iceberg", properties, "");
        stmt.analyze(analyzer);

        Assert.assertEquals("CREATE EXTERNAL TABLE `testCluster:db1`.`table1` (\n" + "\n" + ") ENGINE = iceberg\n"
                + "PROPERTIES (\"iceberg.database\"  =  \"doris\",\n"
                + "\"iceberg.hive.metastore.uris\"  =  \"thrift://127.0.0.1:9087\",\n"
                + "\"iceberg.table\"  =  \"test\")", stmt.toString());
    }

    @Test
    public void testCreateHudiTable() throws UserException {
        Config.disable_iceberg_hudi_table = false;
        Map<String, String> properties = new HashMap<>();
        properties.put("hudi.database", "doris");
        properties.put("hudi.table", "test");
        properties.put("hudi.hive.metastore.uris", "thrift://127.0.0.1:9087");
        CreateTableStmt stmt = new CreateTableStmt(false, true, tblName, "hudi", properties, "");
        stmt.analyze(analyzer);

        Assert.assertEquals("CREATE EXTERNAL TABLE `testCluster:db1`.`table1` (\n" + "\n" + ") ENGINE = hudi\n"
                        + "PROPERTIES (\"hudi.database\"  =  \"doris\",\n"
                        + "\"hudi.hive.metastore.uris\"  =  \"thrift://127.0.0.1:9087\",\n"
                        + "\"hudi.table\"  =  \"test\")",
                stmt.toString());
    }

    @Test
    public void testCreateHudiTableWithSchema() throws UserException {
        Config.disable_iceberg_hudi_table = false;
        Map<String, String> properties = new HashMap<>();
        properties.put("hudi.database", "doris");
        properties.put("hudi.table", "test");
        properties.put("hudi.hive.metastore.uris", "thrift://127.0.0.1:9087");
        CreateTableStmt stmt = new CreateTableStmt(false, true, tblName, "hudi", properties, "");
        ColumnDef idCol = new ColumnDef("id", TypeDef.create(PrimitiveType.INT));
        stmt.addColumnDef(idCol);
        ColumnDef nameCol = new ColumnDef("name", TypeDef.create(PrimitiveType.INT), false, null, true,
                ColumnDef.DefaultValue.NOT_SET, "");
        stmt.addColumnDef(nameCol);
        stmt.analyze(analyzer);

        Assert.assertEquals(
                "CREATE EXTERNAL TABLE `testCluster:db1`.`table1` (\n" + "  `id` int(11) NOT NULL COMMENT \"\",\n"
                        + "  `name` int(11) NULL COMMENT \"\"\n" + ") ENGINE = hudi\n"
                        + "PROPERTIES (\"hudi.database\"  =  \"doris\",\n"
                        + "\"hudi.hive.metastore.uris\"  =  \"thrift://127.0.0.1:9087\",\n"
                        + "\"hudi.table\"  =  \"test\")", stmt.toString());
    }

    @Test
    public void testOdbcString() throws AnalysisException {
        ColumnDef col = new ColumnDef("string_col", TypeDef.create(PrimitiveType.STRING), true, null, true,
                new DefaultValue(false, null), "");
        ExceptionChecker.expectThrowsWithMsg(AnalysisException.class,
                "String Type should not be used in key column[string_col].", () -> col.analyze(true));
        col.analyze(false);
    }
}
