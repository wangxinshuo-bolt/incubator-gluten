# Failed UT 记录

## 选中失败用例

| 序号 | Suite | Test Name | 文件位置 | 状态 |
|---|---|---|---|---|
| 1 | `JsonFunctionsValidateSuite` | `json_object_keys` | `backends-bolt/src/test/scala/org/apache/gluten/functions/JsonFunctionsValidateSuite.scala:351` | Failed |
| 2 | `ScalarFunctionsValidateSuite` | `raise_error, assert_true` | `backends-bolt/src/test/scala/org/apache/gluten/functions/ScalarFunctionsValidateSuite.scala:559` | Failed |
| 3 | `BoltAggregateFunctionsSuite` | `distinct functions` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:654` | Failed |
| 4 | `BoltAggregateFunctionsSuite` | `drop redundant partial sort which has pre-project when offload sortAgg` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:1178` | Failed |
| 5 | `org.apache.gluten.utils.BoltBloomFilterTest` | `<class-level error>` | `backends-bolt/src/test/java/org/apache/gluten/utils/BoltBloomFilterTest.java:30` | Failed |
| 6 | `org.apache.gluten.fs.ArrowFilesystemTest` | `testBaseCsvRead` | `backends-bolt/src/test/java/org/apache/gluten/fs/ArrowFilesystemTest.java:80` | Failed |
| 7 | `org.apache.gluten.columnarbatch.ColumnarBatchTest` | `testToString` | `backends-bolt/src/test/java/org/apache/gluten/columnarbatch/ColumnarBatchTest.java:213` | Failed |
| 8 | `org.apache.gluten.columnarbatch.ColumnarBatchTest` | `testCompose` | `backends-bolt/src/test/java/org/apache/gluten/columnarbatch/ColumnarBatchTest.java:179` | Failed |
| 9 | `FallbackSuite` | `fallback with index based schema evolution` | `backends-bolt/src/test/scala/org/apache/gluten/execution/FallbackSuite.scala:299` | Failed |
| 10 | `DynamicOffHeapSizingSuite` | `Dynamic off-heap sizing` | `backends-bolt/src/test/scala/org/apache/gluten/execution/DynamicOffHeapSizingSuite.scala:43` | Failed |
| 11 | `BoltPaimonSuite` | `paimon transformer exists: primary key table(full compact)` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:190` | Failed |
| 12 | `BoltPaimonSuite` | `paimon filter push down: value filter` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:240` | Failed |
| 13 | `BoltPaimonSuite` | `paimon transformer exists: primary key with sequence field` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:281` | Failed |
| 14 | `BoltPaimonSuite` | `paimon transformer fallback: primary key table - custom single sequence key` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:337` | Failed |
| 15 | `BoltPaimonSuite` | `paimon transformer fallback: primary key table - custom multiple sequence key` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:385` | Failed |
| 16 | `BoltPaimonSuite` | `paimon transformer fallback: primary key table with partial-update engine` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:478` | Failed |
| 17 | `BoltPaimonSuite` | `paimon transformer: primary key table - NULL + NOT NULL` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:521` | Failed |
| 18 | `BoltPaimonSuite` | `paimon transformer: primary key table - NOT NULL + NULL` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:548` | Failed |
| 19 | `BoltPaimonSuite` | `paimon transformer: primary key table - NOT NULL + NOT NULL` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:575` | Failed |
| 20 | `BoltPaimonSuite` | `paimon transformer: primary key table - custom row kind` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:602` | Failed |
| 21 | `BoltPaimonSuite` | `paimon aggregate: sum` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:650` | Failed |
| 22 | `BoltPaimonSuite` | `paimon aggregate: product` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:759` | Failed |
| 23 | `BoltPaimonSuite` | `paimon aggregate: max` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:839` | Failed |
| 24 | `BoltPaimonSuite` | `paimon aggregate: min` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:888` | Failed |
| 25 | `BoltPaimonSuite` | `paimon aggregate: last_value` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:937` | Failed |
| 26 | `BoltPaimonSuite` | `paimon aggregate: last_non_null_value` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:986` | Failed |
| 27 | `BoltPaimonSuite` | `paimon aggregate: listagg` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1035` | Failed |
| 28 | `BoltPaimonSuite` | `paimon aggregate: bool_and` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1075` | Failed |
| 29 | `BoltPaimonSuite` | `paimon aggregate: bool_or` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1128` | Failed |
| 30 | `BoltPaimonSuite` | `paimon aggregate: first_value` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1181` | Failed |
| 31 | `BoltPaimonSuite` | `paimon aggregate: first_non_null_value` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1230` | Failed |
| 32 | `BoltPaimonSuite` | `paimon metadata columns: __paimon_row_index - MOR tables with multiple insert operations` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1582` | Failed |
| 33 | `BoltPaimonSuite` | `paimon metadata columns: __paimon_row_index - MOR table, overwrite records` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1604` | Failed |
| 34 | `BoltPaimonSuite` | `paimon metadata columns: __paimon_file_path - tables with multiple insert operations` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1754` | Failed |
| 35 | `BoltPaimonSuite` | `paimon metadata columns: __paimon_file_path - MOR table, overwrite records` | `backends-bolt/src-paimon/test/scala/org/apache/gluten/execution/BoltPaimonSuite.scala:1794` | Failed |
| 36 | `GlobalOffHeapMemorySuite` | `Sanity` | `backends-bolt/src/test/scala/org/apache/spark/memory/GlobalOffHeapMemorySuite.scala:41` | Failed |
| 37 | `GlobalOffHeapMemorySuite` | `Release task` | `backends-bolt/src/test/scala/org/apache/spark/memory/GlobalOffHeapMemorySuite.scala:99` | Failed |
| 38 | `BoltRoughCostModelSuite` | `fallback trivial project if its neighbor nodes fell back` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltRoughCostModelSuite.scala:46` | Failed |
| 39 | `BoltRoughCostModelSuite` | `avoid adding r2c whose schema contains complex data types` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltRoughCostModelSuite.scala:54` | Failed |
| 40 | `BoltUdfSuiteLocal` | `test native hive udf` | `backends-bolt/src/test/scala/org/apache/gluten/expression/BoltUdfSuite.scala:145` | Failed |
| 41 | `AllBoltConfiguration` | `Check bolt backend configs` | `backends-bolt/src/test/scala/org/apache/gluten/config/AllBoltConfiguration.scala:28` | Failed |
| 42 | `RowToColumnarFuzzer` | `row to columnar` | `backends-bolt/src/test/scala/org/apache/gluten/fuzzer/RowToColumnarFuzzer.scala:49` | Failed |
| 43 | `MiscOperatorSuite` | `test cross join` | `backends-bolt/src/test/scala/org/apache/gluten/execution/MiscOperatorSuite.scala:1302` | Failed |
| 44 | `MiscOperatorSuite` | `Columnar cartesian product with other join` | `backends-bolt/src/test/scala/org/apache/gluten/execution/MiscOperatorSuite.scala:1516` | Failed |

## 备注

- 以上记录基于当前选中的 UT。
- 已补充 `output/test_spark35.nohup.log` 中提取到的失败 case。
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/JsonFunctionsValidateSuite.scala:351`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/ScalarFunctionsValidateSuite.scala:559`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:654`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:1178`
