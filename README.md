# Apex AI Agent

> **多 APK 架构**的移动端 AI 自动化平台 — 一个项目输出 **10 个独立 APK**，
> 通过 `android:process` 共享 JVM + `InProcessRegistry` 实现**零延迟跨 APK 调用**。
> 对用户而言，“多个 APK 像一个 APK 一样”，没有任何隔阂。

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen?style=flat-square)]()
[![Architecture](https://img.shields.io/badge/Architecture-Multi--APK-blue?style=flat-square)]()
[![Build](https://img.shields.io/badge/Build-Gradle%208-orange?style=flat-square)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](./LICENSE)

---

## 📐 项目定位

**Apex AI Agent** 是 [Apex-auto-agent](https://github.com/mengjinghao/Apex-auto-agent) 的**多 APK 重构版**，把单体应用拆分为 **10 个 APK**，分为三类：

### 必须 APK（5 个，约 100MB）— 核心功能必备

| APK | 包名 | 承载功能 |
|-----|------|----------|
| **Main App** | `com.apex.agent` | 普通 Agent 模式 + 设置 + 权限管理 + 业务编排 |
| **Engine** | `com.apex.apk.engine` | Shell 执行 + 5 个工具 + 容器 + 无障碍 + Shizuku |
| **Terminal** | `com.apex.apk.terminal` | 三块终端（普通 / 多 Agent / 狂暴）+ C++ PTY |
| **Market** | `com.apex.apk.market` | 27 个市场（技能/MCP/插件/模型）+ 安装管理 |
| **Working Files** | `com.apex.apk.workingfiles` | 工作文件夹绑定 + 实时监听 + 代码预览 |

### 可选 APK（4 个，按需安装）

| APK | 包名 | 承载功能 | 触发场景 |
|-----|------|----------|----------|
| **Rage Mode** | `com.apex.apk.rage` | 狂暴模式 + 31 个内置技能 | 用户切换狂暴模式时 |
| **Multi-Agent** | `com.apex.apk.multiagent` | 多 Agent 协作（5 种模式） | 用户切换多 Agent 模式时 |
| **Workflow** | `com.apex.apk.workflow` | 工作流 DAG + 8 种节点 | 用户创建工作流时 |
| **Voice** | `com.apex.apk.voice` | TTS + ASR 语音 | 用户启用语音时 |

### 内置功能（非独立 APK）

| 功能 | 位置 | 说明 |
|------|------|------|
| **Diagnostics** | 主 APK 内置（`com.apex.agent.diagnostics`） | 日志收集 + 性能监控 + 崩溃堆栈 + heap dump，已合并到主 APK |

📖 **完整文档**：
- [APK 依赖与安装指南](docs/architecture/APK_DEPENDENCIES.md) — **必读**
- [多 APK 架构详解](docs/architecture/MULTI_APK_ARCHITECTURE.md)
- [API 速查表](docs/architecture/API_REFERENCE.md)

---

## 🏗️ 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Apex Suite（10 APKs）                     │
│  ┌────┐ ┌──────┐ ┌──────┐ ┌──────────┐ ┌───────┐ ┌───────┐ │
│  │Main│ │Engine│ │ Rage │ │Multi-Agent│ │Workflow│ │Market │ │
│  └─┬──┘ └──┬───┘ └──┬───┘ └────┬─────┘ └───┬───┘ └───┬───┘ │
│    │       │        │          │           │         │     │
│    └───────┴────────┴──────────┴───────────┴─────────┘     │
│                              │                               │
│                              ▼                               │
│              ┌─────────────────────────────────┐             │
│              │  InProcessRegistry (零延迟路径)  │             │
│              │  + AIDL Binder (跨进程降级)      │             │
│              │  + LocalSocket (流式传输)        │             │
│              └─────────────────────────────────┘             │
│                              │                               │
│              ┌─────────────────────────────────┐             │
│              │  Watchdog + 心跳 + DeathRecipient │             │
│              └─────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────┘
            所有 APK 共享 process: com.apex.agent.mainprocess
            所有 APK 共享 SharedUserId: com.apex.agent.suite
            所有 APK 必须同 keystore 签名
```

---

## 🚀 快速开始

### 构建

```bash
# 构建所有 APK
./gradlew assembleDebug

# 构建产物
# app/build/outputs/apk/debug/app-debug.apk
# apk/<name>/build/outputs/apk/debug/<name>-debug.apk
```

### 安装

所有 APK 必须用**同一签名**安装，否则 `android:process` 共享无法生效。

```bash
# 安装主 APK（必须先装）
adb install app/build/outputs/apk/debug/app-debug.apk

# 安装其他 APK（按需）
adb install apk/engine/build/outputs/apk/debug/engine-debug.apk
adb install apk/terminal/build/outputs/apk/debug/terminal-debug.apk
# ...
```

### 签名配置

在 `local.properties` 中：
```properties
RELEASE_STORE_FILE=/path/to/apex.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=apex
RELEASE_KEY_PASSWORD=...
```

---

## 🧩 跨 APK 调用示例

```kotlin
// 主 APK 中调用 Engine APK（零延迟，同进程 JVM 直调）
val result = ApexBridge.invoke(
    method = "engine/executeShell",
    argsJson = """{"cmd":"ls /sdcard"}"""
)
println(result.getOrNull())

// 主 APK 中调用 Terminal APK，通过 LocalSocket 传输 PTY 流
val sessionId = ApexBridge.invoke(
    method = "terminal/createNormalSession",
    argsJson = """{"workingDir":"/sdcard"}"""
).getOrNull()

val channelName = ApexBridge.openStream("terminal", "pty.$sessionId").getOrNull()!!
val client = LocalStreamClient(channelName).apply { connect() }
client.send("ls\n".toByteArray())

client.receiveFlow().collect { chunk ->
    print(String(chunk))  // 实时渲染终端输出
}
```

---

## 📦 模块清单

```
Apex-ai-agent/
├── build-logic/                  # Convention Plugins
├── gradle/libs.versions.toml     # Version Catalog
├── sdk/                          # 共享 SDK 层
│   ├── common-core/              # 常量 / 模型 / 错误 / 日志
│   ├── common-ui/                # Compose 主题 / 通用组件
│   ├── process-bridge/           # ★ 零延迟通信核心
│   ├── watchdog/                 # 心跳 + 死亡监听 + 自愈
│   ├── auth/                     # PermissionBridge
│   └── storage/                  # ApexDataStore
├── lib/                          # 功能 APK 私有库（只打包进对应 APK）
│   ├── multi-agent/              # 多 Agent 协作引擎 → :apk:multi-agent
│   ├── workflow/                 # 工作流 DAG 编排 → :apk:workflow
│   ├── working-files/            # 文件监听 + 代码预览 → :apk:working-files
│   ├── engine/                   # 引擎领域层（容器状态机/工具目录/编排） → :apk:engine
│   ├── rage/                     # 狂暴模式核心（31 技能/架构师/任务存储） → :apk:rage
│   ├── market/                   # 市场核心（27 目录/缓存/安装状态机） → :apk:market
│   ├── terminal/                 # 终端领域层（会话/PTY契约/历史/缓冲） → :apk:terminal
│   └── voice/                    # 语音核心（TTS/ASR契约/会话/对话缓冲） → :apk:voice
├── core/                         # 原有核心层
│   ├── burst-kernel/             # 狂暴模式微内核
│   ├── burst-mode/               # 狂暴模式专属库
│   └── integration/              # 集成市场（skills / mcp / plugins / models）
├── engine/                       # 引擎服务层
├── plugins/                      # 狂暴技能插件
├── ai-terminal/                  # AI 终端模块
├── database/  background/  file/  domain/
├── app/                          # 主 APK
└── apk/                          # 功能 APK（9 个）
    ├── engine/  rage/  multi-agent/  workflow/  market/
    └── terminal/  working-files/  diagnostics/  voice/
```

---

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| DI | Hilt |
| 异步 | Kotlin Coroutines + Flow |
| 持久化 | Room + DataStore + ObjectBox |
| 网络 | OkHttp + Retrofit |
| 后台 | WorkManager |
| 序列化 | kotlinx.serialization |
| 高性能 | C++ (JNI / NDK) |
| **跨 APK 通信** | **android:process + InProcessRegistry + AIDL + LocalSocket** |
| **自愈** | **Watchdog + IBinder.DeathRecipient** |
| 构建 | Gradle 8 + Version Catalog + Convention Plugins |

---

## 📦 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK | compileSdk 35 |
| Min SDK | 26 (Android 8.0) |
| NDK | 26+（编译 C++ 部分） |
| Gradle | 8.5+ |

---

## 📚 文档

- [多 APK 架构详解](docs/architecture/MULTI_APK_ARCHITECTURE.md) — **必读**
- [API 文档](docs/API_Documentation.md)
- [集成指南](docs/INTEGRATION_GUIDE.md)
- [模块搭建指南](docs/MODULE_SETUP_GUIDE.md)
- [技能系统](docs/SKILL_SYSTEM.md)
- [模型切换指南](docs/MODEL_SWITCHER_GUIDE.md)

---

## 🔄 与 Apex-auto-agent 的差异

| 维度 | Apex-auto-agent | Apex-ai-agent |
|------|-----------------|---------------|
| APK 输出 | 1 个（单体） | **10 个** |
| 跨模块调用 | 进程内方法调用 | **同进程 JVM 直调** + AIDL 降级 + LocalSocket 流 |
| 自愈机制 | 无 | Watchdog + 心跳 + DeathRecipient |
| 权限共享 | 单 APK 内 | PermissionBridge 路由 + SharedUserId |
| 模块数 | 14 | 28 |

---

## 🔥 热更新（Hot Update）

主 APK 内置**自更新模块**，从 GitHub Releases 拉取新版本 APK 并调起系统安装器，
无需第三方应用商店。同时内置多套**免费 GitHub 加速镜像**，国内用户可在设置中按需启用或自行添加。

### 工作流程

```
App 启动 → Application.initializeHotUpdate()
              │
              ▼
   镜像源加载（ApexDataStore）
              │
              ▼
   判断是否到达检查间隔（默认 6h）
              │
              ▼
   GET https://api.github.com/repos/{owner}/{repo}/releases/latest
              │
              ▼
   版本号比较（语义化版本）→ 有新版？
              │ 是
              ▼
   StateFlow 刷新为 UpdateAvailable（设置页 / 主界面可观察）
              │ 用户点击"立即更新"
              ▼
   按镜像顺序下载 APK（首个成功即用）
   ├─ GitHub 直连
   ├─ ghproxy.com
   ├─ mirror.ghproxy.com
   ├─ ghps.cc
   ├─ github.moeyy.xyz
   ├─ gh-proxy.com
   ├─ kkgithub.com
   └─ 用户自定义镜像…
              │
              ▼
   完整性校验（Content-Length）
              │
              ▼
   FileProvider 暴露 APK → ACTION_VIEW → 系统安装界面
```

### 模块结构

```
app/src/main/java/com/apex/agent/update/
├── UpdateModels.kt              # 数据模型（UpdateRelease / CheckResult / MirrorSource）
├── UpdateSettings.kt            # 偏好设置 + 语义化版本比较工具
├── MirrorSourceRegistry.kt      # 镜像源注册表（内置 + 自定义）
├── HotUpdateManager.kt          # 核心管理器（检查 / 下载 / 校验 / 安装）
└── ui/
    ├── UpdateDialog.kt          # 更新对话框（版本信息 / 更新日志 / 下载进度）
    └── UpdateSettingsSection.kt # 镜像管理 + 偏好设置 Compose UI
```

### 内置免费镜像

| id | 名称 | 模板 | 特点 |
|----|------|------|------|
| `direct` | GitHub 直连 | `{url}` | 原始地址，海外最快 |
| `ghproxy` | ghproxy.com | `https://ghproxy.com/{url}` | 老牌加速，国内可用 |
| `ghproxy-net` | mirror.ghproxy.com | `https://mirror.ghproxy.com/{url}` | ghproxy 备用节点 |
| `ghps` | ghps.cc | `https://ghps.cc/{url}` | Free CDN mirror |
| `moeyy` | github.moeyy.xyz | `https://github.moeyy.xyz/{url}` | moeyy 加速 |
| `gh-proxy` | gh-proxy.com | `https://gh-proxy.com/{url}` | 公益代理 |
| `kkgithub` | kkgithub.com | 域名替换型 | 替换 github.com → kkgithub.com |
| `gcore` | gh.api.99988866.xyz | `https://gh.api.99988866.xyz/{url}` | 备用节点 |

### 用户操作

1. **设置 → 软件更新 → 检查更新** — 立即检查 GitHub Releases（无视检查间隔）
2. **设置 → 软件更新 → 镜像源管理** — 启用/禁用镜像、添加自定义镜像、测试镜像连通性
3. **设置 → 软件更新 → 镜像源管理 → GitHub 仓库** — 切换检查的仓库（默认 `mengjinghao/Apex-ai-agent`）
4. **设置 → 软件更新 → 镜像源管理 → 启动时自动检查 / 包含预发布 / 仅 Wi-Fi 下载**

### 添加自定义镜像

镜像 URL 模板中使用 `{url}` 作为 GitHub 原始下载地址占位符，例如：

```
https://your-mirror.example.com/{url}
```

下载时，模块会按列表顺序尝试每个**已启用**的镜像，首个成功即用，失败自动回退下一个。
镜像列表通过 `ApexDataStore`（跨 APK 共享）持久化。

### 配置 Release

1. 在 GitHub 仓库 → Releases → Draft a new release
2. Tag 命名建议 `v1.2.3`（语义化版本）
3. 上传 `.apk` 文件作为 Release Asset
4. Release body 作为更新日志，支持 Markdown，对话框会按行渲染 `-` / `*` 开头的列表项

### API 限制与降级

GitHub 未认证 API 限流为 **60 次/小时/IP**。模块已内置：
- 6 小时最小检查间隔（可配置 1–168 小时）
- 404 → 视为"无 Release"，不报错
- 403 → 记录限流日志并降级为"检查失败"
- 所有镜像均失败 → 显示失败状态，用户可手动重试

### 高级特性

| 特性 | 说明 |
|------|------|
| **网络预检** | 检查前先 `NetworkUtils.isNetworkAvailable`，无网络直接返回友好错误，不发请求 |
| **WiFi-only 真实生效** | 下载前检查 `isWifiConnected`，移动网络下直接拒绝并提示用户 |
| **断点续传** | 下载中断后再次尝试同镜像时发送 `Range: bytes=<existing>-`，服务器返回 206 则追加写入；返回 200 则覆盖重下 |
| **SHA-256 校验** | 自动从 release notes 解析 `SHA-256: <hex>` 或 `<apk-name>: <hex>`，校验失败删除文件并报错 |
| **错误分类** | `UpdateError` 区分 NoNetwork / WifiOnly / RateLimited / NoRelease / NetworkError / ParseError / AllMirrorsFailed / IntegrityError / Cancelled / Unknown |
| **系统通知** | 三个通道：`apex.update.available`（发现新版本）、`apex.update.progress`（下载进度，常驻通知栏）、`apex.update.result`（完成/失败） |
| **首次启动延迟** | 首次启动延迟 30 秒检查，避免与冷启动 IO 抢资源 |
| **镜像智能排序** | 上次下载成功的镜像自动前置，加快下一次下载 |
| **取消下载** | `SupervisorJob` + `AtomicReference` 跟踪下载 Job，用户可随时取消 |
| **ProGuard 规则** | 已添加 `@Serializable` 数据类与 sealed class 子类的 keep 规则，release 模式不会崩溃 |
| **单元测试** | `VersionComparatorTest` 覆盖版本比较、SHA-256 解析、镜像 URL 包装等核心逻辑 |

### 模块结构（含优化后）

```
app/src/main/java/com/apex/agent/update/
├── UpdateModels.kt              # 数据模型
├── UpdateError.kt               # 错误分类（10 种）
├── UpdateSettings.kt            # 偏好设置 + 版本比较 + SHA-256 解析
├── MirrorSourceRegistry.kt      # 镜像源注册表
├── HotUpdateManager.kt          # 核心管理器（网络预检/WiFi/续传/SHA256/通知）
├── UpdateNotifier.kt            # 系统通知（3 通道）
└── ui/
    ├── UpdateDialog.kt          # 更新对话框（含 SHA-256 徽章）
    └── UpdateSettingsSection.kt # 镜像管理 + 偏好设置 UI

app/src/test/java/com/apex/agent/update/
└── VersionComparatorTest.kt     # 版本比较 + formatBytes + extractSha256 单元测试
```

---

## 📄 License

Apache License 2.0 — 见 [LICENSE](./LICENSE)
