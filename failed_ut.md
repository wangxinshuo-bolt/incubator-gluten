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

## 非 Paimon 失败用例 Root Cause 分析

> 基于 `output/test_spark35.nohup.log` 分析（不含 `BoltPaimonSuite` 序号 11-35）。

| 序号 | Suite | Test Name | 状态 | Root Cause | 关键错误信息 |
|---|---|---|---|---|---|
| 1 | `JsonFunctionsValidateSuite` | `json_object_keys` | Failed | native `json_object_keys` 对畸形 JSON 抛 BoltUserError，与 vanilla Spark 行为不一致导致 Job abort | `BoltUserError ... INVALID_ARGUMENT ... TAPE_ERROR: The JSON document has an improper structure` |
| 2 | `ScalarFunctionsValidateSuite` | `raise_error, assert_true` | Ignored | 用例被主动跳过（`ignore`），非真实失败 | `!!! IGNORED !!!` |
| 3 | `BoltAggregateFunctionsSuite` | `distinct functions` | Ignored | 用例被主动跳过（`ignore`），非真实失败 | `!!! IGNORED !!!` |
| 4 | `BoltAggregateFunctionsSuite` | `drop redundant partial sort which has pre-project when offload sortAgg` | Ignored | 用例被主动跳过（`ignore`），非真实失败 | `!!! IGNORED !!!` |
| 5 | `BoltBloomFilterTest` | `<class-level error>` | Failed | native backend 初始化失败：`BoltMemoryManager` 容量为 0，`setup` 阶段整类报错 | `GlutenException: BoltMemoryManager expects capacity is bigger than 0` |
| 6 | `ArrowFilesystemTest` | `testBaseCsvRead` | Failed | 加载 Arrow dataset JNI 库时 protobuf 符号缺失（native 链接问题） | `UnsatisfiedLinkError: ... undefined symbol: _ZTIN6google8protobuf7MessageE` |
| 7 | `ColumnarBatchTest` | `testToString` | Failed | BoltBackend 实例为 null，R2C 构造 NativeMemoryManager 失败 | `GlutenException: BoltBackend instance is null.` |
| 8 | `ColumnarBatchTest` | `testCompose` | Failed | BoltBackend 实例为 null，`BoltColumnarBatches.toBoltBatch` 处失败 | `GlutenException: BoltBackend instance is null.` |
| 9 | `FallbackSuite` | `fallback with index based schema evolution` | Failed | 断言失败：期望捕获 fallback 事件集合，实际为空 | `ArrayBuffer() was empty (FallbackSuite.scala:322)` |
| 10 | `DynamicOffHeapSizingSuite` | `Dynamic off-heap sizing` | Failed | 广播序列化时 native 目标缓冲区大小为 0，ColumnarBatch 序列化失败 | `The target buffer size is insufficient: 0 vs.65165` |
| 36 | `GlobalOffHeapMemorySuite` | `Sanity` | Failed | 异常类型不匹配：期望 `GlutenException`，实抛 `ThrowOnOomMemoryTarget$OutOfMemoryException` | `Expected exception ...GlutenException ... but ...OutOfMemoryException was thrown (:55)` |
| 37 | `GlobalOffHeapMemorySuite` | `Release task` | Failed | 同上，OOM 异常包装/类型不匹配 | `Expected exception ...GlutenException ... but ...OutOfMemoryException was thrown (:111)` |
| 38 | `BoltRoughCostModelSuite` | `fallback trivial project if its neighbor nodes fell back` | Failed | 成本模型/回退判定与预期不符：期望 trivial project 回退(=0)，实际未回退(=1) | `1 did not equal 0`（plan 仍含 `ProjectExecTransformer`） |
| 39 | `BoltRoughCostModelSuite` | `avoid adding r2c whose schema contains complex data types` | Failed | 期望避免为含复杂类型 schema 添加 r2c(=0)，实际插入了 r2c(=1) | `1 did not equal 0` `ReadSchema: struct<c3:array<bigint>>` |
| 40 | `BoltUdfSuiteLocal` | `test native hive udf` | Failed | native hive udf 未按预期注册/回退，fallback 集合为空 | `Set() did not contain "...UDFStringString" (BoltUdfSuite.scala:157)` |
| 41 | `AllBoltConfiguration` | `Check bolt backend configs` | Failed | 配置文档未同步，需重新生成 `docs/bolt-configuration.md` | `... is out of date. ... Expected 89, but got 87` |
| 42 | `RowToColumnarFuzzer` | `row to columnar` | Failed | 用例超时：fuzzer 20 分钟内未完成（数据量过大，单 task 达 282MB+） | `did not complete within 20 minutes. (RowToColumnarFuzzer.scala:49)` |
| 43 | `MiscOperatorSuite` | `test cross join` | Failed | join metrics 校验失败：native 返回 metrics 数量与 updater 期望不匹配 | `AssertionError ... JoinMetricsUpdaterBase.updateJoinMetrics(:49)` |
| 44 | `MiscOperatorSuite` | `Columnar cartesian product with other join` | Failed | 同上，cartesian product 场景 join metrics 断言失败 | `AssertionError ... JoinMetricsUpdaterBase.updateJoinMetrics(:49)` |

### 共性根因归类

- **A. Native backend 初始化/加载失败（序号 5、6、7、8）**：`BoltMemoryManager` 容量传 0、`BoltBackend instance is null`、Arrow JNI protobuf 符号缺失，属环境/构建层面 native 库问题，与测试逻辑无关。详见下方「A 类问题深入分析与修复」。
- **B. Join metrics 断言失败（序号 43、44）**：同一 `JoinMetricsUpdater.scala:49` assert 失败，native 返回 metrics 结构与 updater 期望数量不匹配。
- **C. 内存/缓冲区 native 异常（序号 10、36、37）**：off-heap 内存路径异常语义变更（buffer size=0、OOM 异常类型不匹配）。
- **D. Fallback/成本模型判定与预期不符（序号 9、38、39、40）**：Bolt 后端 fallback / RoughCostModel / UDF 注册行为与既有测试期望存在差异（断言差异）。
- **E. 文档/配置未同步（序号 41）**：`bolt-configuration.md` 需 `dev/gen_all_config_docs.sh` 重新生成。
- **F. 超时（序号 42）**：RowToColumnarFuzzer 数据规模过大导致性能/超时。
- **G. 实为 IGNORED 非真实失败（序号 2、3、4）**：日志中仅 `!!! IGNORED !!!`，应标注为“已跳过”。

## A 类问题深入分析与修复

A 类内部实际有两个独立根因：

### A-1. `BoltMemoryManager expects capacity is bigger than 0`（序号 5、7、8）

**调用链**：
- `BoltBackendTestBase.setup()` → `new BoltListenerApi().onExecutorStart(MockBoltBackend.mockPluginContext())`（`backends-bolt/src/test/java/org/apache/gluten/test/BoltBackendTestBase.java:30`）
- `BoltListenerApi.initialize` → `NativeBackendInitializer.initialize(...)`（`BoltListenerApi.scala:222-224`）
- native `BoltGlutenMemoryManager::getTaskMemoryCapacity` 读取 offheap capacity（`cpp/core/memory/BoltGlutenMemoryManager.cc:129-145`）

**根因**：
- native 侧计算 capacity 读取的是 **executor 级** key `spark.gluten.memory.offHeap.size.in.bytes`（`GlutenCoreConfig.COLUMNAR_OFFHEAP_SIZE_IN_BYTES`），fallback 才是 `spark.memory.offHeap.size`（`ConfigurationResolver.h:76-83`）。
- 真实运行时该 key 由 `GlutenPlugin.setPredefinedConfigs`（`GlutenPlugin.scala:141`）根据 `spark.memory.offHeap.size` 派生并 set；但单测直接裸调 `onExecutorStart`，**绕过 GlutenPlugin**，该 key 从未赋值。
- `MockBoltBackend` 里设置的是 **task 级** key `COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES`（native 不读），fallback 用的 `spark.memory.offHeap.size` 又被 `GlutenConfig.getNativeBackendConf` 白名单（`GlutenConfig.scala:663-682`）过滤，传不到 native。
- 结果 native 两个 key 都取默认 `"0"`，触发 `GLUTEN_CHECK(capacity > 0)`。
- 该问题由提交 `12db72e1b7 fix some uts` 引入（选错了 key），refactor 提交 `6f4b675fc8` 未改动 offheap key。

**velox 为何不崩**：velox 单测走真实 `TestSparkSession` + GlutenPlugin，且 velox native 对缺失 offheap 用 `kMaxMemory` 兜底，无 `capacity>0` 强校验。

**修复（已实施）**：`backends-bolt/src/test/java/org/apache/gluten/test/MockBoltBackend.java`，将 task 级 key 改为 native 真正读取的 executor 级 key：
```java
conf.set(GlutenCoreConfig.COLUMNAR_OFFHEAP_SIZE_IN_BYTES().key(), "1g");
```
该 key 已在 `getNativeBackendConf` 白名单中，设置后即可正确传入 native。此修复同时覆盖序号 5、7、8（7、8 的 `BoltBackend instance is null` 是 backend 初始化失败的连锁反应）。

### A-2. `undefined symbol: _ZTIN6google8protobuf7MessageE`（序号 6）

**根因**：加载 Arrow dataset JNI 库时缺少 protobuf 符号，属 native 构建/链接（protobuf 符号未导出或版本不匹配）问题，非测试逻辑或 conf 问题。需在 native 构建层面排查（protobuf 链接方式 / `SharedLibraryLoader` 中 `libprotobuf` 加载），不在本次 Java 侧修复范围内。

## 备注

- 以上记录基于当前选中的 UT。
- 已补充 `output/test_spark35.nohup.log` 中提取到的失败 case。
- A-1 已修复（`MockBoltBackend` offheap key）；A-2（protobuf 符号）待 native 构建侧处理。
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/JsonFunctionsValidateSuite.scala:351`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/ScalarFunctionsValidateSuite.scala:559`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:654`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:1178`
