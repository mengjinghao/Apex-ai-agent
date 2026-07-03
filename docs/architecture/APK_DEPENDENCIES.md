# Apex 套件 — APK 依赖与安装指南

> 套件由 **10 个 APK** 组成，分为 **必须 / 可选 / 调试** 三类。
> 用户只需安装必须 APK 即可使用核心功能；可选 APK 按需安装；调试 APK 仅开发场景使用。

---

## 0. TL;DR — 最小安装集

**普通用户最小安装**（5 个 APK，约 100MB）：

```
1. Apex 主应用     (com.apex.agent)             — 必须
2. Apex 引擎       (com.apex.apk.engine)        — 必须
3. Apex 终端       (com.apex.apk.terminal)      — 必须
4. Apex 市场       (com.apex.apk.market)        — 必须
5. Apex 工作文件区 (com.apex.apk.workingfiles)  — 必须
```

**完整安装**（10 个 APK，约 130MB）：在最小安装基础上加：

```
6. Apex 狂暴模式   (com.apex.apk.rage)         — 可选（高级推理）
7. Apex 多 Agent   (com.apex.apk.multiagent)   — 可选（多 Agent 协作）
8. Apex 工作流     (com.apex.apk.workflow)     — 可选（自动化）
9. Apex 语音       (com.apex.apk.voice)         — 可选（TTS/ASR）
10. Apex 诊断      (com.apex.apk.diagnostics)  — 调试（日志/性能）
```

---

## 1. APK 分类详解

### 1.1 必须 APK（5 个）

缺失任一必须 APK 会导致核心功能不可用。主 APK 启动时会自动检查并提示用户安装。

| APK | 包名 | 大小 | 提供能力 | 缺失影响 |
|-----|------|------|----------|----------|
| **主应用** | `com.apex.agent` | 60MB | 普通 Agent 模式 / 设置 / 权限管理 / 业务编排 | 套件入口缺失，无法启动 |
| **引擎** | `com.apex.apk.engine` | 15MB | Shell 执行 / 5 个工具 / 容器 / 无障碍 / Shizuku | 无法执行任何命令和工具 |
| **终端** | `com.apex.apk.terminal` | 10MB | 三块终端（普通/多Agent/狂暴）+ C++ PTY | 无法展示命令输出 |
| **市场** | `com.apex.apk.market` | 8MB | 27 个市场（技能/MCP/插件/模型）+ 安装管理 | 无法安装技能和模型 |
| **工作文件区** | `com.apex.apk.workingfiles` | 5MB | 工作文件夹绑定 + 实时监听 + 代码预览 + SAF | Agent 无法读写文件 |

### 1.2 可选 APK（4 个）

按需安装。调用对应功能前，SDK 会检查是否已安装，未安装时返回友好错误并提示用户前往下载。

| APK | 包名 | 大小 | 提供能力 | 触发场景 |
|-----|------|------|----------|----------|
| **狂暴模式** | `com.apex.apk.rage` | 20MB | 31 个内置技能 + 7 种预设 + 断点续传 | 用户切换到狂暴模式时 |
| **多 Agent** | `com.apex.apk.multiagent` | 8MB | 5 种协作模式 + 5 种角色 + 共享黑板 | 用户切换到多 Agent 模式时 |
| **工作流** | `com.apex.apk.workflow` | 6MB | 8 种节点 + DAG 编排 + 自定义处理器 | 用户创建/执行工作流时 |
| **语音** | `com.apex.apk.voice` | 4MB | TTS 语音合成 + ASR 语音识别 | 用户启用语音输入/朗读时 |

### 1.3 调试 APK（1 个）

普通用户无需安装。开发者和高级用户用于诊断问题。

| APK | 包名 | 大小 | 提供能力 | 触发场景 |
|-----|------|------|----------|----------|
| **诊断** | `com.apex.apk.diagnostics` | 3MB | 日志收集 + 性能监控 + 崩溃堆栈 + heap dump | 开发调试 / 排查问题时 |

---

## 2. 依赖关系

```
                  ┌──────────────┐
                  │  主应用 (必须) │
                  └──────┬───────┘
            ┌────────────┼────────────┐
            ▼            ▼            ▼
       ┌─────────┐  ┌─────────┐  ┌─────────────┐
       │ 引擎(必) │  │ 市场(必) │  │ 工作文件区(必)│
       └────┬────┘  └────┬────┘  └─────────────┘
            │            │
            ▼            │
       ┌─────────┐       │
       │ 终端(必) │       │
       └─────────┘       │
                         │
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
  ┌─────────┐    ┌──────────────┐    ┌─────────┐
  │ 狂暴(可) │    │ 多 Agent(可) │    │ 工作流(可)│
  └─────────┘    └──────────────┘    └─────────┘
   依赖: 引擎+市场   依赖: 引擎+市场    依赖: 引擎

  ┌─────────┐                ┌─────────┐
  │ 语音(可) │                │ 诊断(调) │
  └─────────┘                └─────────┘
   依赖: 主应用              依赖: 主应用
```

**关键**：所有 APK 都依赖主应用（套件入口）。引擎/市场/终端/工作文件区互相独立，但狂暴/多Agent 依赖引擎和市场。

---

## 3. 业务侧使用方式

### 3.1 检查 APK 是否已安装

```kotlin
// 方式 1：通过 ApexClient 的子客户端
if (ApexClient.rage.isAvailable(context)) {
    // 已安装，可以使用狂暴模式
    ApexClient.rage.startSession("分析代码", "reasoning.react")
} else {
    // 未安装，提示用户
    showInstallDialog("rage")
}

// 方式 2：通过 ApexClient 顶层方法
val ready = ApexClient.requireApk(context, ApexSuite.ApkId.RAGE)
if (ready is BridgeResult.Failure) {
    // ready.error.message 是友好提示，直接显示给用户
    showToast(ready.error.message)
    return
}
ApexClient.rage.startSession(...)
```

### 3.2 检查能力（capability）

```kotlin
// 检查"shell"能力是否可用（由 Engine APK 提供）
if (ApexClient.hasCapability(context, "shell")) {
    ApexClient.engine.executeShell("ls")
}

// 强制要求能力
val ready = ApexClient.requireCapability(context, "burst")
if (ready is BridgeResult.Failure) {
    showToast(ready.error.message)  // "能力 'burst' 需要安装：Apex 狂暴模式"
    return
}
```

### 3.3 自动友好错误

调用未安装 APK 的方法时，会自动返回友好错误：

```kotlin
val result = ApexClient.rage.startSession("任务", "reasoning.react")
when (result) {
    is BridgeResult.Success -> { /* 处理结果 */ }
    is BridgeResult.Failure -> {
        // result.error.message = "「Apex 狂暴模式」未安装（可选组件，按需安装）。请前往下载页安装后再使用此功能。"
        // 直接显示给用户
        showErrorDialog(result.error.message)
    }
}
```

### 3.4 监听 APK 安装/卸载事件

```kotlin
// 订阅事件总线，监听套件中 APK 的安装/卸载
SuiteEventBus.events.onEach { event ->
    when (event.type) {
        SuiteEventTypes.APK_INSTALLED -> {
            // event.payload["apkId"] = "rage"
            // event.payload["displayName"] = "Apex 狂暴模式"
            refreshUi()
            showToast("${event.payload["displayName"]} 已安装")
        }
        SuiteEventTypes.APK_UNINSTALLED -> {
            refreshUi()
        }
        SuiteEventTypes.APK_REQUIRED_MISSING -> {
            // 主 APK 启动时检测到必须 APK 缺失
            // event.payload["summary"] 是完整提示文案
            showMissingApksDialog(event.payload["summary"] as String)
        }
    }
}.launchIn(scope)
```

### 3.5 启动 APK 安装

```kotlin
// 跳转到 GitHub Release 下载页
ApexClient.openDownloadPage(context, ApexSuite.ApkId.RAGE)

// 或通过 Market APK 触发安装
ApexClient.market.installSuiteApk(ApexSuite.ApkId.RAGE)

// 或安装本地已下载的 APK 文件
ApexClient.market.installSuiteApk(ApexSuite.ApkId.RAGE, apkFileUri = "file:///sdcard/apex-rage.apk")
```

### 3.6 启动已安装的 APK

```kotlin
ApexClient.launchApk(context, ApexSuite.ApkId.RAGE)
```

### 3.7 获取套件安装摘要

```kotlin
val summary = ApexClient.getInstallSummary(context)
// "已安装 5/10（必须 5/5，可选 0/4，调试 0/1）"
```

---

## 4. Market APK 中的"套件 APK 商店"

Market APK 额外提供"套件 APK 商店"接口，让用户在一个 UI 中管理所有 APK：

```kotlin
// 列出所有 APK 的安装状态
val result = ApexClient.market.listSuiteApks()
// 返回 JSON：
// {
//   "success": true,
//   "count": 10,
//   "apks": "[✓] Apex 主应用 (REQUIRED) v1.0.0\n---\n[✓] Apex 引擎 (REQUIRED) v1.0.0\n---\n[✗] Apex 狂暴模式 (OPTIONAL) ~20MB\n..."
// }

// 检查必须 APK 缺失
val missing = ApexClient.market.checkRequiredApks()
// {"success": true, "missingCount": 0, "missingApkIds": ""}

// 启动安装
ApexClient.market.installSuiteApk(ApexSuite.ApkId.RAGE)

// 启动 APK
ApexClient.market.launchSuiteApk(ApexSuite.ApkId.RAGE)

// 安装摘要
val summary = ApexClient.market.getSuiteInstallSummary()
```

---

## 5. 主 APK 启动时的自动检查

主 APK 通过 `ApexBridgeInitializer`（ContentProvider 自动初始化）在启动时自动：

1. **刷新安装状态快照** — `ApkDependencyManager.refreshInstallState()`
2. **检查必须 APK** — `checkRequiredApks()`
3. **缺失则发布事件** — `SuiteEventTypes.APK_REQUIRED_MISSING`
4. **注册包监听** — `ApkPackageMonitor` 监听后续安装/卸载

业务侧只需订阅 `APK_REQUIRED_MISSING` 事件并显示对话框即可：

```kotlin
// 主 APK 的 Activity 中
SuiteEventBus.events
    .filter { it.type == SuiteEventTypes.APK_REQUIRED_MISSING }
    .onEach { event ->
        val summary = event.payload["summary"] as String
        showMissingApksDialog(summary)
    }
    .launchIn(lifecycleScope)
```

---

## 6. SDK API 速查

### ApkDescriptors（描述符注册表）

```kotlin
ApkDescriptors.ALL                     // 所有 10 个 APK 描述符
ApkDescriptors.REQUIRED                // 5 个必须 APK
ApkDescriptors.OPTIONAL                // 4 个可选 APK
ApkDescriptors.DEBUG                   // 1 个调试 APK
ApkDescriptors.byId("rage")            // 按 ID 查找
ApkDescriptors.byPackage("com.apex.apk.rage")  // 按包名查找
ApkDescriptors.byCapability("shell")   // 按能力查找（返回 List）
ApkDescriptors.dependencyTree("rage")  // 依赖树（递归展开 dependsOn）
```

### ApkDependencyManager（依赖管理器）

```kotlin
ApkDependencyManager.isApkInstalled(context, "rage")           // 是否已安装
ApkDependencyManager.checkRequiredApks(context)                // 缺失的必须 APK 列表
ApkDependencyManager.checkDependencies(context, "rage")        // 缺失的依赖 APK 列表
ApkDependencyManager.findInstalledApksForCapability(context, "shell")
ApkDependencyManager.hasCapability(context, "shell")
ApkDependencyManager.refreshInstallState(context)              // 刷新快照
ApkDependencyManager.startInstall(context, "rage", uri)        // 启动安装
ApkDependencyManager.openDownloadPage(context, "rage")         // 跳转下载页
ApkDependencyManager.launchApk(context, "rage")                // 启动 APK
ApkDependencyManager.getInstalledVersion(context, "rage")      // 已安装版本
ApkDependencyManager.buildMissingApksMessage(missingList)      // 友好提示文案
ApkDependencyManager.getInstallSummary(context)                // "已安装 5/10（...）"
```

### ApexClient（业务侧统一入口）

```kotlin
ApexClient.isApkInstalled(context, "rage")
ApexClient.hasCapability(context, "shell")
ApexClient.requireApk(context, "rage")              // 返回 BridgeResult
ApexClient.requireCapability(context, "shell")
ApexClient.getMissingRequired(context)
ApexClient.getInstallSummary(context)
ApexClient.openDownloadPage(context, "rage")
ApexClient.launchApk(context, "rage")

// 子客户端的便捷方法
ApexClient.rage.isAvailable(context)
ApexClient.multiAgent.isAvailable(context)
ApexClient.workflow.isAvailable(context)
ApexClient.voice.isAvailable(context)
// ...每个客户端都有
```

---

## 7. 安装流程

### 7.1 用户首次安装

1. 用户从 GitHub Release 下载主 APK 安装
2. 主 APK 启动 → `ApexBridgeInitializer` 检查必须 APK
3. 检测到 Engine/Terminal/Market/WorkingFiles 未安装 → 发布 `APK_REQUIRED_MISSING` 事件
4. 主 APK UI 显示对话框：「以下必要组件未安装：Apex 引擎、Apex 终端...」
5. 用户点击「前往下载」→ 跳转 GitHub Release 页面
6. 用户下载并安装其他必须 APK
7. `ApkPackageMonitor` 监听到安装事件 → 刷新状态 → 通知 UI 关闭对话框

### 7.2 按需安装可选 APK

1. 用户在主 APK 中切换到「狂暴模式」
2. 业务代码调用 `ApexClient.rage.isAvailable(context)` → 返回 false
3. 显示对话框：「Apex 狂暴模式 未安装（可选组件）。是否前往下载？」
4. 用户点击「下载」→ `ApexClient.openDownloadPage(context, "rage")`
5. 用户安装 → `ApkPackageMonitor` 监听 → 刷新状态
6. 用户再次切换狂暴模式 → 检测到已安装 → 正常启动会话

### 7.3 通过 Market APK 统一管理

1. 用户打开 Market APK → 切换到「套件管理」Tab
2. 调用 `ApexClient.market.listSuiteApks()` 获取所有 APK 状态
3. UI 显示：必须 APK（绿色✓已安装）、可选 APK（灰色✗未安装）、调试 APK
4. 用户点击「安装」→ `ApexClient.market.installSuiteApk(apkId)`
5. 用户点击「打开」→ `ApexClient.market.launchSuiteApk(apkId)`

---

## 8. 签名要求

**所有 APK 必须使用同一 keystore 签名**，否则：

- `android:sharedUserId` 在旧设备上无法生效
- `android:permission="signature"` 的 BIND 权限无法跨 APK 授权
- `ApkPackageMonitor` 仍能监听，但跨 APK bindService 会失败

签名配置：

```properties
# local.properties
RELEASE_STORE_FILE=/path/to/apex.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=apex
RELEASE_KEY_PASSWORD=...
```

---

## 9. 完整能力清单

| 能力标签 | 提供的 APK | 用途 |
|---------|-----------|------|
| `agent.normal` | 主应用 | 普通 Agent 模式 |
| `settings` | 主应用 | 套件设置 |
| `permissions` | 主应用 | 权限管理 |
| `orchestration` | 主应用 | 业务编排 |
| `shell` | 引擎 | Shell 命令执行 |
| `tools` | 引擎 | file/network/system/process/code 工具 |
| `container` | 引擎 | proot 沙箱容器 |
| `accessibility` | 引擎 | 无障碍服务（点击/滑动/截图） |
| `shizuku` | 引擎 | Shizuku 高权限调用 |
| `terminal` | 终端 | 终端会话 |
| `pty` | 终端 | C++ PTY 伪终端 |
| `shell.session` | 终端 | Shell 会话管理 |
| `market` | 市场 | 市场搜索 |
| `skills.install` | 市场 | 技能安装 |
| `mcp.install` | 市场 | MCP 安装 |
| `models.invoke` | 市场 | 模型调用 |
| `files` | 工作文件区 | 文件读写 |
| `workspace` | 工作文件区 | 工作文件夹 |
| `code.preview` | 工作文件区 | 代码预览 |
| `burst` | 狂暴模式 | 狂暴模式会话 |
| `rage` | 狂暴模式 | 狂暴模式（同 burst） |
| `reasoning.advanced` | 狂暴模式 | 高级推理（ReAct/ToT/CoT 等） |
| `skills.burst` | 狂暴模式 | 狂暴技能（31 个内置） |
| `multiagent` | 多 Agent | 多 Agent 协作 |
| `collaboration` | 多 Agent | 协作（同 multiagent） |
| `blackboard` | 多 Agent | 共享黑板 |
| `workflow` | 工作流 | 工作流执行 |
| `dag` | 工作流 | DAG 编排 |
| `automation` | 工作流 | 自动化 |
| `tts` | 语音 | 语音合成 |
| `asr` | 语音 | 语音识别 |
| `voice` | 语音 | 语音（同 tts+asr） |
| `diagnostics` | 诊断 | 诊断 |
| `logs` | 诊断 | 日志收集 |
| `profiling` | 诊断 | 性能分析 |
| `crash.report` | 诊断 | 崩溃报告 |
