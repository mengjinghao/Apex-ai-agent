pluginManagement {
    // 优先使用 build-logic 中的 convention plugins
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "ApexAIAgent"

// ============================================================
// 模块清单 — 多 APK 顶层架构
// ------------------------------------------------------------
// 设计目标：
//   1. 一个项目输出 N 个 APK（Main / Engine / Rage / Multi-Agent /
//      Workflow / Market / Terminal / Working-Files / Diagnostics / Voice）
//   2. APK 之间通过 SharedUserId + 共享进程实现“零延迟”调用，
//      高频流式场景（终端 PTY、文件 watch）走 LocalSocket
//   3. 每个 lib 模块打包进对应的 APK；其他 APK 通过进程内反射或
//      AIDL/LocalSocket 直接调用，做到“功能不缺失、调用无隔阂”
//   4. Watchdog + 心跳 + DeathRecipient 实现跨 APK 自愈
// ------------------------------------------------------------
// 层次：
//   sdk:*        → 所有 APK 共享的基础设施（进程桥、看门狗、认证、存储）
//   lib:*        → 新增的功能库（多 Agent / 工作流 / 工作文件区）
//   core:*       → 原有核心层（狂暴模式微内核 / 集成市场 / 领域模型）
//   engine       → 原有引擎服务层（AIDL + Shizuku + 无障碍）
//   plugins:*    → 原有插件层（狂暴技能）
//   ai-terminal  → 原有 AI 终端模块（C++/PTY + UI）
//   database / background / file / domain → 数据层
//   app          → 主 APK（普通 Agent 模式 + 设置 + 权限管理 + 业务编排）
//   apk:*        → 各功能 APK（Engine / Rage / Multi-Agent / Workflow /
//                  Market / Terminal / Working-Files / Diagnostics / Voice）
// ============================================================

// ---------- 主 APK ----------
include(":app")

// ---------- SDK 层（所有 APK 共享） ----------
include(":sdk:common-core")      // 共享基础：常量、模型、错误、SharedUserId 配置
include(":sdk:common-ui")        // 共享 Compose UI：主题、组件
include(":sdk:process-bridge")   // 跨 APK 通信：AIDL + LocalSocket + 进程内直调
include(":sdk:watchdog")         // 看门狗：心跳 + 死亡监听 + 自愈
include(":sdk:auth")             // 共享认证 + 权限桥（SharedUserId 共享权限）
include(":sdk:storage")          // 共享存储：DataStore + Room 基类

// ---------- 新增功能库 ----------
include(":lib:multi-agent")      // 多 Agent 协作库（角色分工 + 黑板 + 协作模式）
include(":lib:workflow")         // 工作流库（DAG 编排 + 节点执行）
include(":lib:working-files")    // 工作文件区库（文件夹绑定 + 实时跟随 + 代码预览）

// ---------- 核心层（原有） ----------
include(":core:burst-kernel")    // 狂暴模式微内核
include(":core:burst-mode")      // 狂暴模式专属库（高级 API / 配置 / 预设 / 监控）
include(":core:integration")     // 集成大目录（skills / mcp / 插件 / 模型平台市场）
include(":domain")               // 领域模型

// ---------- 引擎服务层（原有） ----------
include(":engine")

// ---------- 插件层（原有） ----------
include(":plugins:burst-base")   // 插件抽象层
include(":plugins:burst-builtin") // 内置技能

// ---------- 数据层（原有） ----------
include(":database")
include(":background")
include(":file")

// ---------- 终端模块（原有） ----------
include(":ai-terminal")

// ---------- 工具 ----------
include(":code-analyzer")
include(":code-generator")

// ============================================================
// APK 输出层 — 每个模块产出一个独立 APK
// ============================================================
// 所有 APK 共享：
//   - 同一签名（release keystore）
//   - 同一 android:sharedUserId="com.apex.agent.suite"
//   - 主进程名 com.apex.agent.mainprocess（终端等重 IO 模块可选独立进程 + LocalSocket）
// ============================================================
include(":apk:engine")           // 引擎 APK（AIDL + Shizuku + 无障碍服务）
include(":apk:rage")             // 狂暴模式 APK
include(":apk:multi-agent")      // 多 Agent 模式 APK
include(":apk:workflow")         // 工作流 APK
include(":apk:market")           // 市场 APK（技能 / 插件 / MCP / 模型 / Agent 角色）
include(":apk:terminal")         // 终端 APK（三块：普通 / 多 Agent / 狂暴）
include(":apk:working-files")    // 工作文件区 APK
include(":apk:diagnostics")      // 诊断 APK（日志 / 性能 / 调试，补充）
include(":apk:voice")            // 语音 APK（语音输入 + TTS + ASR，补充）
