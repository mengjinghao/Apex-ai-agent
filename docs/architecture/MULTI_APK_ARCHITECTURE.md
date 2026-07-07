# Apex AI Agent — Multi-APK Architecture

> 把一个单体 Android AI Agent 项目拆分为 **9+ 个独立 APK**，
> 通过 `android:process` 共享 JVM + `InProcessRegistry` 实现
> **零延迟跨 APK 调用** —— 对用户而言，“多个 APK 像一个 APK 一样”。

---

## 0. TL;DR

| 关注点 | 方案 |
|--------|------|
| 零延迟跨 APK 调用 | `android:process="com.apex.agent.mainprocess"` + `InProcessRegistry`（同进程 JVM 直调） |
| 高频流式场景（终端 PTY / 文件 watch） | `LocalSocket`（Linux AF_UNIX abstract namespace） |
| 跨进程降级路径 | AIDL Binder + `IBridgeRegistry` 服务发现 |
| 自愈机制 | `Watchdog` 心跳 + `IBinder.DeathRecipient` |
| 权限共享 | 主 APK 统一申请，通过 `PermissionBridge` 路由请求 |
| 数据共享 | 同 UID → 共享 DataStore / Room / SharedPreferences |
| 签名 | 所有 APK 必须使用同一 keystore |

---

## 1. APK 清单

| # | APK | applicationId | 承载功能 | 依赖库 |
|---|-----|---------------|----------|--------|
| 1 | **Main App** | `com.apex.agent` | 普通 Agent 模式 + 设置 + 权限管理 + 业务编排 | `:app` (含全部原有功能) |
| 2 | **Engine** | `com.apex.apk.engine` | AIDL + Shizuku + 无障碍服务 | `:engine` |
| 3 | **Rage Mode** | `com.apex.apk.rage` | 狂暴模式微内核 + 全部狂暴技能 | `:core:burst-kernel` `:core:burst-mode` `:plugins:burst-base` `:plugins:burst-builtin` |
| 4 | **Multi-Agent** | `com.apex.apk.multiagent` | 多 Agent 协作（角色 + 黑板 + 5 种协作模式） | `:lib:multi-agent` |
| 5 | **Workflow** | `com.apex.apk.workflow` | 工作流 DAG 编排 + 8 种节点类型 | `:lib:workflow` |
| 6 | **Market** | `com.apex.apk.market` | 技能 / 插件 / MCP / 模型 / Agent 角色集成与管理 | `:core:integration` |
| 7 | **Terminal** | `com.apex.apk.terminal` | 三块终端（普通 / 多 Agent / 狂暴）+ C++ PTY | `:ai-terminal` |
| 8 | **Working Files** | `com.apex.apk.workingfiles` | 工作文件夹绑定 + 实时跟随 + 代码预览 | `:lib:working-files` `:file` |
| 9 | **Diagnostics** | `com.apex.apk.diagnostics` | 日志 / 性能 / 调试（补充） | — |
| 10 | **Voice** | `com.apex.apk.voice` | 语音输入 / TTS / ASR（补充） | — |

### 1.1 终端 APK 的三块结构

按用户要求，终端 APK 内部拆分为三块（仍是同一个 APK）：

```
:apk:terminal
├── src/main/java/com/apex/apk/terminal/
│   ├── normal/        ← 普通 Agent 调用的终端
│   ├── multiagent/    ← 多 Agent 调用的终端
│   └── burst/         ← 狂暴模式调用的终端
└── src/main/aidl/com/apex/apk/terminal/
    └── ITerminalBridge.aidl   ← createNormalSession / createMultiAgentSession / createBurstSession
```

调用方通过 `ITerminalBridge.createXxxSession()` 拿到 sessionId，
然后通过 `LocalSocket` 传输 PTY 数据流（避免 Binder 1MB 限制）。

### 1.2 终端库的三层拆分（用户建议采纳）

```
:ai-terminal (库模块)
├── agent-terminal-core/    ← 纯逻辑：数据流传输、命令解析、进程管理
├── agent-terminal-ui/      ← UI 组件：自定义 TerminalView、配色、字体
└── feature-terminal/       ← 集成层：登录态、路由、权限申请
```

当前实现中 `:ai-terminal` 仍是单模块，未来可按上述拆分。

---

## 2. 模块分层

```
Apex-ai-agent/
├── build-logic/                       # Convention Plugins（含 apex.suite.apk）
├── gradle/libs.versions.toml          # Version Catalog
├── settings.gradle.kts                # 注册所有模块
│
├── sdk/                               # ★ 共享 SDK 层（所有 APK 都依赖）
│   ├── common-core/                   # 常量 / 模型 / 错误 / 日志 / Trace
│   ├── common-ui/                     # Compose 主题 / 通用组件
│   ├── process-bridge/                # ★ 零延迟通信核心
│   │   ├── aidl/                      # IBridgeRegistry / IApkBridge / IBridgeCallback / BridgeParcel
│   │   ├── InProcessRegistry.kt       # 进程内 JVM 直调（零延迟路径）
│   │   ├── ApexBridge.kt              # 调用门面：in-proc 优先 → AIDL 降级 → LocalSocket 流
│   │   ├── LocalStreamBridge.kt       # Linux LocalSocket 流通道（终端 PTY 等）
│   │   ├── BridgeRegistryService.kt   # 主 APK 中的注册中心 Service
│   │   ├── BridgeConnection.kt        # 各 APK 绑定到 Registry 的辅助类
│   │   └── ApexBridgeInitializer.kt   # ContentProvider 自动初始化（零侵入）
│   ├── watchdog/                      # 心跳 + 死亡监听 + 自愈
│   ├── auth/                          # PermissionBridge（同 UID 共享权限）
│   └── storage/                       # ApexDataStore（共享 DataStore）
│
├── lib/                               # 新增功能库（被打包进对应 APK）
│   ├── multi-agent/                   # 多 Agent 协作引擎
│   ├── workflow/                      # 工作流 DAG 编排
│   └── working-files/                 # 工作文件夹 + 实时监听 + 代码预览
│
├── core/  engine/  plugins/  ai-terminal/  database/  background/  file/  domain/
│                                      # 原有模块（保留，功能不缺失）
│
├── app/                               # 主 APK（保留原有全部功能 + 套件 SDK）
│
└── apk/                               # 各功能 APK（9 个）
    ├── engine/   rage/   multi-agent/   workflow/   market/
    ├── terminal/ working-files/   diagnostics/   voice/
    └── (每个 APK 都用 apex.suite.apk convention plugin)
```

---

## 3. 零延迟通信原理

### 3.1 三条调用路径

```
┌─────────────────────────────────────────────────────────────────┐
│  调用方代码：                                                     │
│    ApexBridge.invoke("engine/execute", """{"cmd":"ls"}""")      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                  ┌───────────────────────┐
                  │  1. InProcessRegistry │  ← 同进程 JVM 直调
                  │     lookup("engine")  │     延迟：0 ns
                  └───────────┬───────────┘
                              │ miss
                              ▼
                  ┌───────────────────────┐
                  │  2. AIDL Binder       │  ← 跨进程降级
                  │     IBridgeRegistry   │     延迟：~50 us
                  │     .lookup()         │
                  └───────────┬───────────┘
                              │ miss
                              ▼
                  ┌───────────────────────┐
                  │  3. 提示用户安装       │
                  │     Engine APK        │
                  └───────────────────────┘
```

### 3.2 关键设计：`InProcessRegistry`

```kotlin
// 当两个 APK 共享同一进程（android:process="com.apex.agent.mainprocess"）
// 时，它们共享同一个 JVM。任何 APK 注册到 InProcessRegistry 的 Kotlin 实例，
// 其他 APK 可以直接拿到，完全跳过 Binder。

object InProcessRegistry {
    fun <T : Any> register(name: String, instance: T)
    fun <T> lookup(name: String): T?
}
```

调用方代码：
```kotlin
// 主 APK 调用 Engine
val engine: IEngineService = ApexBridge.get("engine")
    ?: error("engine not available")
val result = engine.executeShell("ls /")  // 零延迟，JVM 方法调用
```

### 3.3 流式场景：LocalSocket

```
┌──────────────┐         ┌──────────────────┐         ┌──────────────┐
│  Main APK    │         │  Terminal APK    │         │  LocalSocket │
│              │         │                  │         │  (AF_UNIX)   │
│  ApexBridge  │         │  LocalStream     │         │              │
│  .openStream │────1────│  Server          │────2────│  abstract    │
│  ("terminal")│         │  .start()        │         │  namespace   │
│              │         │                  │         │              │
│  LocalStream │         │                  │         │              │
│  Client      │────3─────────────────────────────────│              │
│  .connect()  │         │                  │         │              │
│              │◀───4─────────────────────────────────│              │
│  PTY output  │         │                  │         │              │
└──────────────┘         └──────────────────┘         └──────────────┘
```

**为什么用 LocalSocket 而不是 Binder？**
- Binder 单事务上限 1MB，终端 PTY 输出可能几 MB → TransactionTooLargeException
- Binder 每次调用都有 ~50us 序列化开销；LocalSocket 是流式 push，单次写入只受 socket buffer 影响
- LocalSocket 不经过 TCP/IP 协议栈，是内核内存拷贝，延迟 < 10us

### 3.4 调用示例

```kotlin
// 主 APK 中，普通 Agent 模式调用终端
suspend fun agentCallTerminal(cmd: String): String {
    // 方式 1：通过 ApexBridge 通用 invoke
    val result = ApexBridge.invoke(
        method = "terminal/createNormalSession",
        argsJson = """{"workingDir":"/sdcard"}"""
    )
    val sessionId = result.getOrNull() ?: return "error"

    // 方式 2：通过 LocalSocket 流式传输
    val channelName = ApexBridge.openStream("terminal", "pty.$sessionId").getOrNull() ?: return "error"
    val client = LocalStreamClient(channelName)
    client.connect()
    client.send("$cmd\n".toByteArray())

    // 收集输出
    val output = StringBuilder()
    client.receiveFlow().collect { chunk ->
        output.append(String(chunk))
        if (output.contains("$ ")) return@collect  // 等待 shell 提示符
    }
    return output.toString()
}
```

---

## 4. SharedUserId 现代兼容性说明

**重要事实**：Android 10+ (API 29+) 已废弃 `android:sharedUserId`，新应用无法使用。

**本架构的应对**：
1. **保留 `android:sharedUserId="com.apex.agent.suite"`** —— 兼容旧设备（Android 8/9）
2. **真正依赖 `android:process="com.apex.agent.mainprocess"`** —— 让所有 APK 的组件运行在同一进程，共享 JVM。这是“零延迟”的真正实现路径，**在所有 Android 版本上都有效**。
3. **权限共享**：通过 `PermissionBridge.requestFromMainApk()` 把权限请求路由到主 APK 的统一 Activity。所有 APK 同签名 → 用户感知上仍是“一次授权处处可用”。

| 机制 | Android 8-9 | Android 10+ |
|------|-------------|-------------|
| `android:sharedUserId` 共享 UID | ✅ 完全工作 | ❌ 已废弃 |
| `android:process` 共享 JVM | ✅ 工作 | ✅ 工作 |
| 同进程零延迟调用 | ✅ | ✅ |
| 权限自动继承 | ✅（共享 UID） | ❌（需 PermissionBridge 路由） |
| 数据共享（DataStore/Room） | ✅（共享 UID 文件访问） | ✅（同进程直接访问） |

---

## 5. 签名配置

所有 APK **必须使用同一 keystore 签名**，否则：
- `android:sharedUserId` 在旧设备上无法生效
- `android:process` 跨 APK 仍可工作，但 `PermissionBridge` 无法信任

**配置方式**：在 `~/.gradle/gradle.properties` 或项目根 `local.properties` 中：

```properties
RELEASE_STORE_FILE=/path/to/apex.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=apex
RELEASE_KEY_PASSWORD=...
```

每个 APK 的 `build.gradle.kts` 通过 `signingConfigs` 引用同一份配置。
（`:app` 已有此配置；`:apk:*` 模块的 convention plugin 已预留扩展点。）

---

## 6. Watchdog 自愈机制

```
┌────────────────────────────────────────────────────────────┐
│  APK A (主)    APK B (引擎)    APK C (狂暴)    ...        │
│    │              │              │                         │
│    │ heartbeat    │ heartbeat    │ heartbeat               │
│    │ (5s)         │ (5s)         │ (5s)                    │
│    └──────────────┴──────────────┴───► Watchdog            │
│                                          │                 │
│                                          │ 15s 未收到      │
│                                          ▼                 │
│                                    ApkUnresponsive event   │
│                                          │                 │
│                                          ▼                 │
│                                    Binder.DeathRecipient   │
│                                          │                 │
│                                          ▼                 │
│                                    重启连接 → 重新注册     │
│                                    → 恢复业务              │
└────────────────────────────────────────────────────────────┘
```

业务侧订阅 `Watchdog.events`：
```kotlin
Watchdog.events.onEach { event ->
    when (event) {
        is WatchdogEvent.ApkDied -> restartConnection(event.apkId)
        is WatchdogEvent.ApkUnresponsive -> reconnect(event.apkId)
        is WatchdogEvent.ApkRecovered -> resumeBusiness()
    }
}.launchIn(scope)
```

---

## 7. 构建命令

```bash
# 构建所有 APK
./gradlew assembleDebug

# 只构建主 APK
./gradlew :app:assembleDebug

# 只构建 Engine APK
./gradlew :apk:engine:assembleDebug

# 构建所有功能 APK（不含主 APK）
./gradlew :apk:engine:assembleDebug \
          :apk:rage:assembleDebug \
          :apk:multi-agent:assembleDebug \
          :apk:workflow:assembleDebug \
          :apk:market:assembleDebug \
          :apk:terminal:assembleDebug \
          :apk:working-files:assembleDebug \
          :apk:diagnostics:assembleDebug \
          :apk:voice:assembleDebug

# 构建产物位置
# app/build/outputs/apk/debug/app-debug.apk
# apk/<name>/build/outputs/apk/debug/<name>-debug.apk
```

---

## 8. 扩展点

### 8.1 添加新的功能 APK

1. 在 `apk/` 下新建目录
2. 创建 `build.gradle.kts`：
   ```kotlin
   plugins {
       id("apex.suite.apk")
       id("apex.android.application.compose")
   }
   android {
       namespace = "com.apex.apk.yourfeature"
       defaultConfig { applicationId = "com.apex.apk.yourfeature" }
   }
   ```
3. 创建 `AndroidManifest.xml`（参考现有 APK）
4. 创建 `Application.kt` + `MainActivity.kt`
5. 在 `settings.gradle.kts` 中 `include(":apk:your-feature")`
6. 完成 —— 自动获得：共享进程 + SDK 依赖 + Bridge 注册 + Watchdog 心跳

### 8.2 添加新的共享库

1. 在 `lib/` 下新建目录
2. 创建 `build.gradle.kts`：
   ```kotlin
   plugins { id("apex.android.library") }
   android { namespace = "com.apex.lib.yourlib" }
   dependencies {
       api(project(":sdk:common-core"))
       api(project(":sdk:process-bridge"))
   }
   ```
3. 在 `settings.gradle.kts` 中 `include(":lib:your-lib")`
4. 在需要的 APK 的 `build.gradle.kts` 中 `implementation(project(":lib:your-lib"))`

### 8.3 添加新的 AIDL 接口

1. 在对应 APK 的 `src/main/aidl/` 下创建 `.aidl` 文件
2. 实现 `Stub`：
   ```kotlin
   class YourBridgeImpl : IYourBridge.Stub() {
       override fun yourMethod(...): ... { ... }
   }
   ```
3. 在 `HostService.onBind()` 中返回 `YourBridgeImpl()`
4. 调用方通过 `ApexBridge.get<IYourBridge>("your-apk")` 拿到代理

---

## 9. 与原项目 (Apex-auto-agent) 的差异

| 维度 | Apex-auto-agent | Apex-ai-agent |
|------|-----------------|---------------|
| APK 输出 | 1 个（单体） | **10 个**（主 + 9 功能 APK） |
| 跨模块调用 | 进程内方法调用 | **同进程 JVM 直调**（零延迟）+ AIDL 降级 + LocalSocket 流 |
| 自愈机制 | 无 | Watchdog + 心跳 + DeathRecipient |
| 权限共享 | 单 APK 内 | PermissionBridge 路由 + SharedUserId（旧设备） |
| 模块数 | 14 | 28（含 6 SDK + 3 新 lib + 9 APK + 原有 10） |
| 签名 | 单 keystore | **所有 APK 必须同 keystore** |
| 文件数 | ~2340 | ~2380（增量主要在 SDK + APK 骨架） |
| 仓库大小 | 60MB | ~65MB |

---

## 10. 路线图

### 已完成（本次提交）
- ✅ 完整的多 APK Gradle 架构骨架（10 APK + 6 SDK + 3 新 lib）
- ✅ `process-bridge` SDK：InProcessRegistry + ApexBridge + LocalSocket + BridgeRegistryService
- ✅ `watchdog` SDK：心跳 + 死亡监听 + 自愈事件流
- ✅ `auth` SDK：PermissionBridge（共享权限路由）
- ✅ `storage` SDK：ApexDataStore（共享 DataStore）
- ✅ `common-ui` SDK：统一主题 + 通用组件
- ✅ 6 个 AIDL 接口（Engine / Rage / MultiAgent / Workflow / Market / Terminal / WorkingFiles）
- ✅ `lib:multi-agent`：5 种协作模式 + Blackboard
- ✅ `lib:workflow`：8 种节点类型 + DAG 编排
- ✅ `lib:working-files`：文件监听 + 代码预览
- ✅ ApexSuiteApkConventionPlugin（自动注入 SDK + 占位符）
- ✅ ContentProvider 自动初始化（零侵入，无需修改 Application）

### 待完善（后续迭代）
- ⬜ 各 APK UI 完整实现（当前为骨架）
- ⬜ 主 APK 业务逻辑迁移到各功能 APK（增量进行，主 APK 保留兜底）
- ⬜ 终端 APK 三块功能完整实现（普通 / 多 Agent / 狂暴）
- ⬜ 市场 APK 集成现有 `:core:integration` 全部市场
- ⬜ 端到端集成测试
- ⬜ 共享 keystore 生成脚本

---

## 11. 参考资料

- [Android SharedUserId 文档（已废弃）](https://developer.android.com/guide/topics/manifest/manifest-element)
- [android:process 文档](https://developer.android.com/guide/topics/manifest/activity-element#proc)
- [LocalSocket / LocalServerSocket](https://developer.android.com/reference/android/net/LocalSocket)
- [ContentProvider 自动初始化（Firebase 同款机制）](https://firebase.google.com/docs/reference/android/com/google/firebase/provider/InitProvider)
- [Now in Android 架构（convention plugins 参考）](https://github.com/android/nowinandroid)
