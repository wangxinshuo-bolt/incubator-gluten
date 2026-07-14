<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Bolt JNI 与原生库拆分设计

## 1. 文档状态

- 状态：推荐方案已确认，代码已按方案实施；完整 Bolt 发布产物验证仍受基线构建问题阻塞。
- 最后更新：2026-07-13
- 目标：将 Bolt backend 从“Core 静态嵌入 backend so”的模式迁移为两个独立业务 so，并恢复标准 JNI 生命周期。
- 预期业务产物：`libgluten.so`、`libbolt_backend.so`。
- 辅助产物：保留 `libglutenlibloader.so`，因此物理上总计三个 so。
- 本文同时记录设计决策、当前实现状态、已完成验证和剩余发布阻塞项。

## 2. 背景

### 2.1 当前构建方式

Bolt 构建入口当前执行：

```cmake
set(GLUTEN_CORE_LIBRARY_TYPE STATIC)
set(GLUTEN_CORE_RENAME_JNI_ENTRYPOINTS ON)
add_subdirectory(core)
```

`cpp/core` 因此生成静态 target `gluten`，随后被链接进
`libbolt_backend.so`。Core 与 Bolt 最终位于同一个 DSO 中。

为了避免单个 DSO 中同时存在两个 `JNI_OnLoad/JNI_OnUnload`，Core 的入口通过编译宏被重命名：

```text
JNI_OnLoad   -> JNI_OnLoad_Base
JNI_OnUnload -> JNI_OnUnload_Base
```

Bolt 的标准 `JNI_OnLoad` 再显式调用 `JNI_OnLoad_Base`，从而把 Core 和
Bolt 的生命周期串在一起。

`JNI_OnLoad_Base` 由提交
`df6872787fae45d9f984c356c4e81feaea96ec9a` 为“Core 静态嵌入 backend”场景引入。它不是 Core 应长期对外提供的 JNI 接口。

### 2.2 当前模式的问题

1. Core 不能作为独立 JNI 库加载、复用和诊断。
2. Core 与 backend 生命周期被手工串联，职责边界不清晰。
3. `JNI_OnLoad_Base` 是构建细节泄漏到源码接口中的内部 ABI。
4. Core 与 Bolt 的静态依赖、全局状态和 registry 全部折叠在一个 DSO 中，无法验证真正的跨 DSO 边界。
5. 与 Velox backend 的构建、加载和打包模式不一致。
6. 后续若引入其他 backend，容易继续复制入口重命名方案。

### 2.3 libloader 的原始需求

历史提交 `6fad947027b02af1cafaa6555cc0e97209a0099c` 中的
`BoltJniLibLoader` 说明了 loader 的原始目的：

- JVM 的 `System.load` 无法由调用者指定 `RTLD_GLOBAL`。
- Bolt 的 LLVM JIT 和原生 UDF 需要通过当前进程符号表解析 Bolt/Core 的 C++ 符号。
- 历史实现先通过 `dlopen(RTLD_GLOBAL | RTLD_LAZY)` 加载单体
  `libbolt_backend.so`，随后再对同一路径调用 `System.load`，让 JVM 登记该库并调用标准 `JNI_OnLoad`。

拆分前 Core 已静态包含在 Bolt DSO 中，因此只需把一个 backend so 放入
global scope。拆分后，为保持相同能力，Core 和 Bolt 两个业务 so 都可能需要进入 global scope。

## 3. 设计目标

### 3.1 必须满足

1. `cpp/core` 生成独立 `libgluten.so`。
2. `cpp/bolt` 生成独立 `libbolt_backend.so`。
3. `libbolt_backend.so` 通过 `DT_NEEDED` 单向依赖 `libgluten.so`。
4. 两个业务 so 分别导出标准 `JNI_OnLoad/JNI_OnUnload`。
5. 完全删除 `JNI_OnLoad_Base/JNI_OnUnload_Base` 及其编译宏和手工转调。
6. JVM 显式按 Core、Bolt 的顺序分别执行 `System.load`。
7. 保留 `libglutenlibloader.so` 提供的全局符号可见性能力。
8. loader 不手工调用目标业务库的 `JNI_OnLoad/JNI_OnUnload`。
9. 消除 Core 对 backend 的反向未解析符号。
10. 保持 LLVM JIT 和原生 UDF 查找 Core/Bolt 符号的能力。
11. Jar、外部路径和 Spark Driver/Executor 使用一致的加载顺序。

### 3.2 非目标

以下内容不建议与本次拆分混在一起完成：

- 全面收窄所有 Core/Bolt C++ 导出 ABI。
- 支持在同一 JVM 中热切换或反复卸载、重载 backend DSO。
- 通过 loader 自己实现一套替代 JVM 的 JNI 生命周期管理器。
- 同时重构所有第三方依赖的静态/动态链接方式。
- 将所有 Bolt/Velox loader 代码一次性抽象成完全通用框架。

## 4. 目标架构

### 4.1 DSO 依赖关系

```text
libbolt_backend.so ──DT_NEEDED──> libgluten.so

libgluten.so
  - 不依赖 libbolt_backend.so
  - 不保留由 Bolt/Velox 在运行时补齐的未定义符号

libglutenlibloader.so
  - 不依赖 libgluten.so
  - 不依赖 libbolt_backend.so
  - 建议不依赖 glog/gflags
  - 只依赖 libc/libdl 和必要的 C++ runtime
```

不允许形成以下关系：

```text
libgluten.so ──undefined symbol──> libbolt_backend.so
```

### 4.2 每个 so 的职责

| DSO | 主要职责 | JNI 生命周期 |
|---|---|---|
| `libgluten.so` | Core JNI、公共 native 类型、Runtime/MemoryManager/ThreadManager registry、公共 JNI cache | JVM 调用 Core 自己的标准入口 |
| `libbolt_backend.so` | Bolt runtime、filesystem、shuffle、UDF、Bolt JNI cache | JVM调用 Bolt 自己的标准入口 |
| `libglutenlibloader.so` | 将已由 JVM 加载的 DSO 提升到 global scope；兼容历史 preload API | 只管理 loader 自身入口，不管理 Core/Bolt JNI hooks |

### 4.3 加载状态机

```text
UNINITIALIZED
  │
  │ System.load(libglutenlibloader.so)
  ▼
LOADER_READY
  │
  │ 加载明确列出的公共动态依赖
  │ System.load(libgluten.so)
  ▼
CORE_JNI_READY
  │
  │ promoteGlobal(libgluten.so)
  ▼
CORE_GLOBAL
  │
  │ System.load(libbolt_backend.so)
  ▼
BOLT_JNI_READY
  │
  │ promoteGlobal(libbolt_backend.so)
  ▼
BOLT_GLOBAL
  │
  │ NativeBackendInitializer.initialize(...)
  ▼
BACKEND_READY
```

任一步失败后，不允许继续进入后续状态。Java/Scala 侧应保存明确的失败状态，避免其他线程再次从中间状态继续初始化。

### 4.4 为什么不能只依靠 DT_NEEDED

`DT_NEEDED libgluten.so` 只保证 ELF 动态链接器能映射 Core 并解析 Bolt 所需符号。

如果仅执行：

```text
System.load(libbolt_backend.so)
```

动态链接器可能映射 `libgluten.so`，但 JVM 不会因此自动调用 Core 的
`JNI_OnLoad`。Core JNI cache、internal factories 等状态会保持未初始化。

因此必须显式执行：

```text
System.load(libgluten.so)
System.load(libbolt_backend.so)
```

## 5. CMake 和产物设计

### 5.1 Core target

`cpp/core/CMakeLists.txt` 中的运行时 Core target 固定为共享库：

```cmake
add_library(gluten SHARED ${SPARK_COLUMNAR_PLUGIN_SRCS})
```

删除：

```cmake
GLUTEN_CORE_LIBRARY_TYPE
GLUTEN_CORE_RENAME_JNI_ENTRYPOINTS
JNI_OnLoad=JNI_OnLoad_Base
JNI_OnUnload=JNI_OnUnload_Base
```

新增 backend JNI registry 源文件，并继续输出到：

```text
${root_directory}/releases/libgluten.so
```

### 5.2 Bolt target

Bolt runtime target 显式声明为共享库，不能再依赖全局 `BUILD_SHARED_LIBS` 隐式决定：

```cmake
add_library(bolt_backend SHARED ${BOLT_SRCS})
target_link_libraries(bolt_backend PUBLIC gluten ...)
```

如果现有内部 target 名已经是 `${PROJECT_NAME}`，可以保留 target 名并通过
`OUTPUT_NAME` 固定物理文件名，避免影响测试和 Conan target 引用。

必须设置：

```cmake
set_target_properties(
  bolt_backend
  PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${root_directory}/releases"
    BUILD_RPATH "$ORIGIN"
    INSTALL_RPATH "$ORIGIN")
```

预期动态段：

```text
SONAME  libbolt_backend.so
NEEDED libgluten.so
RUNPATH $ORIGIN
```

### 5.3 loader target

`glutenlibloader` 保持独立 `SHARED` target：

```cmake
add_library(glutenlibloader SHARED JniNativeLibraryLoader.cc)
target_link_libraries(glutenlibloader PRIVATE ${CMAKE_DL_LIBS})
```

建议删除它对 glog 的依赖。loader 是第一个加载的 bootstrap DSO，不应提前决定 glog、gflags 或其他 C++ 依赖版本。

loader 应使用单独的 version script，只导出：

```text
JNI_OnLoad
JNI_OnUnload
Java_org_apache_gluten_jni_BoltJniLibLoader_nativeLoadLibrary
Java_org_apache_gluten_jni_BoltJniLibLoader_nativePromoteLibrary
```

### 5.4 静态 target

当前 Bolt 构建默认设置 `BUILD_STATIC ON`，并生成供测试或 Conan 使用的静态 backend target。

本设计建议将“运行时 JNI bundle”和“静态 C++ 测试库”分开：

- 运行时始终使用 `libgluten.so + libbolt_backend.so`。
- `bolt_backend_static` 可以保留给不需要 JNI lifecycle 的 C++ 单元测试。
- `bolt_backend_static` 不再被视为可独立加载的完整 JNI backend。

如果仍要求发布“完全静态 Core + Bolt”的 Conan 产物，需要单独设计
`gluten_static`、JNI 源文件排除规则以及最终入口所有权。否则很容易重新引入两个标准 JNI 入口冲突或恢复 `*_Base` 模式。

这一点属于待确认决策。

## 6. JNI 生命周期设计

### 6.1 Core JNI_OnLoad

Core 保留源码中的标准入口：

```cpp
jint JNI_OnLoad(JavaVM* vm, void* reserved);
void JNI_OnUnload(JavaVM* vm, void* reserved);
```

建议初始化顺序：

1. 通过 `JavaVM::GetEnv` 获取 `JNIEnv`。
2. 初始化 Core 公共 `JniCommonState/JniErrorState`。
3. 创建 Core global class references 和 method IDs。
4. 注册 internal MemoryManager/ThreadManager/Runtime factories。
5. 返回支持的 JNI version。

factory 尽量最后发布，避免 JNI cache 初始化失败后 registry 已对外可见。

Core `JNI_OnUnload` 只清理 Core 自己拥有的资源：

1. 删除 Core global refs。
2. 关闭 `JniErrorState`。
3. 关闭 `JniCommonState`。
4. 处理 Core 所有的 protobuf 生命周期。

本次修改需要顺便核对 Core 创建和删除的 global refs 是否对称，但不建议扩大成无关的全量 JNI 清理重构。

### 6.2 Bolt JNI_OnLoad

Bolt 保留自己的标准入口，不再调用 Core 入口：

```cpp
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  // 1. GetEnv
  // 2. 检查或初始化公共 JNI state（取决于第 13 节决策）
  // 3. initBoltJniFileSystem
  // 4. initBoltJniUDF
  // 5. OnHeap hook
  // 6. 创建 Bolt global refs/method IDs
  // 7. 最后注册 Bolt InputIterator factory
  // 8. return JNI version
}
```

Bolt `JNI_OnUnload` 只清理 Bolt 自己拥有的状态：

1. 停止发布新的 Bolt callback（仅在支持卸载时需要）。
2. 删除 Bolt global refs。
3. finalize Bolt UDF/filesystem 等 JNI 状态。
4. 清理 Bolt 自己拥有的 logging/runtime 状态。
5. 不调用 Core `JNI_OnUnload`。

### 6.3 公共 JNI state 的两个选项

#### 选项 A：对齐当前 Velox

Bolt `JNI_OnLoad` 也执行：

```cpp
getJniCommonState()->ensureInitialized(env);
getJniErrorState()->ensureInitialized(env);
```

Bolt `JNI_OnUnload` 也执行 `close()`。

优点：

- 与当前 Velox 代码一致。
- 最小修改。
- 即使公共 state 已由 Core 初始化，重复调用仍是幂等的。

缺点：

- Core 与 backend 共同拥有同一 state，所有权不清晰。
- `ensureInitialized/close` 当前不是引用计数模型。
- 卸载顺序依赖幂等实现。

#### 选项 B：Core 独占公共 state

Core 负责初始化和关闭，Bolt 只检查 Core 已完成初始化：

```cpp
getJniCommonState()->assertInitialized();
getJniErrorState()->assertInitialized();
```

检查失败时 Bolt `JNI_OnLoad` 返回 `JNI_ERR`。

优点：

- 所有权和加载顺序清晰。
- backend 不会提前关闭公共 state。
- 更符合两个独立 DSO 的职责边界。

缺点：

- `System.load(libgluten.so)` 必须成为强制前置条件。
- 若同步迁移 Velox，需要修复或明确其单路径加载模式。

推荐选项 B，但在实施前需要确认是否同时迁移 Velox 的公共 state 所有权。

## 7. 消除 Core 到 backend 的反向符号依赖

### 7.1 当前问题

Core JNI wrapper 当前调用：

```cpp
gluten::createInputIterator(env, javaIterator, runtime, iteratorIndex);
```

实现位于 backend DSO。现有 `libgluten.so` 的动态符号表中，
`gluten::createInputIterator` 是未定义符号。

这意味着：

- Core 单独以 `RTLD_NOW` 加载可能失败。
- Core 必须等待某个 backend DSO 在全局 scope 中补齐符号。
- 同一进程加载多个 backend 时，符号可能被错误抢占。
- `libgluten.so -> libbolt_backend.so` 形成隐式反向依赖。

### 7.2 推荐 registry

新增：

```text
cpp/core/jni/BackendJniRegistry.h
cpp/core/jni/BackendJniRegistry.cc
```

建议 API：

```cpp
namespace gluten {

using InputIteratorFactory =
    std::unique_ptr<ColumnarBatchIterator> (*)(
        JNIEnv* env,
        jobject javaIterator,
        Runtime* runtime,
        int32_t iteratorIndex);

void registerInputIteratorFactory(
    const std::string& backendKind,
    InputIteratorFactory factory);

std::unique_ptr<ColumnarBatchIterator> createBackendInputIterator(
    JNIEnv* env,
    jobject javaIterator,
    Runtime* runtime,
    int32_t iteratorIndex);

} // namespace gluten
```

使用普通函数指针而不是 `std::function`，避免 Core registry 持有需要调用 backend 析构代码的闭包对象。

Core JNI wrapper 改为：

```cpp
auto arrayIter = createBackendInputIterator(env, iter, runtime, idx);
```

`createBackendInputIterator` 直接使用已有的：

```cpp
runtime->kind()
```

作为 registry key，因此不需要修改 `Runtime` ABI，也不需要 Java 额外传 backend 名称。

### 7.3 注册时机

Bolt 在自己的 `JNI_OnLoad` 完成所有本地初始化后，最后执行：

```cpp
registerInputIteratorFactory(
    kBoltBackendKind,
    &createBoltInputIterator);
```

Velox 同步改为注册：

```cpp
registerInputIteratorFactory(
    kVeloxBackendKind,
    &createVeloxInputIterator);
```

随后删除 Velox 当前直接导出的 `gluten::createInputIterator`。

必须同时迁移 Bolt 和 Velox。只迁移 Bolt 不能消除 Core 的 backend 反向符号。

### 7.4 registry 行为

基础版本要求：

- 使用 mutex 保护 map。
- 相同 backend kind 重复注册时严格失败。
- 未注册 factory 时抛出带 backend kind 的 `GlutenException`。
- factory 为空或返回空 iterator 时视为 backend bug。
- 锁内只取得函数指针，锁外调用 backend 代码，避免重入死锁。

若 DSO 按进程生命周期持有，可以不在生产路径支持 unregister。

如果要求 ClassLoader 热卸载，则 registry 还需要 registration token、in-flight 计数、停止接收新调用和等待 callback 归零，这会明显扩大设计范围。

## 8. libglutenlibloader.so 详细设计

### 8.1 新加载路径

推荐的新路径是“JVM 先加载，再提升可见性”：

```text
System.load(target)
nativePromoteLibrary(targetCanonicalPath)
```

native promotion 使用：

```cpp
RTLD_NOLOAD | RTLD_NOW | RTLD_GLOBAL
```

含义：

- `RTLD_NOLOAD`：只允许提升一个已经存在的 link map，不允许 loader 偷偷映射新的 JNI DSO。
- `RTLD_NOW`：立即暴露未解析符号问题。
- `RTLD_GLOBAL`：允许 LLVM JIT/UDF 通过 process symbol lookup 找到该 DSO 的动态符号。

使用 `RTLD_NOW` 的前提是第 7 节的 registry 已经消除 Core 中的 backend 未定义符号。

### 8.2 兼容 API

保留现有 JNI ABI：

```java
static native boolean nativeLoadLibrary(String path, int flags);
```

它继续支持历史“先 dlopen，再 System.load”的调用方，但：

- 不调用目标 `JNI_OnLoad`。
- 必须规范 flags。
- 必须保存和去重 handle。
- 作为兼容接口标记为 deprecated。

新增：

```java
static native void nativePromoteLibrary(String canonicalPath);
```

新接口不接受 Java 传入的 raw flags，避免 Linux/macOS flag 值差异和调用者误用。

### 8.3 native 状态

实现按 canonical path 保存每个 DSO 的 single-flight 状态：

```text
ABSENT
  ├─ nativeLoadLibrary ─> LOADING ─> LOADED_LOCAL / LOADED_GLOBAL
  └─ nativePromoteLibrary ─────────> PROMOTING ─> LOADED_GLOBAL

LOADED_LOCAL ─> PROMOTING ─> LOADED_LOCAL / LOADED_GLOBAL

LOADING / PROMOTING ─> FAILED
```

要求：

- 同一路径重复 promotion 幂等。
- LOCAL 只能升级为 GLOBAL，不能降级。
- LAZY 只能升级为 NOW，不能降级。
- 保存额外的 promotion handle，进程运行期间不 `dlclose`。
- 同一路径并发请求采用 single-flight。
- 不在持有全局 mutex 时执行 `dlopen`，防止 ELF static constructor 重入 loader 后死锁。
- 同线程对同一路径重入时明确失败，避免等待自身造成死锁。
- `FAILED` 保存第一次异常；同一路径后续调用复用该失败，不重试。

### 8.4 错误处理

必须修复当前实现中的问题：

1. `GetStringUTFChars` 返回空时立即处理。
2. 使用 RAII 保证 `ReleaseStringUTFChars`。
3. 每次 `dlopen/dlsym` 前清空 `dlerror`，失败后只读取一次并复制字符串。
4. 不再用 `ThrowNew(thisClass, ...)`，应查找并抛出正确异常类。
5. 路径/依赖/符号错误抛 `UnsatisfiedLinkError`。
6. 非法 flags 抛 `IllegalArgumentException`。
7. null 参数抛 `NullPointerException`。
8. native 异常不能跨越 JNI 边界。
9. 抛出异常后返回 `JNI_FALSE`，不能始终返回成功。

### 8.5 不负责目标库 JNI 生命周期

loader 禁止：

- 手工调用 Core/Bolt `JNI_OnLoad`。
- 登记 Core/Bolt `JNI_OnUnload` hook。
- 手工调用 Core/Bolt `JNI_OnUnload`。
- 使用反射操作 `ClassLoader.nativeLibraries`。

原因是 raw `dlopen` 不会把 DSO 登记到发起加载的 JVM ClassLoader 中。手工调用 JNI hooks 会造成 JVM 状态与 native 状态不一致，并可能在随后 `System.load` 时初始化两次。

### 8.6 平台范围

`RTLD_NOLOAD` promotion 首先以 Linux/glibc 为主要支持目标。

如果 Bolt 必须支持 macOS，需要单独验证 macOS 对相同 DSO 的 global promotion 行为。若行为不满足要求，可以保留历史 preload 兼容路径，但不能默认假设两个平台语义完全相同。

## 9. Java/Scala 加载层

### 9.1 当前模块状态

当前工作树已包含完整的 `backends-bolt` Maven 模块，以及 C++ 导出：

```text
Java_org_apache_gluten_jni_BoltJniLibLoader_nativeLoadLibrary
```

对应的 JVM 加载入口也已存在：

```text
backends-bolt/src/main/java/org/apache/gluten/jni/BoltJniLibLoader.java
backends-bolt/src/main/scala/org/apache/gluten/backendsapi/bolt/BoltListenerApi.scala
backends-bolt/pom.xml
```

当前问题不是恢复模块，而是上述 Java loader 仍保留历史单体 so 的加载、事务和反射卸载模式。实施时直接修改当前模块，使 JVM 加载顺序与新的 native ABI 同步。

### 9.2 JniLibLoader 扩展

现有 `JniLibLoader.load(String)` 负责解压资源并 `System.load`，但不返回最终 real path。新 loader promotion 需要同一个 canonical absolute path。

建议增加一个新方法，同时保留现有 API：

```java
public synchronized String loadAndGetPath(String resourcePath);
```

行为：

1. 将资源复制到 `JniWorkspace`。
2. 使用 `Path.toRealPath()` 得到 canonical path。
3. 使用该路径执行 `System.load`。
4. 返回同一个 canonical path。

现有方法保持二进制兼容：

```java
public synchronized void load(String resourcePath) {
  loadAndGetPath(resourcePath);
}
```

外部路径可增加对应的：

```java
public static synchronized String loadFromPathAndGetPath(String path);
```

### 9.3 BoltJniLibLoader Java wrapper

建议恢复精简版 `BoltJniLibLoader`，不恢复历史版本中重复实现的资源解压、事务和反射卸载逻辑。

其职责只包括：

- 声明兼容 `nativeLoadLibrary`。
- 声明新 `nativePromoteLibrary`。
- 接收 `JniLibLoader` 返回的 canonical path。
- 管理 Bolt native loading 的顺序状态。

### 9.4 BoltListenerApi 加载顺序

建议逻辑：

```scala
val workspace = JniWorkspace.getDefault
val loader = workspace.libLoader

// 在执行任何 System.load 前，一次性检查三个主资源存在。
val loaderPath = loader.loadAndGetPath(loaderResource)

// 解压/加载 Core 和 Bolt 使用的公共动态依赖。
SharedLibraryLoaderUtils.load(conf, loader)

val corePath = loader.loadAndGetPath(coreResource)
BoltJniLibLoader.nativePromoteLibrary(corePath)

val boltPath = loader.loadAndGetPath(boltResource)
BoltJniLibLoader.nativePromoteLibrary(boltPath)

NativeBackendInitializer
  .forBackend(BoltBackend.BACKEND_KIND)
  .initialize(...)
```

已经确认：

- `initBoltJniUDF()` 在 Bolt `JNI_OnLoad` 中只缓存 Java class/method。
- 真正的原生 UDF `dlopen` 位于 `BoltBackend::initUdf()`。
- `BoltBackend::initUdf()` 在 `NativeBackendInitializer.initialize()` 期间执行。

因此，在 `System.load` 之后、backend initialize 之前完成 promotion，可以保持 LLVM JIT/UDF 功能。

### 9.5 并发初始化

建议用明确状态而不是多个独立 `AtomicBoolean`：

```text
UNINITIALIZED
INITIALIZING
READY
FAILED
```

要求：

- 同一 JVM 只允许一个线程执行加载链。
- 其他线程等待相同结果。
- `FAILED` 保存第一个异常，后续调用重新抛出，不从中间状态重试。
- Driver/Executor 在独立进程中分别初始化。
- local mode 中 Driver 完成后 Executor 路径跳过重复初始化。

## 10. Jar 和原生依赖打包

### 10.1 Jar 布局

三个主 so 必须位于同一资源目录：

```text
${platform}/${arch}/
├── libglutenlibloader.so
├── libgluten.so
├── libbolt_backend.so
└── 经过明确选择的运行时依赖
```

`JniWorkspace` 解压后保持相同目录层级：

```text
<jni-workspace>/${platform}/${arch}/
├── libglutenlibloader.so
├── libgluten.so
└── libbolt_backend.so
```

`libbolt_backend.so` 使用 `$ORIGIN` 查找同目录的 `libgluten.so`。

### 10.2 干净 staging

历史 `backends-bolt/pom.xml` 会把整个 `cpp/build/releases` 复制到 Jar。该方式可能包含：

- 上一次构建的陈旧产物。
- `libvelox.so`。
- 测试库和 UDF 示例。
- 不相关架构的产物。
- 带绝对 RUNPATH 的本地构建文件。

建议 CMake 安装到 backend 专属 staging：

```text
cpp/build/package/bolt/${platform}/${arch}/
```

Maven 只复制 staging 中的 manifest/白名单文件，并在打包开始前检查三个主 so 均存在。

### 10.3 runtime manifest

仅打包三个主 so 是否足够，取决于最终的 `DT_NEEDED`。

建议构建阶段生成 Bolt runtime manifest，内容包括：

- 主业务 so。
- loader so。
- 必须随 Jar 分发的第三方 DSO。
- 每个文件的 SONAME 和架构。

Maven 根据 manifest 打包，避免手工维护整套依赖列表。

本轮实现采用两层交付契约：Bolt backend Jar 中的 manifest 记录三个主 DSO
的文件名、SONAME、架构，以及 `dependency.delivery=thirdparty-companion-jar`；
既有 `gluten-thirdparty-lib` companion Jar 继续承载共享第三方 DSO，不在 Bolt
backend Jar 中重复打包。最终 `libbolt_backend` 能完整链接后，仍需根据
`DT_NEEDED` 校验 companion Jar/系统库闭包；这一步不能仅凭三个主 DSO 的
白名单替代。

### 10.4 RUNPATH 约束

最终产物不得携带：

```text
/data00/home/...
/home/.../tools/...
cpp/build/releases
```

允许的优先形式是：

```text
$ORIGIN
$ORIGIN/<明确的相对子目录>
```

## 11. 外部路径模式

现有 `GLUTEN_LIB_PATH` 只能表达一个文件，而拆分后有三个主 so。

推荐兼容语义：

- `GLUTEN_LIB_PATH` 继续指向 `libbolt_backend.so`。
- 从 backend 文件的父目录推导同级：

```text
libgluten.so
libglutenlibloader.so
```

- 在加载任何一个文件前，一次性验证三个文件均存在且架构匹配。
- 三个文件全部 canonicalize。
- 仍按 loader、Core、Bolt 的顺序加载。

备选方案是新增 `GLUTEN_BOLT_LIB_DIR`，但会扩大配置面。该选择需要确认。

## 12. 符号可见性与 JIT/UDF

### 12.1 RTLD_GLOBAL 不是导出机制

`RTLD_GLOBAL` 只能让 `.dynsym` 已存在的符号进入后续查找范围，不能恢复被 visibility 或 version script 隐藏的符号。

因此需要同时确认：

- Core 的 JIT/UDF 所需符号存在于 `libgluten.so` 的动态符号表。
- Bolt 的 JIT/UDF 所需符号存在于 `libbolt_backend.so` 的动态符号表。
- Core/Bolt 不重复导出两份同名实现。

### 12.2 本次建议

第一次拆分建议优先保持现有 JIT/UDF 所需符号集合，先保证行为不变，不在同一修改中大规模收窄 `*gluten::*` 或 `*bytedance::bolt::*`。

后续可设计稳定的 JIT C ABI，再逐步缩小 C++ namespace 导出范围。

### 12.3 链接期检查

完成 backend registry 后，可以考虑对 Linux 共享 target 启用：

```text
-Wl,-z,defs
```

用于阻止新的未解析 backend 反向符号进入 `libgluten.so`。启用前需要确认现有其他合法动态依赖均已显式链接。

## 13. 生命周期与卸载策略

### 13.1 推荐：进程生命周期

Spark Driver/Executor 中的 backend JNI 库通常与 JVM 进程同寿命。本设计推荐：

- Core、Bolt、loader promotion handle 进程期持有。
- 不主动 `dlclose`。
- 不使用反射强制触发 JVM native library finalize。
- backend shutdown 负责停止业务资源，不负责卸载 DSO。
- 最终由进程退出回收 ELF mappings。

该策略能避免以下悬空对象：

- Core registry 中保存的 Bolt/Velox function pointer。
- 仍存活的 backend Runtime vtable。
- LLVM JIT 已解析的函数地址。
- UDF DSO 对 Core/Bolt 符号的引用。

### 13.2 如果必须热卸载

如果生产场景要求 ClassLoader unload/reload，需要扩大设计：

1. 停止提交新任务和 JIT 编译。
2. 等待所有 backend Runtime、iterator、UDF 调用结束。
3. backend registry entry 进入 `accepting=false`。
4. 等待 in-flight callback 为零。
5. 执行 `NativeBackendInitializer.shutdown()`。
6. 注销 Bolt factories/callbacks。
7. JVM 卸载 Bolt。
8. JVM 卸载 Core。
9. loader 逆序释放 promotion handles。

这不是当前代码通过反射调用 native library `finalize` 可以安全解决的问题，应作为单独设计处理。

## 14. 兼容策略

### 14.1 保持不变

- 物理文件名 `libgluten.so`。
- 物理文件名 `libbolt_backend.so`。
- 物理文件名 `libglutenlibloader.so`。
- backend kind 保持 `bolt`。
- 历史 `BoltJniLibLoader.nativeLoadLibrary(String, int)` JNI symbol 暂时保留。

### 14.2 明确变化

- `libbolt_backend.so` 不再包含 Core 静态副本。
- 删除 `JNI_OnLoad_Base/JNI_OnUnload_Base`。
- Jar 必须同时包含 Core 与 Bolt 两个业务 so。
- 外部部署必须同时分发三个主 so。
- 单独只提供 `libbolt_backend.so` 不再是完整部署。
- loader 新代码走 post-load promotion，不再默认使用 preload 双开。

### 14.3 旧 Jar 兼容

若存在仓库外部的旧 `backends-bolt` jar：

- native loader 保留旧 JNI 方法，避免立即出现 `UnsatisfiedLinkError`。
- 旧 jar 仍只加载单体 `libbolt_backend.so`，不能自动适配新的拆分产物。
- 不建议混用“旧 Java jar + 新拆分 native bundle”。
- Java 与 native bundle 应通过同一版本发布。

## 15. 逐文件修改清单

| 文件或模块 | 计划修改 |
|---|---|
| `cpp/bolt.CMakeLists.cmake` | 删除 Core STATIC 和 JNI entrypoint rename 设置 |
| `cpp/core/CMakeLists.txt` | 固定构建共享 Core；加入 Backend JNI registry；删除 rename 分支 |
| `cpp/bolt/CMakeLists.txt` | 显式构建共享 Bolt；动态链接 Core；设置输出目录和 `$ORIGIN` |
| `cpp/core/jni/JniWrapper.h` | 删除 `*_Base` 声明和旧 backend 外部函数声明 |
| `cpp/core/jni/JniWrapper.cc` | 保持标准入口；通过 registry 创建 input iterator |
| `cpp/core/jni/BackendJniRegistry.{h,cc}` | 新增 backend kind 到 JNI callback 的注册和分发 |
| `cpp/bolt/jni/BoltJniWrapper.cc` | 删除 Base 转调；注册 Bolt iterator factory；整理 Bolt 生命周期 |
| `cpp/velox/jni/VeloxJniWrapper.cc` | 迁移 iterator factory，删除直接补齐 Core 符号的实现 |
| `cpp/core/symbols.map` | 验证/导出 registry API；确保标准 JNI 入口可见 |
| `cpp/bolt/symbols.map` | 保持 Bolt JNI/JIT 所需符号；不再包含 Core 静态副本 |
| `cpp/velox/symbols.map` | 配合 iterator registry 调整 |
| `cpp/bolt/nativeLoader/JniNativeLibraryLoader.cc` | 新增 promotion API；修复路径、异常、handle 和并发问题 |
| `cpp/bolt/nativeLoader/CMakeLists.txt` | 移除 glog；链接 libdl；添加最小 symbols map |
| `gluten-core/.../JniLibLoader.java` | 新增 `loadAndGetPath`，保持旧 API 兼容 |
| `backends-bolt/.../BoltJniLibLoader.java` | 精简现有 Java wrapper；保留旧 native ABI；使用新 promotion API |
| `backends-bolt/.../BoltListenerApi.scala` | 明确 loader、Core、Bolt、promotion 和 backend initialize 顺序 |
| `backends-bolt/pom.xml` | 精确打包 staging 内容，不复制整个 releases |
| 根 `pom.xml` 与 bundle profile | 验证现有 Bolt backend module/profile 的接入关系 |
| `ep/bolt/scripts/launch-spark.sh` | 可选：同时检查三个主 so，避免接受不完整 Jar |

`backends-bolt` 已在当前工作树和根 Maven profile 中，无需从历史恢复。

## 16. 测试方案

### 16.1 C++ registry 测试

- Bolt kind 注册并正确分发。
- Velox kind 注册并正确分发。
- 两个 kind 同时存在时不串用 callback。
- 重复注册相同 kind 失败。
- 未注册 kind 返回包含 kind 的明确错误。
- factory 返回空指针时报错。
- 多线程并发查找稳定。

### 16.2 ELF/ABI 检查

对最终三个 DSO 执行 `readelf/nm` 检查：

```text
libgluten.so:
  导出 JNI_OnLoad/JNI_OnUnload
  不存在 JNI_OnLoad_Base/JNI_OnUnload_Base
  不再未定义 gluten::createInputIterator
  不依赖 libbolt_backend.so

libbolt_backend.so:
  导出 JNI_OnLoad/JNI_OnUnload
  NEEDED 包含 libgluten.so
  RUNPATH 为 $ORIGIN
  不包含 Core 静态副本

libglutenlibloader.so:
  不依赖 Core/Bolt/glog
  导出兼容 API 和新 promotion API
  不导出业务 JNI 方法
```

### 16.3 loader 测试

- 未经 `System.load` 的路径调用 promotion 必须失败。
- `System.load` 后 promotion 成功。
- 同一路径重复 promotion 幂等。
- symlink、相对路径和绝对路径归一到同一个 DSO。
- 并发 promotion 只保存一个稳定 entry。
- null、空路径、文件不存在、架构错误、依赖缺失时抛正确 Java 异常。
- 旧 `nativeLoadLibrary` ABI 仍能被历史测试类调用。

### 16.4 JNI 加载测试

使用独立子 JVM 验证：

1. loader、Core、Bolt 正确顺序加载。
2. Core 和 Bolt `JNI_OnLoad` 各执行一次。
3. 直接先加载 Bolt 时受控失败，而不是崩溃。
4. Core JNI 方法至少成功调用一次。
5. Bolt JNI 方法至少成功调用一次。
6. 重复初始化不会重复运行 JNI lifecycle。

native library 测试建议使用 forked JVM，避免同一个 Maven test JVM 内无法可靠卸载 DSO。

### 16.5 JIT/UDF 测试

准备一个 fixture DSO：

- 引用一个 Core 导出符号。
- 引用一个 Bolt 导出符号。
- promotion 前作为负例加载失败。
- Core/Bolt promotion 后成功加载和执行。

同时运行一个现有 Bolt UDF 或 LLVM JIT smoke，证明 loader 功能没有名存实亡。

### 16.6 Spark 集成测试

- Driver 初始化。
- Executor 初始化。
- local mode 不重复初始化。
- 创建 Bolt Runtime/MemoryManager/ThreadManager。
- 执行至少一个包含 input iterator 的 native plan。
- 执行一次 shuffle 或 UDF 路径。
- 按项目要求在 `gluten-ut` 增加至少一个 backend smoke test。

### 16.7 Jar 检查

- 三个主 so 位于同一个 `${platform}/${arch}` 目录。
- 不包含陈旧 `libvelox.so`、测试库或其他架构产物。
- 所有业务 DSO 不含本机构建绝对 RUNPATH。
- runtime manifest 与 Jar 内容一致。

## 17. 实施顺序

建议按以下顺序实施，但在你确认本设计前不修改源码：

### 阶段一：清理跨 DSO 边界

1. 新增 Backend JNI registry。
2. Core 改为调用 registry。
3. Bolt 注册自己的 factory。
4. Velox 同步迁移。
5. 确认 Core 不再有 backend 未定义符号。

### 阶段二：拆分原生 target

1. Core 改为共享库。
2. Bolt 改为独立共享库并动态链接 Core。
3. 删除全部 `*_Base`。
4. 设置输出目录、SONAME 和 `$ORIGIN`。
5. 完成 ELF/ABI 检查。

### 阶段三：loader 与 JVM 加载链

1. 新增 post-load promotion API。
2. 保留和修复旧 ABI。
3. 修改 Bolt Java wrapper 与 Listener。
4. 明确 loader、Core、Bolt、promotion、backend initialize 顺序。
5. 增加并发和错误状态管理。

### 阶段四：打包和验证

1. 建立干净 staging。
2. 更新 Bolt Maven bundle。
3. 增加 registry、loader、JNI 和 JIT/UDF 测试。
4. 运行格式、license header、native build 和 Maven 测试。
5. 提交前提供完整 diff 和测试结果，得到明确同意后再提交。

## 18. 被否决或不推荐的方案

### 18.1 继续 Core STATIC + JNI_OnLoad_Base

这正是本次希望移除的模式，无法得到真正的 DSO 边界。

### 18.2 两个 so，但 Bolt 继续调用 Core JNI_OnLoad

标准 `JNI_OnLoad` 应由 JVM 对每个 JNI DSO 分别调用。跨 DSO 手工转调会导致重复初始化和 ClassLoader 语义错误。

### 18.3 只加载 Bolt，依赖 DT_NEEDED 带入 Core

动态链接器不会替 JVM 调用 Core `JNI_OnLoad`。

### 18.4 完全复制 Velox 的反向未定义符号

让 Core 等待 backend 提供 `createInputIterator` 会继续依赖
`RTLD_LAZY/RTLD_GLOBAL` 和加载顺序。本设计使用 registry 消除该问题。

### 18.5 loader 手工调用 Core/Bolt JNI hooks

这会绕过 JVM native library registry，并可能导致同一入口执行两次。

### 18.6 继续打包整个 releases 目录

容易混入陈旧、无关或带本机路径的产物，无法保证 Jar 可复现。

## 19. 已确认决策

用户已回复“按文档推荐项执行”，以下选择均采用推荐项：

1. **公共 JNI state 所有权**
   - A：对齐当前 Velox，backend 也执行 `ensureInitialized/close`。
   - B：Core 独占公共 state，backend 只检查；本文推荐 B。

2. **DSO 生命周期**
   - A：进程生命周期持有，不支持 ClassLoader 热卸载；本文推荐 A。
   - B：支持热卸载，需要扩展 registry、shutdown 和 handle 协议。

3. **静态 Bolt/Conan**
   - A：静态 target 仅供 C++ 测试，不承诺完整 JNI 静态 bundle；本文推荐 A。
   - B：继续发布完整静态产物，需要额外 target 与 JNI 源文件边界设计。

4. **backends-bolt JVM 模块**
   - 调研更正：模块和根 Maven profile 已存在，直接随本次修改，无需恢复。

5. **外部路径配置**
   - A：`GLUTEN_LIB_PATH` 指向 Bolt backend，并从同目录推导 loader/Core；本文推荐 A。
   - B：新增独立的 Bolt native library directory 配置。

6. **旧 loader JNI ABI**
   - A：保留旧 `nativeLoadLibrary`，新增 promotion API；本文推荐 A。
   - B：直接删除旧 API，只支持新 bundle，会破坏旧 Java 调用方。

7. **Velox 同步范围**
   - A：本次同步迁移 InputIterator registry，但暂不修改其他 Velox lifecycle；本文推荐 A。
   - B：同时统一 Velox 的公共 JNI state 所有权和 path loading，范围更大。

以上推荐项已由用户通过“按文档推荐项执行”确认。

## 20. 实施状态与验证记录

### 20.1 已完成实现

- Core 固定生成独立共享库，Bolt 生成独立共享库并动态链接 Core；另行保留的 Bolt 静态 target 只供 native tests/benchmarks 使用。
- Core、Bolt、loader 均恢复各自的标准 `JNI_OnLoad/JNI_OnUnload`，源码中已无 `*_Base` 入口或重命名宏。
- Core 新增 backend JNI registry；Core 内部、Bolt、Velox 的 InputIterator factory 均按 backend kind 注册和分发。
- 公共 `JniCommonState/JniErrorState` 由 Core 独占，Bolt 只检查 Core 已完成初始化；初始化失败会清理已创建的 global refs。
- loader 保留旧 `nativeLoadLibrary` ABI，并新增 post-load `nativePromoteLibrary`；实现 canonical path、single-flight、幂等升级、首错粘滞和标准 Java 异常映射。
- JVM 侧实现 loader → 公共依赖 → Core/System.load+promote → Bolt/System.load+promote → backend initialize 的顺序，以及并发初始化状态机。
- `GLUTEN_LIB_PATH` 继续指向 Bolt backend，并从同目录推导 loader/Core；Linux 会在首次加载前检查三个 ELF64 文件的实际架构。
- CMake 使用干净 Bolt staging 并生成 runtime manifest；Maven 只接受三个主 DSO 和 manifest，且逐项校验恰好一个文件。

### 20.2 已完成验证

- C++ 格式化和 Maven Spotless 检查通过。
- 最新 Core 共享库、loader 以及三个 Bolt JNI 变更对象均编译通过。
- Core registry：8 个测试通过，包含错误分支、多 backend 隔离和并发分发。
- `backends-bolt` 定向测试：3 个 suite、10 个测试通过，覆盖初始化 gate、资源/外部路径预检、旧 loader ABI、`System.load` 后 promotion、并发幂等和异常映射。
- Core 与 loader 的 Linux ELF 检查已确认标准 JNI hooks、SONAME/RUNPATH 和最小 loader 导出；源码扫描确认无 `JNI_OnLoad_Base/JNI_OnUnload_Base`。

### 20.3 发布前阻塞与未决项

- 当前依赖树中的 Bolt `ReaderStreamIterator` 已变为 5 参数 `updateMetrics`，而仓库现有
  `ReaderStreamIteratorWrapper` 仍实现 8 参数版本。完整 `libbolt_backend.so` 因该基线 ABI
  不匹配无法链接完成；本次 JNI 拆分相关对象已先独立编译通过。
- 因完整 Bolt DSO 尚未产出，最终 `DT_NEEDED(libgluten.so)`、Bolt SONAME/RUNPATH、干净
  staging、最终 Jar 内容、第三方依赖闭包、forked JVM 三 DSO 加载和 JIT/UDF smoke 仍需在
  上述基线问题修复后闭环。
- 新增的 `gluten-ut` 布局测试已写入，但该模块当前先被既有的
  `TestFileSourceScanExecTransformer` 缺失错误阻塞，尚未得到实际执行结果。
- Velox 的完整 native build 当前先被既有的 `cpp/velox/utils/ConfigResolver.h` 缺失阻塞；
  本次只按已确认范围迁移 InputIterator registry，未扩大到 Velox 其他 lifecycle。
- Linux/glibc 是当前已经验证的主路径。是否声明为 Linux-only，还是补齐 GNU linker 参数、
  promotion 语义和测试后正式支持 macOS，仍需用户确认。
