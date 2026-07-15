# `cpp/core` 与 Community Main 差异收敛方案

## 1. 背景与基线

本文档记录当前分支相对 Community Main 基线的 `cpp/core` 差异收敛决策、实施结果和验证边界。

- 基线提交：`9baa47c4240bd761878c9f99dc2fd96d9749b83b`
- 实施起点 HEAD：`04d82611dd40081b4d0e304b5929b9629680f215`
- 原始 diff 方向：基线提交到实施起点 HEAD
- 当前 residual patch 范围：基线提交到当前工作树，包含尚未提交的 selective rollback
- 当前 residual patch：`CPP_CORE_DIFF_VS_COMMUNITY_MAIN.patch`
- 当前 residual patch SHA-256：`efd38574c3a51b11a6fe499876ba197fc30eb8757d5b87d935aa85a6ceb34fca`
- 实施起点 HEAD diff SHA-256（仅审计记录）：`13995220a4a87ff5dce994201eded5cb42a22705e7e3ea0f07854b6ee2fe1757`

实施起点 HEAD 共有 12 个文件存在差异，合计新增 141 行、删除 180 行，共 321 行增删：

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `cpp/core/CMakeLists.txt` | 23 | 17 |
| `cpp/core/compute/Runtime.h` | 3 | 0 |
| `cpp/core/jni/JniCommon.cc` | 11 | 0 |
| `cpp/core/jni/JniCommon.h` | 11 | 4 |
| `cpp/core/jni/JniError.cc` | 5 | 0 |
| `cpp/core/jni/JniError.h` | 1 | 4 |
| `cpp/core/jni/JniWrapper.cc` | 29 | 72 |
| `cpp/core/shuffle/Options.h` | 5 | 0 |
| `cpp/core/symbols.map` | 1 | 0 |
| `cpp/core/tests/CMakeLists.txt` | 1 | 0 |
| `cpp/core/tests/JniInputIteratorTest.cc` | 45 | 0 |
| `cpp/core/utils/Metrics.h` | 6 | 83 |

本轮没有整体回退 `04d82611d`。实际做法是在该提交之上进行 selective rollback：仅恢复社区 Metrics、CMake、include 和未使用配置等可收敛部分，保留 split SO 所需的 JNI 状态、Runtime iterator hook、符号隔离和 Core 测试。所有实施内容仍位于工作区，未创建新提交。

## 2. 目标与原则

目标是在不回退 Bolt 功能、不破坏 Velox/community 行为、不引入跨 DSO 生命周期问题的前提下，尽可能减少 `cpp/core` 相对基线的差异。

收敛时遵循以下原则：

1. Bolt 专有逻辑优先放在 `cpp/bolt` 或 `backends-bolt`。
2. `cpp/core` 只保留多个 backend 或多个 JNI SO 之间真正需要的稳定边界。
3. 不通过改变公共结构体布局、替换公共 JNI 协议或依赖传递 include 来适配单一 backend。
4. 纯格式、include 顺序和未使用字段不进入长期维护 patch。
5. 对多 DSO 单例、符号可见性和 JNI 生命周期问题，不以减少行数为由恢复存在风险的实现。
6. 每个阶段单独验证，避免同时修改 Metrics、CMake 和 JNI 边界后难以定位回归。

## 3. 预期结果

### 3.1 实际收敛结果

当前工作树相对基线的 `cpp/core` residual diff 已从 12 个文件、321 行增删收敛到 10 个文件、123 行增删，其中新增 102 行、删除 21 行；总增删减少 198 行，降幅约 61.7%。

最终数字高于原先约 80～85 行的最小化设想，主要原因是最终决定继续保留 Core iterator dispatch 测试及其 46 行差异。

实际仍保留差异的文件：

- `cpp/core/CMakeLists.txt`：恢复社区 Arrow/HDFS 逻辑，仅保留非 Linux 构建保护。
- `cpp/core/compute/Runtime.h`：保留 backend 自定义 JNI input iterator 的最小虚接口。
- `cpp/core/jni/JniCommon.cc`
- `cpp/core/jni/JniCommon.h`
- `cpp/core/jni/JniError.cc`
- `cpp/core/jni/JniError.h`
- `cpp/core/jni/JniWrapper.cc`
- `cpp/core/symbols.map`
- `cpp/core/tests/CMakeLists.txt`
- `cpp/core/tests/JniInputIteratorTest.cc`

以下文件已恢复到基线，或已将适配迁出 `cpp/core`：

- `cpp/core/shuffle/Options.h`
- `cpp/core/utils/Metrics.h`

### 3.2 未选择的进一步减量空间

如果未来将 iterator 测试迁到 Bolt，并恢复无条件执行 `ld`/`ldd`，预计仍可进一步减少约 68 行增删。

本轮没有选择这两项，原因是：

- 保留 Core 测试可以维持当前轻量、稳定的 virtual dispatch 覆盖。
- Gluten 文档和源码仍支持 macOS 源码构建，非 Linux 平台没有 `ldd`。

本轮已选择恢复社区 HDFS 无条件安装。该选择减少了 Core diff，但接受源码树缺少 `cpp/core/resources/libhdfs.so` 时 `cmake --install` 失败的已知风险。

## 4. 分项收敛方案

### 4.1 统一恢复社区 44-array Metrics 协议

#### 现状问题

当前改动用 JSON payload 替换了社区 Metrics 的固定数组协议：

- `cpp/core/utils/Metrics.h` 删除了数组、`TYPE` 枚举和 `get(TYPE)`。
- Core JNI 的 Java 构造函数签名从多组 `long[]` 改为 JSON 字符串。
- Velox 和 Bolt 的 native 指标先序列化成 JSON，再由 JVM 解析回 `OperatorMetrics` 字段。

这部分约占 150 行 `cpp/core` 增删，是当前最大的 diff 来源，同时改变了 Core/Velox 的公共数据模型和 JNI 契约。

经调用点检查，Bolt 的 `metricsJson` 只被 Bolt `MetricsUtil.scala` 解析。JVM 最终消费的仍是社区 `OperatorMetrics` 已定义的固定指标；当前没有其他调用方读取原始 JSON，也没有调用方消费 JSON 中未映射的任意 custom metric。因此 JSON 层可以删除，不需要新增另一套 Bolt metrics 协议。

#### 目标结构

```text
Velox operator stats ----\
                          -> 社区 Metrics::TYPE 数组
Bolt operator stats  -----/          |
                                     v
                              Core nativeFetchMetrics
                                     |
                                     v
                              Java Metrics(long[] ...)
                                     |
                                     v
                           backend MetricsUtil/Updater
```

Velox 和 Bolt 统一使用社区定义的 44-array 协议。Bolt 在 native 收集阶段通过保留的 `cpp/bolt/compute/BoltMetrics.h` helper，把自身的 operator/custom stats 映射到固定字段；该 helper 是 backend 内部映射实现，不是新的 DTO、公共协议或 Bolt 专用 metrics JNI。

#### 具体改动

1. 完整恢复社区版本的 `cpp/core/utils/Metrics.h`：

   - 恢复 `array`、`arrayRawPtr`。
   - 恢复 `Metrics::TYPE` 及所有枚举值。
   - 恢复 `Metrics(unsigned int)`。
   - 恢复 `get(TYPE)`。
   - 不向公共 `Metrics` 增加 JSON、variant、`void*`、扩展指针或虚函数。

2. 完整恢复 Core `JniWrapper.cc` 中的社区 metrics 路径：

   - 恢复旧 Java 构造函数签名。
   - 恢复各类 `jlongArray` 的创建和填充。
   - 恢复旧 `nativeFetchMetrics` 返回值。
   - Velox 和 Bolt 均继续调用同一个 Core JNI 入口。

3. 恢复 Velox metrics 实现：

   - 恢复 `cpp/velox/compute/WholeStageResultIterator` 的数组填充逻辑。
   - 恢复 `backends-velox` 的 `Metrics.java`、`OperatorMetrics.java` 和 `MetricsUtil.scala`。

4. Bolt native 通过 `BoltMetrics` helper 填充社区 `Metrics`：

   - `WholeStageResultIterator::collectMetrics()` 先按 `orderedNodeIds_` 和每个节点的 operator 数计算 `statsNum`。
   - 创建 `Metrics(statsNum)` 后，先将 `statsNum * Metrics::kNum` 个元素全部清零，避免 C++ 默认分配留下未初始化数据。
   - 按与 JVM 指标树一致的 operator 顺序填充每个 `Metrics::TYPE` 数组。
   - 对 omitted node 保留一个全零 slot，使指标下标与计划遍历保持一致。
   - 直接字段如 input/output、CPU、wall time、memory 和 spill 指标直接复制。
   - `cpp/bolt/compute/BoltMetrics.h` 集中处理 slot 计数、清零、operator/custom stats 映射和边界条件，避免把 Bolt 逻辑放回 Core。
   - Bolt `customStats` 通过该 helper 按 `sum` 或 `count` 映射到社区固定字段。
   - `loadLazyVectorTime` 写入最后一个实际 metrics slot；`statsNum == 0` 时不得访问数组。
   - `veloxToArrow` 字段沿用社区名称和 JNI 契约，Bolt 不另行扩展公共结构。

5. Bolt JVM 恢复数组消费路径：

   - `backends-bolt` 的 `Metrics.java` 使用社区数组字段和旧构造函数。
   - `MetricsUtil.scala` 直接调用 `getOperatorMetrics(index)`，删除 Jackson、`metricsJson` 和 JSON tree 遍历。
   - `HashAggregateMetricsUpdater`、`JoinMetricsUpdater`、`NestedLoopJoinMetricsUpdater`、`UnionMetricsUpdater` 的 backend 语义有意分开：Velox 恢复社区 `.last.loadLazyVectorTime`；Bolt 保留 `.map(_.loadLazyVectorTime).sum`，以汇总 Bolt 多 operator slot。

6. 固定 custom metrics 映射：

   当前 Bolt JVM 从 JSON 中实际读取的 custom metrics 已能由社区字段表达，包括 dynamic filter、aggregation、scan、IO、preload、write 等指标。映射表应在 Bolt native 侧集中定义，并对缺失项返回 0。

   对于未来新增的 Bolt custom metric，需要先明确其 Spark/JVM 消费字段，再决定是否：

   - 映射到已有社区 `Metrics::TYPE`；或
   - 在独立变更中扩展社区公共协议。

   不为尚未消费的任意 custom metric 保留字符串或 JSON 透传通道。

7. 保留社区已有的 taskStats 诊断字符串：

   - 社区 `Metrics` 和 Java 构造函数已经包含末尾 `taskStats String`。
   - 该字段只在超过长任务阈值时用于事件日志和诊断，不属于 operator metrics 主协议。
   - 本轮允许 `taskStats` 内容继续使用现有 JSON 文本，不改变其格式和消费方。
   - 主 Metrics 数据面不再采用 JSON。

#### 预期收益

- `cpp/core/utils/Metrics.h` 恢复为零 diff。
- Core `nativeFetchMetrics` 和构造函数签名恢复为零 diff。
- Velox 不再为支持 Bolt 改变 metrics 编码。
- Bolt 删除 JSON 序列化、JNI 字符串创建、Jackson 解析和中间对象分配。
- 不增加 Bolt 专用 JNI、DTO、schema version 或双协议兼容逻辑。

#### 实现与验收边界

- 双 backend JVM 编译、style 检查和 `javap` descriptor 对齐已经通过，确认 44-array 构造签名与 Core JNI 一致。
- `taskStats` 的 native 生成、Core JNI 传递、Java 保存和 Scala accumulator 消费链已经静态核对并保留。
- 4 个 updater 的 Velox `.last` / Bolt `.sum` 分流已经通过定向静态检查。
- 最终决策是不保留本轮临时新增的两个 `org.apache.gluten.metrics.MetricsSuite`，也不保留 Bolt/Velox `RuntimeTest.cc` 中新增的 native metrics TEST；这些测试及其测试专用 include 已删除。
- 两棵现有 native cache 均保持 `BUILD_TESTS=OFF`。因此本文档不宣称 native metrics 单测或保留的 Core `JniInputIteratorTest` 已在本轮运行通过，也不把这些 metrics 测试列为交付承诺。

### 4.2 将 Arrow/CMake 兼容逻辑迁出 `cpp/core`

决策目标：采用 A1，恢复 Core 社区代码，在父级和 Bolt CMake 提供 compatibility target，并分别验证 Core、Velox、Bolt 的重编译和最终 SO。实际完成范围和失败边界以第 7 节为准，不把原定门禁写成已经全部通过。

#### 已确认事实

- 普通构建路径已在 `cpp/CMakeLists.txt`、进入 `add_subdirectory(core)` 前调用 `find_arrow_lib`。
- `find_arrow_lib` 内部使用 `if(NOT TARGET ...)`，因此在 Core 中再次调用是幂等的。
- Bolt 构建使用 `find_package(Arrow CONFIG REQUIRED)`，并为小写 target 创建 `Arrow::arrow` alias。
- Bolt 的 Arrow package 不一定提供 `Arrow::arrow_bundled_dependencies`。

#### 具体改动

1. 恢复 Core 中的社区代码：

   - 恢复两个 `find_arrow_lib` 调用。
   - 恢复固定链接 `Arrow::arrow` 和 `Arrow::arrow_bundled_dependencies`。

2. 在 `cpp/bolt.CMakeLists.cmake` 提供社区 Core 所需要的适配：

   - 设置 `ARROW_LIB_NAME` 和 `ARROW_BUNDLED_DEPS`。
   - 提供 Bolt 构建路径下的兼容 `find_arrow_lib`，只验证对应 target 是否存在。
   - 当 Bolt Arrow package 不提供 bundled dependencies target 时，提供空的 imported interface target。
   - 保持真实 Arrow include 和依赖由 `Arrow::arrow` target 传递。

3. 将 `GLUTEN_PREFIX_INCLUDE_DIRS` 注入移到父级 CMake：

   - 在 `add_subdirectory(core)` 后对 `gluten` target 设置 include。
   - 不让 `cpp/core/CMakeLists.txt` 感知父级 prefix 策略。
   - 后续应逐步用具体依赖 target 取代通用 `BEFORE PUBLIC` include 注入，避免头文件和链接库版本不一致。

#### 验证重点

- Velox 和 Bolt 最终使用的 Arrow headers 与库来自同一安装。
- `Arrow::arrow` 已传递 Bolt 所需的静态依赖闭包。
- 对最终 SO 执行 `ldd -r`，确认不存在未解析符号。
- 检查编译命令，确认没有通过偶然的 transitive include 才能编译。

### 4.3 HDFS 安装和非 Linux POST_BUILD 命令

这两组改动不属于 Bolt 核心功能，但恢复社区代码存在实际风险。

#### `libhdfs.so`

基线无条件安装 `cpp/core/resources/libhdfs.so`，但该文件不在基线 Git tree 中。当前 `if(EXISTS ...)` 可避免缺文件时 install/package 失败，但也可能掩盖启用 HDFS 后缺少运行库的问题。

决策：采用 H3，恢复社区无条件安装逻辑。

已验证并接受以下影响：

- Bolt configure、普通 build 和 `package_bolt_native` 不受影响。
- Bolt 使用自己的 `bolt_hdfs`/Arrow HDFS，不直接使用或打包该资源文件。
- 当前源码树中该文件不存在，因此直接运行 `cmake --install` 或 install target 会失败。
- 本轮不把缺文件情况下的 `cmake --install` 成功作为验收条件；如果后续需要恢复 install 能力，应单独讨论按 HDFS feature 控制的安装方案。

#### `ld`/`ldd`

决策：采用 P1，保留 `CMAKE_SYSTEM_NAME MATCHES "Linux"`。项目文档明确支持 macOS 源码开发，Velox 和 Bolt 构建代码也包含实际 Darwin 分支，因此不恢复无条件执行 Linux `ld`/`ldd` 的社区代码。

### 4.4 保留最小的 split SO JNI 边界

以下改动属于 Core 和 backend JNI SO 之间的必要边界，建议保留。

#### 共享 JNI 状态 accessor

保留 `getJniCommonState` 和 `getJniErrorState` 的 out-of-line 定义。

如果恢复 header-inline 函数局部 static，不同 SO 可能分别持有自己的状态，造成：

- Core 初始化的 JavaVM、class 和 method ID 无法被 Bolt SO 看到。
- 初始化和释放不在同一份状态上执行。
- JNI global reference 泄漏或重复释放。

#### `Runtime::createJniInputIterator`

Bolt 当前存在真实 override，用于：

- 识别 `ShuffleReaderInIterator`。
- 创建 `ShuffleReaderWrapperedIterator`。
- 在普通输入上创建 `BoltJniColumnarBatchIterator`。
- 处理并行执行所需的 TaskContext 和 ClassLoader。

因此不能简单恢复 Core 中直接创建 `JniColumnarBatchIterator` 的逻辑。决策采用 R1：保留当前 virtual hook。它比之前的全局 backend factory registry 更小，也没有进程级注册表和卸载生命周期问题。Core 与 backend SO 必须同步构建，以避免 Runtime vtable ABI 不一致。

#### `makeShuffleStreamReader`

Bolt 当前有两个实际调用点，而 Core 的具体 `ShuffleStreamReader` 位于匿名命名空间。保留一个返回 `StreamReader` 接口的 factory，是比公开具体类型或在 Bolt 重复约 40 行 JNI reader 更小的边界。

决策采用 S1：保留当前 Core factory，不在 Bolt 重复 JNI reader 实现。

#### JNI 导出和 gflags 隔离

决策采用 G1，并保留 JNI 导出修复：

- `JNI_OnLoad`/`JNI_OnUnload` 的 `JNIEXPORT` 和 `JNICALL`。
- `symbols.map` 中的 `*gflags::*`。

后者用于避免 Core 和 Bolt 两个 JNI SO 静态链接或加载不同 gflags 实例时发生符号抢占和 flag 注册冲突。

### 4.5 保留 Core input iterator 测试

决策采用 T2：保留 `cpp/core/tests/JniInputIteratorTest.cc` 及其 Core 测试注册。

该测试只验证 C++ virtual dispatch 和 `iteratorIndex` 传递，业务覆盖有限，但依赖少、执行稳定，并能直接保护新增的 Core virtual 边界。本轮不为减少 46 行 Core diff 而迁移测试。

当前 Bolt 和 Velox native cache 均为 `BUILD_TESTS=OFF`，本轮没有为运行该测试而重配 cache。因此这里的“保留”只表示源码和 CMake 注册继续交付，不表示本轮已经执行该测试。

Bolt 的普通 iterator、shuffle wrapper、parallel 开关和 fallback 行为仍适合作为后续增强测试，但不作为本轮删除 Core 测试的前置工作。

### 4.6 删除未使用的 ShuffleReaderOptions 扩展

以下内容在当前仓库中没有任何读写点：

- `enableGpuAsyncReader`
- `gpuAsyncReaderMaxPrefetchBytes`
- `Options.h` 中新增的 `<thread>`

决策采用 O1：全部恢复到基线。如果后续 Bolt GPU reader 确实需要这些配置，应在 Bolt 目录定义 `BoltShuffleReaderOptions` 或 `GpuAsyncReaderOptions`，不要改变公共 `ShuffleReaderOptions` 的布局。

### 4.7 消除纯 diff 噪声

恢复 `JniWrapper.cc` 的社区 include 顺序。当前 include 集合与基线基本相同，主要差异是分组和顺序变化，约产生 21 行无功能意义的 patch。

`metricsBuilderClass` 在 `JNI_OnUnload` 中释放属于正确但独立的小修复。决策采用 C1：保留该一行，使 JNI global reference 的初始化和释放对称，并考虑后续单独上游化。

### 4.8 删除 Velox stale `ConfigResolver` include

最终删除以下两个已经没有标识符使用点的 include：

- `cpp/velox/utils/ConfigExtractor.h` 中的 `#include "utils/ConfigResolver.h"`。
- `cpp/velox/jni/VeloxJniWrapper.cc` 中的 `#include "utils/ConfigResolver.h"`。

全 `cpp/velox` 定向搜索已无 `ConfigResolver` 引用。后续 Velox 增量构建中，包含 `ConfigExtractor.h` 的相关编译以及 `VeloxJniWrapper.cc.o` 均已成功，证明删除没有造成直接编译依赖缺失。

## 5. 文件级预期动作

| 实施起点差异文件 | 最终动作 | 当前是否仍有 diff |
|---|---|---|
| `cpp/core/CMakeLists.txt` | Arrow/prefix 迁出；恢复 HDFS；保留 Linux guard | 是 |
| `cpp/core/compute/Runtime.h` | 保留最小 input iterator virtual hook | 是 |
| `cpp/core/jni/JniCommon.cc` | 保留共享 state 定义和默认 iterator 实现 | 是 |
| `cpp/core/jni/JniCommon.h` | 保留 state 声明、context 和 stream reader factory | 是 |
| `cpp/core/jni/JniError.cc` | 保留共享 state 定义 | 是 |
| `cpp/core/jni/JniError.h` | 保留 out-of-line accessor 声明 | 是 |
| `cpp/core/jni/JniWrapper.cc` | 恢复 metrics/include；保留 bridge 和导出修复 | 是 |
| `cpp/core/shuffle/Options.h` | 删除未使用字段和 include | 否 |
| `cpp/core/symbols.map` | 保留 gflags 隔离 | 是 |
| `cpp/core/tests/CMakeLists.txt` | 保留 Core iterator 测试注册 | 是 |
| `cpp/core/tests/JniInputIteratorTest.cc` | 保留当前 Core virtual dispatch 测试 | 是 |
| `cpp/core/utils/Metrics.h` | 恢复社区实现 | 否 |

## 6. 实际实施顺序与边界

### 阶段 0：冻结基线

1. 固定 Community Main 基线、`04d82611d` 实施起点、原始 patch 和 12 文件/321 行统计。
2. 明确采用 selective rollback，不整体撤销 `04d82611d`，避免误删 split SO 必需边界。

### 阶段 1：无争议清理

1. 恢复 `JniWrapper.cc` 社区 include 顺序。
2. 删除 `Options.h` 未使用字段和 `<thread>`。
3. 保留 `metricsBuilderClass` cleanup、Linux POST_BUILD guard 等有独立理由的最小改动。

### 阶段 2：恢复 44-array Metrics

1. 恢复 Core 和 Velox 的社区数组协议及 JVM 构造签名。
2. 在 Bolt backend 内保留 `BoltMetrics.h` helper，集中填充社区 `Metrics::TYPE` 数组并处理零初始化、omitted slot 和 custom stats。
3. Bolt JVM 恢复数组版 `Metrics`/`MetricsUtil`，移除主 metrics 数据面的 JSON/Jackson；`taskStats` 诊断链保留。
4. 恢复 Velox 4 个 updater 的 `.last`，保留 Bolt 对应 4 个 updater 的 `.sum`。
5. 删除本轮临时新增的 JVM `MetricsSuite` 和 native metrics TEST，不把它们作为最终交付物。

### 阶段 3：CMake 适配迁出 Core

1. 恢复 Core Arrow 查找、固定 target 链接和 HDFS 安装逻辑。
2. 在父级和 Bolt CMake 提供 Arrow/prefix 适配。
3. 用 Bolt build/package 和最终 SO 的 `ldd -r` 验证适配；Velox 侧只记录实际完成的 Core/对象编译结果，不把失败的全 backend 构建写成通过。

### 阶段 4：保留 split SO 边界和 Core 测试源码

1. 保留 shared JNI state accessor、Runtime iterator virtual hook、stream reader factory、JNI 导出和 gflags 隔离。
2. 保留 `JniInputIteratorTest.cc` 及 CMake 注册。
3. native cache 保持 `BUILD_TESTS=OFF`，不为本轮临时测试重配，也不宣称 Core/native metrics 测试已执行。

### 阶段 5：最终清理与验证

1. 删除 Velox 两处 stale `ConfigResolver` include。
2. 完成双 backend JVM 编译、style、descriptor 和定向静态检查。
3. 完成 Bolt native build/package/`ldd -r`；尝试 Velox 全量构建并如实记录外部 API 不匹配失败。
4. 复算 `cpp/core` residual 为 10 文件/123 行，并保持工作区未提交。

## 7. 实际验证结果

### 7.1 已通过

| 范围 | 实际结果 |
|---|---|
| Core residual | 相对 `9baa47c4` 为 10 个文件、102 行新增、21 行删除，共 123 行增删 |
| updater 分流 | 定向检查确认 Velox 4 个 updater 均为 `.last.loadLazyVectorTime`，Bolt 对应 4 个 updater 均为 `.map(...).sum` |
| 双 backend JVM 编译 | Bolt 和 Velox 分别通过 `./build/mvn ... -DskipTests test-compile`；Maven 均通过项目 wrapper 调用 |
| JVM style | Bolt 和 Velox 分别通过 `spotless:check checkstyle:check` |
| JNI/JVM descriptor | 两个 backend 编译出的 `Metrics` 构造 descriptor 均与 Core JNI 一致：44 个 `long[]`、一个 `long` 和末尾 `String`；`OperatorMetrics` 也均为 44 个 `long` 且无临时 no-arg 构造函数 |
| Metrics 静态链 | 主协议路径无 `metricsJson`/Jackson 残留；Bolt/Velox 的 `taskStats` native 生成、Core JNI 传递、Java 保存和 Scala accumulator 消费链均存在 |
| C++ style/静态检查 | 本轮新增 `BoltMetrics.h` 和相关改动范围通过 clang-format 15 定向检查；新增文件 license header 已核对；`git diff --check` 通过 |
| Bolt native | `bolt_backend` 成功，`package_bolt_native` 成功并完成 staging |
| Bolt 最终 SO | package 中的 `libgluten.so` 和 `libbolt_backend.so` 均通过 `ldd -r`，未发现未解析符号 |
| Velox Core | 本轮重新链接的 `cpp/build/releases/libgluten.so` 通过 `ldd -r`；`nm -D --undefined-only` 中没有 `gluten::` 未定义符号 |
| Velox 关键对象 | `WholeStageResultIterator.cc.o` 和 `VeloxJniWrapper.cc.o` 成功生成且时间新于源码；两处 stale include 删除没有造成直接编译失败 |

### 7.2 未通过或不在本轮验收范围

- Velox 默认全量 native 构建未通过，不能宣称 Velox backend 验证成功。构建在 82 个步骤的第 26 步后出现错误：外部 Velox `HashTable` 缺少 `serializedSize`、`serializeTo`、`deserializeFrom`，同时当前 Parquet writer 只提供 `parquet::WriterOptions`，与 Gluten 使用的 `ParquetWriterOptions`/`formatSpecificOptions` 调用面不一致。
- 失败 cache 指向 `/home/wangxinshuo.db/velox`，其 HEAD 为 `65a1806b91128bd7219748b973a1d8f3f0587f89`，处于 detached、无 remote 状态；项目脚本默认分支为 `dft-2026_07_03`。现有证据指向外部 Velox 源码/构建缺少当前 Gluten 所需版本或补丁，而不是本轮 stale include/metrics 修改。
- 因 backend 未成功重链，没有对目录中旧的 `libvelox.so` 做最终产物声明或把它的检查结果计入验收。
- Bolt 和 Velox native cache 均为 `BUILD_TESTS=OFF`。本轮新增的 JVM `MetricsSuite` 和 native metrics TEST 已删除，保留的 Core `JniInputIteratorTest` 也未执行；不得把旧 `target/` 中残留的 class/report 当作最终测试交付或本轮通过证据。
- 源码树缺少 `cpp/core/resources/libhdfs.so` 时，标准 `cmake --install` 仍会失败；该影响已按 H3 接受，不属于本轮通过项。
- 本轮没有执行 macOS/Darwin 构建，只保留了避免在非 Linux 平台调用 `ld`/`ldd` 的源码保护。

## 8. 风险与控制

| 风险 | 控制措施 |
|---|---|
| Bolt 数组中存在未初始化的指标值 | `BoltMetrics` helper 分配后先清零 `statsNum * Metrics::kNum` 个元素，再覆盖有效字段；本轮未保留专用 metrics 单测，仍需依靠后续集成验证发现行为偏差 |
| Bolt operator、omitted node 与 JVM 指标树下标错位 | helper 固定遍历顺序并为 omitted node 保留全零 slot；因最终决定删除临时 metrics tests，该项仍有未被专用单测覆盖的剩余风险 |
| Arrow 空 bundled target 掩盖缺少静态依赖 | 检查 `Arrow::arrow` 传递依赖，并运行 `ldd -r` |
| 保留的 Core iterator 测试只覆盖语言级 virtual 分发，且本轮未执行 | 源码和注册继续保留；明确记录 `BUILD_TESTS=OFF`，不把“存在测试”误写成“测试通过” |
| 恢复 inline JNI state 导致每个 SO 各自初始化 | 明确保留 out-of-line accessor，不纳入减量候选 |
| 删除 gflags 隐藏规则后发生 flag 冲突 | 默认保留，并用最终动态符号表验证 |
| 恢复社区无条件安装后，资源缺失会导致 `cmake --install` 失败 | 已接受该已知风险；当前门禁只认已通过的 Bolt 常规构建、自定义打包和 SO 检查，Velox 全量验证需在匹配外部依赖后补全；后续如需标准 install 再单独设计 feature/resource 一致性校验 |
| Velox 外部源码/API 与 Gluten 不匹配 | 不修改业务源码掩盖依赖问题；记录 cache、commit 和首个错误，在准备匹配的 Velox 版本或补丁后重新做全量 backend 验证 |
| 旧 `target/` 或旧 `libvelox.so` 被误当成当前结果 | 只认时间新于源码的对象和本轮重新链接产物；不把 stale test class/report 或未重链 backend SO 计入通过项 |

## 9. 已确认的决策记录

本轮最终决策已经全部确认并落实到当前工作树：

| 编号 | 已选方案 | 结果 |
|---|---|---|
| RB1 | 在 `04d82611d` 上 selective rollback | 只恢复可收敛部分，不整体撤销 split SO 必需改动 |
| M1 | 社区 44-array Metrics | Velox/Bolt 主 Metrics 数据面不使用 JSON；保留 taskStats 诊断 JSON |
| B1 | 保留 Bolt native `BoltMetrics` helper | backend 内集中做数组映射和边界处理，不形成新公共协议 |
| U1 | updater 语义按 backend 分流 | Velox 4 个 updater 恢复 `.last`；Bolt 对应 4 个保留 `.sum` |
| H3 | 恢复社区无条件安装 `libhdfs.so` | 接受文件缺失时 `cmake --install` 失败；Bolt 普通 build/custom package 不受影响 |
| P1 | 保留 Linux guard | 继续支持项目文档和源码中的 macOS/Darwin 构建路径 |
| A1 | Arrow 适配迁到父级/Bolt CMake | Core 恢复固定 target；同步重编译后以 `ldd -r` 验收 |
| I1 | prefix include 注入移到父级 | 保持现有 include 行为，同时恢复 Core CMake |
| R1 | 保留 Runtime virtual hook | Core/backend 必须同步构建，避免 vtable ABI 不一致 |
| S1 | 保留 Core stream reader factory | 不在 Bolt 重复 JNI 生命周期实现 |
| T2 | 保留 Core iterator 测试 | 接受保留 46 行 Core 测试 diff |
| T3 | 不保留临时 metrics tests | 删除两个 JVM `MetricsSuite` 和两份 `RuntimeTest.cc` 中新增的 native metrics TEST；cache 保持 `BUILD_TESTS=OFF` |
| O1 | 删除未使用 GPU shuffle 配置 | `Options.h` 恢复社区版本 |
| C1 | 保留 `metricsBuilderClass` cleanup | 修复 JNI global reference 释放不对称 |
| G1 | 保留 gflags 符号隔离 | 防止 split SO 符号抢占和重复 flag 注册 |
| V1 | 删除 Velox stale include | 删除 `ConfigExtractor.h`、`VeloxJniWrapper.cc` 中无使用点的 `ConfigResolver.h` include |

最终组合：`RB1 + M1 + B1 + U1 + H3 + P1 + A1 + I1 + R1 + S1 + T2 + T3 + O1 + C1 + G1 + V1`。

## 10. 最终状态与未完成项

1. diff 收敛目标已达到：`cpp/core` 从 12 个文件、321 行增删下降到 10 个文件、123 行增删。
2. `cpp/core/utils/Metrics.h` 和 `cpp/core/shuffle/Options.h` 已恢复到基线；Core iterator 测试源码和注册按 T2 保留。
3. 社区 44-array 协议、BoltMetrics helper、taskStats 链、双 backend updater 分流、JVM descriptor 和 style 检查均已落地并完成第 7 节列出的验证。
4. Bolt native build、custom package 和最终 Core/backend SO 的 `ldd -r` 已通过。
5. Velox Core 和本轮关键对象已成功重建，但 Velox 全 backend 因外部 Velox API/补丁不匹配失败；在使用匹配依赖完成重建前，不得将本轮状态描述为“Velox native 通过”或“全部验证完成”。
6. 本轮不保留新增 metrics tests，native cache 维持 `BUILD_TESTS=OFF`；因此不存在 native metrics/Core iterator 测试通过的声明。
7. `cmake --install` 的缺失 `libhdfs.so` 风险和未执行 Darwin 构建的验证空缺均已明确记录，不以文档措辞掩盖。
8. 实施结果已提交为 `ec32f2a24500a402781c2b63a5a736796acd44b5`。用户随后要求整理从 `97c02a3c3d1291ef5f24df52818953da341b6932` 开始的提交历史；具体方案见第 11 节，当前尚未执行历史重写。

## 11. 提交历史整理方案（尚未执行）

### 11.1 目标与边界

本节用于整理 `97c02a3c3d1291ef5f24df52818953da341b6932`（含）到当前实施提交之间的历史，使每个可见提交具有相对独立的职责，并消除已经被后续提交撤销的中间设计。

审计锚点如下：

- rewrite base：`fa4f937732298b6a6539afeeac1ed4f760627599`，即 `97c02a3^`。
- rewrite 前 tip：`ec32f2a24500a402781c2b63a5a736796acd44b5`。
- rewrite 前最终 tree：`30e3a827fb168a237b8befa8c69b89cfbcdc640b`。
- 原范围共有 9 个线性 commit，最终净变化涉及 49 个真实路径。
- Git rename detection 会把删除的 `cpp/core/jni/JniWrapper.h` 和新增的 `cpp/core/tests/JniInputIteratorTest.cc` 显示为一次 `R055`；重写时必须按一个 delete 和一个 add 处理，不能按真实 rename 暂存。
- 当前主工作区仅允许保留未跟踪的 `codex_wants_execute.sh` 和 `ep/bolt/`；二者不进入新提交、不被移动或清理。
- 本方案只重写本地历史，不包含 push。远端强制更新必须再次获得用户确认。

旧 tip tree `30e3a827fb168a237b8befa8c69b89cfbcdc640b` 只作为实施起点的审计锚点。由于本节文档需要进入最终 docs commit，执行重写前必须通过临时 index 计算 `expected_target_tree`：其内容应等于旧 tip 的全部 tracked 内容，加上当前已审核的 `CPP_CORE_DIFF_REDUCTION_PLAN.md` 修改；snapshot patch 应重新生成并保持字节不变。`expected_target_tree` 不写回本文档，避免文档包含自身 tree hash 造成自引用变化。

历史重写的硬性完成条件是：新 tip tree 严格等于执行前计算并记录的 `expected_target_tree`；相对旧 tip，唯一允许的 tracked 差异是本设计文档。除该文档外，源码和 snapshot patch 必须与旧 tip 完全一致。

### 11.2 需要消除的中间态

原 9 个 commit 中有两类明显的先引入、后删除或回滚：

1. `97c02a3` 引入 `BackendJniRegistry`、backend iterator factory 和对应测试，`c471f777` 随后删除该 registry，改为最终的 `Runtime::createJniInputIterator` virtual 分发。
2. `04d82611` 将共享 Metrics 主协议切换为 JSON，`ec32f2a2` 又恢复社区 44-array 协议。相对 rewrite base，Core/JVM/Velox 的这次协议往返净 diff 为 0。

新历史不得出现上述中间态：

- 不提交 `BackendJniRegistry.{h,cc}`、registry test 或其临时 CMake 注册。
- 不提交 Core/JVM/Velox 的 JSON Metrics 协议，也不提交随后用于撤销它的反向变更。
- 最终 Metrics 净变化只保留 Bolt native helper、Bolt producer 调用和四个 Bolt updater 的 `.sum` 语义。

### 11.3 依赖顺序

推荐的依赖顺序为：

```text
Runtime iterator virtual 分发
  -> 共享 JNI state accessor
  -> JVM-loaded library promotion
  -> Core/Bolt split SO + JNI lifecycle + gflags 隔离
  -> MemoryManager API 清理
  -> Bolt array Metrics 补全
  -> community/native build 差异收敛
  -> 文档与最终 patch snapshot
```

其中前三项都必须先于 split SO 完成：

- split 前必须已经有最终 Runtime virtual 分发，否则删除临时 registry 后，Core SO 会暂时保留无法满足的 backend iterator 路由。
- split 前必须有 out-of-line 的共享 JNI state，否则 Core 与 backend SO 可能各自持有一份 header-inline state。
- split 前必须具备 JVM-first load 后的 global promotion 能力，否则 Core、Bolt 的加载顺序和动态符号可见性没有可靠入口。
- gflags 隔离与 split SO 放在同一 commit，避免产生“已经拆分但仍暴露重复依赖符号”的不安全提交。
- Core 固定 Arrow target 与 Bolt 父级 compatibility target 必须在同一 commit，否则中间 Bolt configure 会失败。

### 11.4 计划中的 8 个 commit

#### Commit 1：Runtime iterator virtual 分发

Subject：

```text
[CORE][BOLT] Dispatch JNI input iterators through Runtime
```

范围：

- `cpp/core/compute/Runtime.h`：增加最终 virtual hook。
- `cpp/core/jni/JniCommon.{h,cc}`：增加 iterator context 和 Core 默认实现。
- `cpp/core/jni/JniWrapper.cc`：通过多态 Runtime 调用 `createJniInputIterator`。
- `cpp/bolt/compute/BoltRuntime.h`、`cpp/bolt/jni/BoltInputIterator.cc`：Bolt override 和实现。
- Core/Bolt/Velox wrapper 中删除旧 backend iterator factory 的相关 hunk。
- `cpp/core/tests/CMakeLists.txt` 和 `cpp/core/tests/JniInputIteratorTest.cc`：保留最终 Core virtual 分发回归测试。
- `cpp/core/jni/JniWrapper.h` 在本 commit 只删除 backend factory 声明；pre-split 的 Base OnLoad/Unload 声明继续保留到 Commit 4。

明确排除：`BackendJniRegistry` 生产代码、测试和 CMake 注册。

验收：Core、Bolt 对 Runtime API 的声明/override 一致；`git show --check` 通过；条件允许时构建并运行 `jni_input_iterator_test`。

#### Commit 2：共享 JNI state accessor

Subject：

```text
[CORE] Export shared JNI state accessors
```

范围：

- `cpp/core/jni/JniCommon.{h,cc}`：将 common state 从 header-inline/static 迁为 Core 中的 out-of-line 单一实例和 accessor。
- `cpp/core/jni/JniError.{h,cc}`：以相同方式集中 error state。

本 commit 不拆 SO、不改变 JVM loader，也不改变 backend OnLoad 所有权；它只建立 split 所需的单一状态基础。

验收：Core 编译通过；动态/静态符号检查确认 accessor 只有一份定义。

#### Commit 3：JVM-loaded library promotion

Subject：

```text
[BOLT] Promote JVM-loaded native libraries to global visibility
```

范围：

- Bolt JVM `BoltJniLibLoader.java`。
- `cpp/bolt/nativeLoader/JniNativeLibraryLoader.cc` 和对应 CMake arm64 识别。
- `BoltNativeLoaderSuite.scala`。
- `backends-bolt/pom.xml` 中仅与 `bolt.native.loader.path` 测试属性有关的 hunk。

该 commit 使用 `System.load` 先触发正常 `JNI_OnLoad`，再用 `RTLD_NOLOAD | RTLD_GLOBAL` promotion；不要提前修改 `BoltListenerApi` 的 split-SO 加载顺序。

验收：loader native/JVM 编译通过；loader suite 必须实际执行而不是 cancel。

#### Commit 4：拆分 Core/Bolt JNI SO 并隔离依赖符号

Subject：

```text
[CORE][BOLT] Split Core and Bolt into independent JNI libraries
```

范围：

- Core/Bolt CMake 中的独立 shared library target、Bolt relative RPATH、package/staging 和 Darwin version-script 处理；不带入最终净 diff 为 0 的 Core RPATH 往返。
- `backends-bolt/pom.xml` 中 package 目录和 native resource hunk。
- `BoltListenerApi.scala` 中 shared dependencies、Core、Bolt 的加载顺序。
- Core/Bolt/Velox wrapper 的独立 `JNI_OnLoad`/`JNI_OnUnload`、state `ensureInitialized`/`assertInitialized` 和 ownership。
- 删除 split 后不再需要的 `cpp/core/jni/JniWrapper.h` 及 include。
- `cpp/core/symbols.map` 的 gflags 动态导出隐藏规则。
- `metricsBuilderClass` global reference 的对称释放。

原 `d4056b93` 的 gflags 隔离不再单独形成一个可见提交，而是合入 split commit，确保该 commit 本身就是可安全加载的状态。

验收：Core/Bolt 两个 SO 均构建并打包；加载顺序为 shared dependencies -> Core -> Bolt；`ldd -r` 无未解析符号；`nm/readelf` 确认各自 JNI entry、Bolt 对 Core 的依赖和 Core 不导出 gflags 定义。

#### Commit 5：MemoryManager name plumbing 清理

Subject：

```text
[CORE] Remove unused memory manager name plumbing
```

范围：

- Core `MemoryManager.{h,cc}` 和 JNI memory-manager factory。
- Bolt backend/runtime/memory manager、benchmark 和 runtime test 的机械参数删除。
- Velox backend/memory manager 的对应机械参数删除。
- Bolt WholeStage 中仅 memory manager holder 参数相关的 hunk。
- 与该 API 删除直接相关的无用 include。

该 commit 不包含 Metrics producer、SO lifecycle、Arrow/CMake 或 stale include 清理。

验收：Core、Bolt、Velox 对 MemoryManager 构造 API 的声明和调用点全部一致；全仓不存在被删除 name 参数的引用。

#### Commit 6：Bolt array Metrics 补全

Subject：

```text
[BOLT] Populate operator metrics through the array protocol
```

范围：

- 新增 `cpp/bolt/compute/BoltMetrics.h`。
- Bolt `WholeStageResultIterator.cc` 中 helper include、array producer 调用和保留 taskStats 诊断 JSON 的 hunk。
- Bolt `WholeStageResultIterator.h` 删除旧 member `runtimeMetric`。
- 四个 Bolt metrics updater 保留 `.map(_.loadLazyVectorTime).sum`。

该 commit 不包含 Core/JVM/Velox Metrics 文件，因为相对 rewrite base 它们的最终净 diff 为 0。禁止误带 `04d82611` 的 JSON 协议中间态。

验收：helper 映射 44/44 字段、全量清零、omitted slot 为零、`statsNum == 0` 安全、loadLazy 写入 `statsNum - 1`；JNI/JVM 仍使用 rewrite base 已有的 44-array 协议；taskStats 链保持不变。

#### Commit 7：收敛 community/native build 差异

Subject：

```text
[CORE][BOLT] Reduce native build divergence from community
```

范围：

- `cpp/CMakeLists.txt`、`cpp/bolt.CMakeLists.cmake`、`cpp/bolt/CMakeLists.txt`、`cpp/core/CMakeLists.txt` 的剩余 hunk。
- Core 固定 Arrow target 与 Bolt `Arrow::arrow_bundled_dependencies` compatibility target、父级 prefix include 注入必须原子落地。
- HDFS 无条件 install、Linux `ld`/`ldd` guard 及相关 Core CMake 收敛。
- Core Runtime/JNI/Likely、Velox Runtime 的剩余 include/社区对齐 hunk。
- 删除 Velox `ConfigExtractor.h`、`VeloxJniWrapper.cc` 中未使用且目标不存在的 `ConfigResolver.h` include。

对于原历史中最终净 diff 为 0 的 GPU shuffle Options 或纯格式往返，不创建新 hunk；只按 base 到最终 tree 的净效果提交。

验收：Bolt configure/build/package 通过；Core 与 Bolt Arrow target 闭包正确；Velox 本轮相关对象可编译；`git diff --check` 和定向 clang-format/license 检查通过。现有外部 Velox API mismatch 仍按第 7 节记录，不在历史整理中越界修复。

#### Commit 8：设计文档与最终 snapshot

Subject：

```text
[DOC] Record the Core diff-reduction design and snapshot
```

范围：

- `CPP_CORE_DIFF_REDUCTION_PLAN.md`。
- `CPP_CORE_DIFF_VS_COMMUNITY_MAIN.patch`。

该 commit 必须最后创建。前 7 个代码 commit 完成后重新生成 patch，并确认它与当时 `git diff 9baa47c4240bd761878c9f99dc2fd96d9749b83b -- cpp/core` 逐字节一致。

验收：文档中的 commit hash、tree hash、统计和实际验证结果一致；patch 保持 10 文件、102 行新增、21 行删除，共 123 行增删。

### 11.5 共享文件的 hunk 暂存规则

以下文件跨多个主题，不能整文件提前暂存：

- `cpp/core/jni/JniCommon.{h,cc}`：Commit 1 的 iterator context/default implementation、Commit 2 的 singleton accessor和 Commit 7 的 community-alignment hunk必须分开；Commit 4 的 lifecycle ownership 位于各 backend wrapper，不在这两个文件中制造额外分段。
- `cpp/core/jni/JniWrapper.cc`：Commit 1 的 Runtime dispatch、Commit 4 的 independent OnLoad/Unload 和 cleanup、Commit 5 的 memory factory、Commit 7 的社区对齐 hunk必须分开。
- `cpp/core/jni/JniWrapper.h`：Commit 1 只移除 backend factory；Commit 4 再删除剩余文件。
- `cpp/core/CMakeLists.txt`、`cpp/bolt.CMakeLists.cmake`、`cpp/bolt/CMakeLists.txt`：Commit 4 的 SO/package hunk与 Commit 7 的 Arrow/HDFS/community hunk必须分开。
- `cpp/bolt/compute/WholeStageResultIterator.cc`：Commit 5 的 memory 参数与 Commit 6 的 Metrics producer 必须分开。
- `cpp/velox/jni/VeloxJniWrapper.cc`：Commit 1 的 iterator factory、Commit 4 的 lifecycle、Commit 7 的 stale include 必须分开。

每个暂存动作都必须使用显式路径或可审计 patch；禁止 `git add -A`。每个 commit 前输出 staged name-status、stat 和 diff，并确认未带入下一主题的 hunk。

### 11.6 安全重写流程

1. 记录当前 branch、`old_tip`、`base`、旧 tip tree、真实 index 和工作区状态。真实 index 必须为空；tracked 工作区相对 `old_tip` 只允许本设计文档这一处修改。若存在其他 tracked/index 变更，立即停止；不自动 stash、reset 或 clean。
2. 使用独立临时 index，以 `old_tip` tree 为基础加入当前设计文档，计算并在执行日志中记录 `expected_target_tree`。验证该临时 tree 相对 `old_tip` 只修改 `CPP_CORE_DIFF_REDUCTION_PLAN.md`；不要把该 hash 写回文档。
3. 通过带旧值校验的 `git update-ref` 创建备份 ref，例如 `refs/backup/seperate_so_minimal/pre-rewrite-<timestamp>`，指向 `old_tip`。
4. 在仓库外创建临时目录，并直接以 `base` 建立 detached worktree和临时 rewrite 分支。用可审计的 `git diff --binary base old_tip` patch把旧 tip tracked 内容物化为未暂存变更，再应用主工作区中 `old_tip -> 当前设计文档` 的单文件 patch。这样提交起点明确是 `base`，目标工作内容明确是 `expected_target_tree`，不依赖 checkout/reset 保留工作内容的隐式行为。
5. 在临时 worktree 中按第 11.4 节逐 hunk暂存并创建 8 个 commit。主工作区不执行 rebase、reset、stash 或 clean。
6. 不使用 `--no-verify`。任一 hook、格式、license、编译或测试失败都停止重写，不移动原分支。
7. 每个 commit 至少执行 `git diff --cached --check`（嵌套 patch artifact 按统一 diff 语法单独审计）、`git show --check` 和主题路径检查；关键生产 commit执行第 11.4 节列出的定向构建/测试。
8. Commit 8 前重新生成 snapshot patch并验证它与旧 tip 中的 patch 字节一致；创建后临时 worktree必须干净。
9. 验证新 tip tree严格等于执行日志中的 `expected_target_tree`。`git diff old_tip new_tip --` 必须只显示 `CPP_CORE_DIFF_REDUCTION_PLAN.md`；对排除该文档后的所有路径执行 `git diff --exit-code` 必须无输出。同时确认 `base..new_tip` 恰好 8 个 commit，并执行 `git fsck --connectivity-only`。
10. CAS 移动分支前，在主工作区只暂存 `CPP_CORE_DIFF_REDUCTION_PLAN.md`，并确认真实 index 的 `git write-tree` 等于 `expected_target_tree`。随后才使用带 expected old value 的 `git update-ref` 原子移动当前本地分支。这样 ref、index 和已经在工作区中的新文档内容同时对齐；不得假设不同 tree 的 ref 移动会自动同步 index。若 CAS 因 expected old value 不匹配而失败，立即用只更新 index、不更新 worktree 的 `git read-tree old_tip^{tree}` 恢复原 index，并确认设计文档重新表现为未暂存修改。
11. 移动后再次核对主工作区：tracked/index 必须 clean，预期只剩 `codex_wants_execute.sh` 和 `ep/bolt/` 未跟踪。任一不一致都从 backup ref按第 11.8 节恢复。
12. 本轮不 push。后续如需更新远端，只能在用户再次确认后使用精确 `--force-with-lease=<branch>:<old_tip>`，不得使用裸 `--force`。

### 11.7 最终验证矩阵

| 闸门 | 要求 |
|---|---|
| 提交数量 | `base..new_tip` 恰好 8 个 commit |
| 最终 tree | 新 tip tree 严格等于执行前通过临时 index计算并记录的 `expected_target_tree` |
| 新旧内容 | `old_tip..new_tip` 只允许设计文档变化；排除该文档后的 `git diff --exit-code` 无输出 |
| 每提交独立性 | subject、staged path/hunk 与第 11.4 节一致，无 registry/JSON 中间态 |
| Runtime/JNI | Runtime override、共享 state、Core/Bolt OnLoad ownership 和符号导出一致 |
| loader/split SO | loader suite 实际执行；Core/Bolt build/package、加载顺序、`ldd -r` 和 `nm/readelf` 通过 |
| Metrics | 最终只有 Bolt array helper/updater 净变化；主协议保持 44-array，taskStats 保留 |
| JVM/style/license | Maven `test-compile`、Spotless、Checkstyle、Scalastyle 和本轮 license 检查通过 |
| Core snapshot | patch 与最终 `cpp/core` diff 逐字节一致，统计仍为 10 文件/123 行 |
| 工作区 | tracked/index clean；只保留预期未跟踪路径 |

Velox 全 backend 仍可能因 `/home/wangxinshuo.db/velox` 的既有 API/补丁不匹配失败；该已知外部依赖问题不通过重排提交掩盖，也不能被描述成历史重写引入的回归。

### 11.8 回滚与失败处理

- 任一重建或验证失败：保留原分支不动，删除临时 worktree前保存必要日志，修正方案后重新开始。
- 原分支已移动但后续发现问题：使用 backup ref 和当前 ref 的 expected value执行 CAS 恢复；随后用只更新 index、不更新 worktree 的 `git read-tree backup_ref^{tree}` 恢复原 index，使 HEAD/index 回到旧 tip、工作区继续保留设计文档修改；不得使用 `git reset --hard`。
- 不执行 `git clean`，不删除用户未跟踪文件，不自动修改远端。
- backup ref 至少保留到用户检查新历史并决定是否 push 之后。

### 11.9 当前状态

截至本节写入时：

- 历史仍停留在 `ec32f2a24500a402781c2b63a5a736796acd44b5`。
- 尚未创建 backup ref、临时 worktree或任何新 commit。
- 尚未执行 rebase、reset、update-ref 或 push。
- 当前仅修改本设计文档，等待用户审核并确认后再实施。
