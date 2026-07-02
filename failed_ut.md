# Failed UT 记录

## 选中失败用例

| 序号 | Suite | Test Name | 文件位置 | 状态 |
|---|---|---|---|---|
| 1 | `JsonFunctionsValidateSuite` | `json_object_keys` | `backends-bolt/src/test/scala/org/apache/gluten/functions/JsonFunctionsValidateSuite.scala:351` | Failed |
| 2 | `ScalarFunctionsValidateSuite` | `raise_error, assert_true` | `backends-bolt/src/test/scala/org/apache/gluten/functions/ScalarFunctionsValidateSuite.scala:559` | Failed |
| 3 | `BoltAggregateFunctionsSuite` | `distinct functions` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:654` | Failed |
| 4 | `BoltAggregateFunctionsSuite` | `drop redundant partial sort which has pre-project when offload sortAgg` | `backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:1178` | Failed |

## 备注

- 以上记录基于当前选中的 UT。
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/JsonFunctionsValidateSuite.scala:351`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/functions/ScalarFunctionsValidateSuite.scala:559`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:654`
- 测试定义位置：`backends-bolt/src/test/scala/org/apache/gluten/execution/BoltAggregateFunctionsSuite.scala:1178`
