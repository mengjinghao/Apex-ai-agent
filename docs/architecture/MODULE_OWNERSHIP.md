# Apex 套件 — 模块归属规则

> 明确每个模块"属于哪个 APK"，防止库被错误地打包进多个 APK 导致体积膨胀。

---

## 0. TL;DR

| 模块类型 | 打包范围 | 校验机制 |
|----------|----------|----------|
| `:sdk:*` | **所有 APK 共享** | 不限制 |
| `:lib:*` | **只打包进对应 APK** | `ModuleOwnershipPlugin` 编译时校验 |
| `:core:*` / `:engine` / `:plugins:*` / `:ai-terminal` / `:domain` 等 | 按需引用 | 不限制 |
| `:app` | 主 APK 自身 | — |
| `:apk:*` | 各功能 APK 自身 | — |

---

## 1. 模块归属表

### 1.1 共享 SDK（所有 APK 都打包）

| 模块 | 用途 | 大小估算 |
|------|------|----------|
| `:sdk:common-core` | 常量 / 模型 / 错误 / 日志 / Trace / ApkDescriptors | ~50KB |
| `:sdk:common-ui` | Compose 主题 / 通用组件 | ~100KB |
| `:sdk:process-bridge` | 零延迟通信核心（InProcessRegistry + AIDL + LocalSocket + ApexClient） | ~150KB |
| `:sdk:watchdog` | 心跳 + 死亡监听 + 自愈 | ~30KB |
| `:sdk:auth` | PermissionBridge（共享权限路由） | ~20KB |
| `:sdk:storage` | ApexDataStore（共享 DataStore） | ~30KB |

### 1.2 功能 APK 私有库（⚠️ 只打包进对应 APK）

| 模块 | 归属 APK | 大小估算 | 原因 |
|------|----------|----------|------|
| `:lib:multi-agent` | `:apk:multi-agent` | ~80KB | 多 Agent 协作引擎，只有多 Agent APK 需要 |
| `:lib:workflow` | `:apk:workflow` | ~60KB | 工作流 DAG 编排，只有工作流 APK 需要 |
| `:lib:working-files` | `:apk:working-files` | ~200KB（含 java-diff-utils ~500KB） | 代码编辑器 + 快照 + diff + 分支 + Agent流程 + 时间机器 + 冲突检测 + 回放 |
| `:lib:engine` | `:apk:engine` | ~40KB | 引擎领域层（模型/容器生命周期状态机/工具目录/编排器/Shizuku策略），Engine APK 私有 |
| `:lib:rage` | `:apk:rage` | ~120KB | 狂暴模式核心（31 技能目录/任务存储/4 Agent 架构师/引擎/预设），Rage APK 私有 |
| `:lib:market` | `:apk:market` | ~150KB | 市场核心（27 市场目录/缓存/收藏/统计/安装状态机/调用契约/引擎），Market APK 私有 |
| `:lib:terminal` | `:apk:terminal` | ~70KB | 终端领域层（三块会话/PTY 契约/命令历史/输出缓冲/引擎/预设），Terminal APK 私有 |
| `:lib:voice` | `:apk:voice` | ~60KB | 语音核心（TTS/ASR 契约/会话管理/对话缓冲/引擎/预设），Voice APK 私有 |

### 1.3 按需引用模块（不限制）

| 模块 | 典型引用方 |
|------|-----------|
| `:domain` | `:app` / `:apk:rage` / `:apk:multi-agent` 等 |
| `:core:burst-kernel` | `:app` / `:apk:rage` |
| `:core:burst-mode` | `:app` / `:apk:rage` |
| `:core:integration` | `:app` / `:apk:market` |
| `:engine` | `:app` / `:apk:engine` |
| `:plugins:burst-base` / `:plugins:burst-builtin` | `:app` / `:apk:rage` |
| `:ai-terminal` | `:app` / `:apk:terminal` |
| `:database` / `:background` / `:file` | `:app` |

---

## 2. 校验机制：ModuleOwnershipPlugin

### 2.1 工作原理

在 `build-logic/convention/src/main/kotlin/ModuleOwnershipPlugin.kt` 中定义了归属规则：

```kotlin
private val ownershipRules: Map<String, Set<String>> = mapOf(
    ":lib:multi-agent" to setOf(":apk:multi-agent"),
    ":lib:workflow" to setOf(":apk:workflow"),
    ":lib:working-files" to setOf(":apk:working-files"),
    ":lib:engine" to setOf(":apk:engine"),
    ":lib:rage" to setOf(":apk:rage"),
    ":lib:market" to setOf(":apk:market"),
    ":lib:terminal" to setOf(":apk:terminal"),
    ":lib:voice" to setOf(":apk:voice")
)
```

### 2.2 自动应用

- `:apk:*` 模块通过 `apex.suite.apk` convention plugin **自动应用**校验
- `:app` 模块在 `build.gradle.kts` 中手动应用：`id("apex.module.ownership")`

### 2.3 违规报错

如果 `:app` 误加了 `implementation(project(":lib:working-files"))`，构建时会失败：

```
========================================
❌ 模块归属校验失败
========================================
以下依赖违反了模块归属规则：

  ❌ :app 通过 implementation 引用了 :lib:working-files
     但 :lib:working-files 只允许以下模块引用：:apk:working-files
     请改用 ApexClient 跨 APK 调用，或检查 docs/architecture/MODULE_OWNERSHIP.md

如需在主 APK 中使用这些功能，请通过 ApexClient 跨 APK 调用：
  - 多 Agent：ApexClient.multiAgent.*
  - 工作流：ApexClient.workflow.*
  - 工作文件：ApexClient.workingFiles.*
  - 引擎：ApexClient.engine.*
  - 狂暴模式：ApexClient.rage.*
  - 市场：ApexClient.market.*
  - 终端：ApexClient.terminal.*
  - 语音：ApexClient.voice.*
========================================
```

### 2.4 添加新规则

未来如果新增私有库，只需在 `ModuleOwnershipPlugin.kt` 的 `ownershipRules` 中加一行：

```kotlin
private val ownershipRules: Map<String, Set<String>> = mapOf(
    ":lib:multi-agent" to setOf(":apk:multi-agent"),
    ":lib:workflow" to setOf(":apk:workflow"),
    ":lib:working-files" to setOf(":apk:working-files"),
    ":lib:new-feature" to setOf(":apk:new-feature")  // 新增
)
```

---

## 3. 为什么这样设计？

### 3.1 体积优化

如果不限制，`:lib:working-files`（含 java-diff-utils ~500KB）会被打包进：
- 主 APK（+500KB）
- 工作文件 APK（+500KB）
- 任何引用它的其他 APK

每个 APK 都增加 500KB 是浪费。限制后只在 `:apk:working-files` 中打包一次。

### 3.2 职责清晰

主 APK 不应该直接调用工作文件区的内部 API（如 `CodeEditorFacade`）。
应该通过 `ApexClient.workingFiles.*` 跨 APK 调用，保持架构层次清晰。

### 3.3 零延迟不受影响

由于所有 APK 共享 `android:process="com.apex.agent.mainprocess"`，
跨 APK 调用走 `InProcessRegistry`（JVM 直调），延迟为 0。
所以"私有库 + 跨 APK 调用"和"共享库 + 直接调用"性能等同。

---

## 4. 业务侧正确用法

### ❌ 错误：在主 APK 中直接依赖 lib

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":lib:working-files"))  // ❌ 校验失败
}
```

```kotlin
// 主 APK 代码
val facade = TypedServiceRegistry.get<CodeEditorFacade>()  // ❌ 编译不过
facade.createBranch(...)
```

### ✅ 正确：通过 ApexClient 跨 APK 调用

```kotlin
// app/build.gradle.kts — 不需要依赖 :lib:working-files
dependencies {
    implementation(project(":sdk:process-bridge"))  // ✅ 只依赖 SDK
}
```

```kotlin
// 主 APK 代码
val result = ApexClient.workingFiles.createBranch(
    name = "experiment",
    filePath = "/sdcard/proj/Main.kt"
)
if (result is BridgeResult.Success) {
    val branchId = parseJson(result.value).branchId
}
```

### ✅ 正确：在工作文件 APK 中直接使用 lib

```kotlin
// apk/working-files/build.gradle.kts
dependencies {
    implementation(project(":lib:working-files"))  // ✅ 允许
}
```

```kotlin
// 工作文件 APK 代码
val facade = TypedServiceRegistry.get<CodeEditorFacade>()
facade.createBranch(...)
```

---

## 5. 完整依赖图

```
┌─────────────────────────────────────────────────────────────────┐
│                          共享 SDK 层                              │
│   :sdk:common-core  :sdk:common-ui  :sdk:process-bridge          │
│   :sdk:watchdog     :sdk:auth       :sdk:storage                 │
│   （所有 APK 都打包，无限制）                                       │
└──────────────────────────────┬──────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌─────────────────┐  ┌──────────────────┐  ┌────────────────────┐
│   :app (主)     │  │  :apk:multi-agent│  │ :apk:working-files │
│                 │  │                  │  │                    │
│ 不依赖 :lib:*   │  │ 依赖 :lib:multi-  │  │ 依赖 :lib:working- │
│ 通过 ApexClient │  │     agent        │  │     files          │
│ 跨 APK 调用     │  │                  │  │                    │
└─────────────────┘  └──────────────────┘  └────────────────────┘
                              │                      │
                              ▼                      ▼
                     ┌─────────────────┐  ┌─────────────────────┐
                     │:lib:multi-agent │  │:lib:working-files   │
                     │  只打包进此 APK  │  │  只打包进此 APK     │
                     └─────────────────┘  └─────────────────────┘

┌─────────────────┐  ┌──────────────────┐
│ :apk:workflow   │  │  :apk:rage       │
│                 │  │                  │
│ 依赖 :lib:workflow│  │ 不依赖 :lib:*    │
│                 │  │ 依赖 :core:burst-*│
└────────┬────────┘  └──────────────────┘
         ▼
┌─────────────────┐
│:lib:workflow    │
│  只打包进此 APK │
└─────────────────┘
```

---

## 6. 校验插件源码

完整源码见 `build-logic/convention/src/main/kotlin/ModuleOwnershipPlugin.kt`。

关键逻辑：
1. 只在 `:app` 和 `:apk:*` 模块上生效
2. 遍历所有可解析的 configuration 的项目依赖
3. 检查依赖是否在 `ownershipRules` 中且当前模块不在白名单
4. 违规则抛 `GradleException` 让构建失败
5. 在 `preBuild` 任务前自动执行

---

## 7. 常见问题

### Q1: 为什么 `:sdk:*` 不限制？

SDK 是所有 APK 共享的基础设施（通信、日志、权限、存储），每个 APK 都需要。
限制反而会增加重复代码。

### Q2: 为什么 `:core:*` / `:engine` 等不限制？

这些是原有模块，被多个 APK 按需引用。例如 `:engine` 被 `:app` 和 `:apk:engine` 都引用是合理的。
强制限制会破坏现有架构。

### Q3: 如果两个 APK 需要共享同一个 lib 怎么办？

两种方案：

**方案 A**：把 lib 提升为 SDK（如果确实是基础设施）
```kotlin
// ownershipRules 中不加入此 lib，让所有 APK 都可引用
// 把模块从 lib/ 移到 sdk/
```

**方案 B**：在白名单中加入两个 APK
```kotlin
":lib:shared-feature" to setOf(":apk:apk-a", ":apk:apk-b")
```

### Q4: 校验失败但确实需要在主 APK 用怎么办？

如果主 APK 真的需要直接调用某 lib（而非通过 ApexClient），说明该 lib 应该是 SDK 级别。
把它从 `lib/` 移到 `sdk/`，并从 `ownershipRules` 中移除。

但**绝大多数情况下，主 APK 应该通过 ApexClient 跨 APK 调用**，这才是多 APK 架构的精髓。
