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
├── lib/                          # 新增功能库
│   ├── multi-agent/              # 多 Agent 协作引擎
│   ├── workflow/                 # 工作流 DAG 编排
│   └── working-files/            # 文件监听 + 代码预览
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

## 📄 License

Apache License 2.0 — 见 [LICENSE](./LICENSE)
