package com.apex.sdk.common

/**
 * APK 在套件中的角色与必要性。
 *
 * - [REQUIRED]: 必须 APK。主 APK 启动时检查，未安装则提示用户立即安装。
 *               缺失会导致核心功能不可用（如 Engine 缺失 → 无法执行任何 shell 命令）。
 * - [OPTIONAL]: 可选 APK。用户按需安装。功能调用时若未安装，返回友好错误并提示。
 * - [DEBUG]:    调试 APK。仅开发/诊断场景使用，普通用户可不装。
 */
enum class ApkNecessity {
    REQUIRED,
    OPTIONAL,
    DEBUG
}

/**
 * APK 依赖描述符。
 *
 * 描述一个 APK 在套件中的元信息：
 * - 必要性（必须 / 可选 / 调试）
 * - 提供的能力（capabilities，用于能力 → APK 的反向查找）
 * - 依赖的其他 APK（如 Rage 依赖 Engine + Market）
 * - 下载来源（ Play Store / GitHub Release / 内置 assets）
 */
data class ApkDescriptor(
    /** APK ID，对应 [ApexSuite.ApkId]。 */
    val apkId: String,
    /** applicationId（包名）。 */
    val packageName: String,
    /** 显示名（中文）。 */
    val displayName: String,
    /** 描述（一句话说明用途）。 */
    val description: String,
    /** 必要性。 */
    val necessity: ApkNecessity,
    /** 该 APK 提供的能力标签（如 "shell", "tts", "asr"）。 */
    val capabilities: List<String> = emptyList(),
    /** 依赖的其他 APK ID（必须先装）。 */
    val dependsOn: List<String> = emptyList(),
    /** 大致大小（MB，用于安装提示）。 */
    val approxSizeMb: Int = 0,
    /** 下载 URL（GitHub Release / Play Store）。 */
    val downloadUrl: String = "",
    /** 版本号。 */
    val versionName: String = "1.0.0",
    /** 图标资源 ID（可选，主 APK 提供）。 */
    val iconResName: String? = null
)

/**
 * 套件中所有 APK 的依赖描述注册表。
 *
 * 在 SDK 中集中定义，所有 APK 共享。主 APK 启动时遍历 [ALL] 检查必须项；
 * 业务侧调用某能力前可通过 [byCapability] 查找需要哪个 APK。
 */
object ApkDescriptors {

    /**
     * 主 APK — 普通 Agent 模式 + 设置 + 权限管理 + 业务编排。
     *
     * **必须**。套件的入口和枢纽，所有其他 APK 通过它的 BridgeRegistryService 注册。
     */
    val MAIN = ApkDescriptor(
        apkId = ApexSuite.ApkId.MAIN,
        packageName = "com.apex.agent",
        displayName = "Apex 主应用",
        description = "普通 Agent 模式 + 设置 + 权限管理 + 业务编排（套件入口）",
        necessity = ApkNecessity.REQUIRED,
        capabilities = listOf("agent.normal", "settings", "permissions", "orchestration"),
        dependsOn = emptyList(),
        approxSizeMb = 60,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Engine APK — Shell 执行 + 工具调用 + 容器 + 无障碍 + Shizuku。
     *
     * **必须**。所有 Agent 模式（普通/多Agent/狂暴）都需要 Engine 执行实际操作。
     * 缺失会导致：无法执行 shell、无法调用 file/network/system/process/code 工具、
     * 无法做无障碍操作（点击/滑动/截图）。
     */
    val ENGINE = ApkDescriptor(
        apkId = ApexSuite.ApkId.ENGINE,
        packageName = "com.apex.apk.engine",
        displayName = "Apex 引擎",
        description = "Shell 执行 + 工具调用 + 容器 + 无障碍 + Shizuku 高权限",
        necessity = ApkNecessity.REQUIRED,
        capabilities = listOf("shell", "tools", "container", "accessibility", "shizuku"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN),
        approxSizeMb = 15,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Terminal APK — 三块终端（普通/多Agent/狂暴）+ C++ PTY。
     *
     * **必须**。所有 Agent 模式都需要终端来执行命令和展示输出。
     */
    val TERMINAL = ApkDescriptor(
        apkId = ApexSuite.ApkId.TERMINAL,
        packageName = "com.apex.apk.terminal",
        displayName = "Apex 终端",
        description = "三块终端（普通/多Agent/狂暴）+ C++ PTY + LocalSocket 流",
        necessity = ApkNecessity.REQUIRED,
        capabilities = listOf("terminal", "pty", "shell.session"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN, ApexSuite.ApkId.ENGINE),
        approxSizeMb = 10,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Market APK — 27 个市场（技能/插件/MCP/模型）+ 安装/导入管理。
     *
     * **必须**。所有 APK 都需要从市场获取技能、模型配置等。
     */
    val MARKET = ApkDescriptor(
        apkId = ApexSuite.ApkId.MARKET,
        packageName = "com.apex.apk.market",
        displayName = "Apex 市场",
        description = "27 个市场（技能/插件/MCP/模型）+ 安装/导入管理",
        necessity = ApkNecessity.REQUIRED,
        capabilities = listOf("market", "skills.install", "mcp.install", "models.invoke"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN),
        approxSizeMb = 8,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Working Files APK — 工作文件夹绑定 + 实时监听 + 代码预览。
     *
     * **必须**。三种 Agent 模式都需要工作文件夹来读写产物。
     */
    val WORKING_FILES = ApkDescriptor(
        apkId = ApexSuite.ApkId.WORKING_FILES,
        packageName = "com.apex.apk.workingfiles",
        displayName = "Apex 工作文件区",
        description = "工作文件夹绑定 + 实时监听 + 代码预览 + SAF 支持",
        necessity = ApkNecessity.REQUIRED,
        capabilities = listOf("files", "workspace", "code.preview"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN),
        approxSizeMb = 5,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    // ============================================================
    // 可选 APK — 按需安装
    // ============================================================

    /**
     * Rage Mode APK — 狂暴模式微内核 + 31 个内置技能。
     *
     * **可选**。只有用户使用狂暴模式时才需要。
     * 不安装时：狂暴模式相关调用返回 [BridgeError.CODE_APK_NOT_INSTALLED]，
     * 普通 Agent 模式不受影响。
     */
    val RAGE = ApkDescriptor(
        apkId = ApexSuite.ApkId.RAGE,
        packageName = "com.apex.apk.rage",
        displayName = "Apex 狂暴模式",
        description = "狂暴模式微内核 + 31 个内置技能 + 7 种预设 + 断点续传",
        necessity = ApkNecessity.OPTIONAL,
        capabilities = listOf("burst", "rage", "reasoning.advanced", "skills.burst"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN, ApexSuite.ApkId.ENGINE, ApexSuite.ApkId.MARKET),
        approxSizeMb = 20,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Multi-Agent APK — 多 Agent 协作（5 种模式 + 5 种角色 + 黑板）。
     *
     * **可选**。只有用户使用多 Agent 协作时才需要。
     */
    val MULTI_AGENT = ApkDescriptor(
        apkId = ApexSuite.ApkId.MULTI_AGENT,
        packageName = "com.apex.apk.multiagent",
        displayName = "Apex 多 Agent 模式",
        description = "多 Agent 协作（5 种模式：流水线/辩论/对抗/竞速/层级）",
        necessity = ApkNecessity.OPTIONAL,
        capabilities = listOf("multiagent", "collaboration", "blackboard"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN, ApexSuite.ApkId.ENGINE, ApexSuite.ApkId.MARKET),
        approxSizeMb = 8,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Workflow APK — 工作流 DAG 编排 + 8 种节点类型。
     *
     * **可选**。只有用户使用工作流自动化时才需要。
     */
    val WORKFLOW = ApkDescriptor(
        apkId = ApexSuite.ApkId.WORKFLOW,
        packageName = "com.apex.apk.workflow",
        displayName = "Apex 工作流",
        description = "工作流 DAG 编排 + 8 种节点（LLM/Tool/Condition/Loop/Parallel/HTTP/Terminal/Code）",
        necessity = ApkNecessity.OPTIONAL,
        capabilities = listOf("workflow", "dag", "automation"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN, ApexSuite.ApkId.ENGINE),
        approxSizeMb = 6,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Voice APK — TTS + ASR 语音输入输出。
     *
     * **可选**。只有用户使用语音功能时才需要。
     */
    val VOICE = ApkDescriptor(
        apkId = ApexSuite.ApkId.VOICE,
        packageName = "com.apex.apk.voice",
        displayName = "Apex 语音",
        description = "TTS 语音合成 + ASR 语音识别（多语言支持）",
        necessity = ApkNecessity.OPTIONAL,
        capabilities = listOf("tts", "asr", "voice"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN),
        approxSizeMb = 4,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /**
     * Diagnostics APK — 日志收集 + 性能监控 + 崩溃堆栈。
     *
     * **调试**。普通用户可不装；开发和高级用户用于诊断问题。
     */
    val DIAGNOSTICS = ApkDescriptor(
        apkId = ApexSuite.ApkId.DIAGNOSTICS,
        packageName = "com.apex.apk.diagnostics",
        displayName = "Apex 诊断",
        description = "日志收集 + 性能监控 + 崩溃堆栈 + heap dump",
        necessity = ApkNecessity.DEBUG,
        capabilities = listOf("diagnostics", "logs", "profiling", "crash.report"),
        dependsOn = listOf(ApexSuite.ApkId.MAIN),
        approxSizeMb = 3,
        downloadUrl = "https://github.com/mengjinghao/Apex-ai-agent/releases/latest"
    )

    /** 所有 APK 描述符。 */
    val ALL: List<ApkDescriptor> = listOf(
        MAIN, ENGINE, TERMINAL, MARKET, WORKING_FILES,  // 必须
        RAGE, MULTI_AGENT, WORKFLOW, VOICE,             // 可选
        DIAGNOSTICS                                      // 调试
    )

    /** 按 apkId 查找。 */
    fun byId(apkId: String): ApkDescriptor? = ALL.firstOrNull { it.apkId == apkId }

    /** 按包名查找。 */
    fun byPackage(packageName: String): ApkDescriptor? = ALL.firstOrNull { it.packageName == packageName }

    /** 按必要性筛选。 */
    fun byNecessity(necessity: ApkNecessity): List<ApkDescriptor> = ALL.filter { it.necessity == necessity }

    /** 所有必须 APK。 */
    val REQUIRED: List<ApkDescriptor> get() = byNecessity(ApkNecessity.REQUIRED)

    /** 所有可选 APK。 */
    val OPTIONAL: List<ApkDescriptor> get() = byNecessity(ApkNecessity.OPTIONAL)

    /** 所有调试 APK。 */
    val DEBUG: List<ApkDescriptor> get() = byNecessity(ApkNecessity.DEBUG)

    /**
     * 按能力标签反查 APK。一个能力可能由多个 APK 提供（如 "shell" 由 Engine 和 Terminal 都提供）。
     */
    fun byCapability(capability: String): List<ApkDescriptor> = ALL.filter { capability in it.capabilities }

    /**
     * 获取某 APK 的完整依赖链（递归展开 dependsOn）。
     * 例如 Rage 依赖 Engine + Market，Engine 又依赖 Main → 返回 [MAIN, ENGINE, MARKET]。
     */
    fun dependencyTree(apkId: String): List<ApkDescriptor> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<ApkDescriptor>()
        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            val desc = byId(id) ?: return
            desc.dependsOn.forEach(::visit)
            if (desc.apkId != apkId) result.add(desc)  // 不包括自己
        }
        visit(apkId)
        return result
    }
}
