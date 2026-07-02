package com.ai.assistance.aiterminal.terminal.mascot

import com.ai.assistance.aiterminal.terminal.ui.AgentMode
import com.ai.assistance.aiterminal.terminal.ui.TerminalMessage
import com.ai.assistance.aiterminal.terminal.ui.TerminalMessageType
import com.ai.assistance.aiterminal.terminal.ui.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 吉祥物终端集成。
 *
 * 将 Aura 吉祥物与终端会话集成：
 * - 在终端启动时显示欢迎动画
 * - 根据终端状态自动切换吉祥物形态
 * - 在关键操作时播放一次性动画（成功/错误）
 * - 在终端中渲染 ASCII 帧
 *
 * # 使用示例
 *
 * ```
 * val mascotIntegration = MascotTerminalIntegration(sessionManager)
 * mascotIntegration.showWelcome(sessionId)
 *
 * // 根据模式切换
 * mascotIntegration.onModeChanged(sessionId, AgentMode.BURST)
 *
 * // 成功动画
 * mascotIntegration.playSuccess(sessionId)
 *
 * // 错误动画
 * mascotIntegration.playError(sessionId)
 * ```
 */
class MascotTerminalIntegration(
    private val sessionManager: TerminalSessionManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 每个会话的动画控制器。 */
    private val controllers = java.util.concurrent.ConcurrentHashMap<String, MascotAnimationController>()

    /**
     * 获取或创建会话的动画控制器。
     */
    fun getController(sessionId: String): MascotAnimationController {
        return controllers.computeIfAbsent(sessionId) {
            MascotAnimationController().also { it.start() }
        }
    }

    /**
     * 显示欢迎动画。
     *
     * 在终端中显示大号 Aura + 欢迎信息。
     */
    fun showWelcome(sessionId: String) {
        // 发送欢迎 ASCII 艺术
        for (line in AuraMascot.welcomeArt) {
            sessionManager.sendMessage(sessionId, TerminalMessage(
                type = TerminalMessageType.SYSTEM,
                source = com.ai.assistance.aiterminal.terminal.ui.MessageSource.SYSTEM,
                content = line
            ))
        }

        sessionManager.sendMessage(sessionId, TerminalMessage.divider())
        sessionManager.sendMessage(sessionId, TerminalMessage.info(
            "🪼 Aura 已就绪 — 深海极光终端助手"
        ))
        sessionManager.sendMessage(sessionId, TerminalMessage.info(
            "Aura 会根据终端状态变换形态，陪伴你的编程之旅"
        ))

        // 设置初始形态
        getController(sessionId).setForm(AuraMascot.AuraForm.IDLE)
    }

    /**
     * 当 Agent 模式变化时切换形态。
     */
    fun onModeChanged(sessionId: String, mode: AgentMode) {
        val form = when (mode) {
            AgentMode.NONE -> AuraMascot.AuraForm.IDLE
            AgentMode.SINGLE -> AuraMascot.AuraForm.THINKING
            AgentMode.MULTI -> AuraMascot.AuraForm.TYPING
            AgentMode.BURST -> AuraMascot.AuraForm.BERSERK
        }
        getController(sessionId).setForm(form)

        // 在终端显示形态变化
        val emoji = AuraMascot.getEmoji(form)
        sessionManager.sendMessage(sessionId, TerminalMessage.system(
            "$emoji Aura 变身: ${form.displayName} — ${form.description}"
        ))
    }

    /**
     * 当狂暴模式阶段变化时切换形态。
     */
    fun onBurstPhaseChanged(sessionId: String, phase: String) {
        val form = MascotStateMapper.fromBurstPhase(phase)
        getController(sessionId).setForm(form)
    }

    /**
     * 播放成功动画（一次性）。
     *
     * 显示 ✨ 成功形态后回到上一形态。
     */
    fun playSuccess(sessionId: String) {
        val controller = getController(sessionId)
        sessionManager.sendMessage(sessionId, TerminalMessage.success("🪼✨ Aura 很开心！"))
        controller.playOnce(AuraMascot.AuraForm.SUCCESS) {
            controller.revertForm()
        }
    }

    /**
     * 播放错误动画（一次性）。
     */
    fun playError(sessionId: String) {
        val controller = getController(sessionId)
        sessionManager.sendMessage(sessionId, TerminalMessage.warning("🪼💢 Aura 感到不安..."))
        controller.playOnce(AuraMascot.AuraForm.ERROR) {
            controller.revertForm()
        }
    }

    /**
     * 播放打字动画（Agent 正在输入时）。
     */
    fun playTyping(sessionId: String) {
        getController(sessionId).setForm(AuraMascot.AuraForm.TYPING)
    }

    /**
     * 播放思考动画。
     */
    fun playThinking(sessionId: String) {
        getController(sessionId).setForm(AuraMascot.AuraForm.THINKING)
    }

    /**
     * 回到空闲形态。
     */
    fun backToIdle(sessionId: String) {
        getController(sessionId).setForm(AuraMascot.AuraForm.IDLE)
    }

    /**
     * 进入休眠形态。
     */
    fun sleep(sessionId: String) {
        getController(sessionId).setForm(AuraMascot.AuraForm.SLEEPING)
        sessionManager.sendMessage(sessionId, TerminalMessage.info("🪼💤 Aura 进入休眠..."))
    }

    /**
     * 在终端中渲染当前帧。
     *
     * 将当前 ASCII 帧作为系统消息发送到终端。
     */
    fun renderFrame(sessionId: String) {
        val controller = getController(sessionId)
        val frame = controller.getCurrentFrame()
        for (line in frame) {
            sessionManager.sendMessage(sessionId, TerminalMessage(
                type = TerminalMessageType.SYSTEM,
                source = com.ai.assistance.aiterminal.terminal.ui.MessageSource.SYSTEM,
                content = line
            ))
        }
    }

    /**
     * 获取当前帧（供 UI 层渲染）。
     */
    fun getCurrentFrame(sessionId: String): List<String> {
        return getController(sessionId).getCurrentFrame()
    }

    /**
     * 获取当前形态。
     */
    fun getCurrentForm(sessionId: String): AuraMascot.AuraForm {
        return getController(sessionId).getCurrentForm()
    }

    /**
     * 获取当前 emoji。
     */
    fun getCurrentEmoji(sessionId: String): String {
        return getController(sessionId).getCurrentEmoji()
    }

    /**
     * 清理会话资源。
     */
    fun cleanup(sessionId: String) {
        controllers[sessionId]?.stop()
        controllers.remove(sessionId)
    }

    /**
     * 清理所有资源。
     */
    fun cleanupAll() {
        for ((_, controller) in controllers) {
            controller.stop()
        }
        controllers.clear()
        stateBinders.values.forEach { it.stop() }
        stateBinders.clear()
    }

    // ===== 真实状态源绑定 =====

    /** 每个会话的状态绑定器。 */
    private val stateBinders = java.util.concurrent.ConcurrentHashMap<String, MascotStateBinder>()

    /**
     * 绑定真实状态源,让吉祥物根据软件真实运行状态自动切换形态。
     *
     * 在终端会话启动 + 各模块就绪后调用一次。传入各模块的真实 StateFlow,
     * [MascotStateBinder] 会订阅并自动驱动形态切换。
     *
     * @param sessionId 会话 ID
     * @param sources 真实状态源集合(见 [MascotStateSources])
     */
    fun bindRealStateSources(sessionId: String, sources: MascotStateSources) {
        val controller = getController(sessionId)
        val binder = MascotStateBinder(controller, scope)
        binder.bind(
            kernelState = sources.kernelState,
            isBerserk = sources.isBerserk,
            isThinking = sources.isThinking,
            isExecuting = sources.isExecuting,
            hasError = sources.hasError,
            isIdle = sources.isIdle,
            isSleeping = sources.isSleeping,
            isRemembering = sources.isRemembering,
            isAnalyzing = sources.isAnalyzing,
            isLearning = sources.isLearning,
            isNetworking = sources.isNetworking,
            isRootActive = sources.isRootActive,
            isPlanning = sources.isPlanning,
            isCompiling = sources.isCompiling,
            isConnecting = sources.isConnecting,
            isTooling = sources.isTooling,
            isSkilling = sources.isSkilling,
            isMcping = sources.isMcping,
            isCollaborating = sources.isCollaborating,
        )
        binder.start()
        stateBinders[sessionId] = binder
    }

    /**
     * 解绑真实状态源(会话结束时调用)。
     */
    fun unbindRealStateSources(sessionId: String) {
        stateBinders.remove(sessionId)?.stop()
    }
}

/**
 * 真实状态源集合。
 *
 * 由调用方(DI / Activity / Service)从各软件模块收集真实 StateFlow 后传入。
 * 任一字段可为 null,表示该模块未接入或当前会话不关注。
 *
 * # 模块独立性
 *
 * 所有字段都是基础类型(Boolean / [MascotKernelState]),**不依赖任何外部模块类型**,
 * 保证 mascot 模块可独立编译。调用方负责把各模块真实类型映射成这些字段。
 *
 * # 真实来源参考(调用方在 app 层实现映射)
 *
 * | 字段 | 真实来源 | 映射方式 |
 * |------|---------|---------|
 * | kernelState | `BurstKernel.state` (core/burst-kernel) | `.map { it.toMascot() }` 见 MascotStateBinder 文档 |
 * | isBerserk | `BurstStateManager` phase == "berserk"/"extreme" | 直接 Boolean |
 * | isThinking | `AITerminalHelper` / `DialogManager` LLM 推理中标志 | 直接 Boolean |
 * | isExecuting | `TerminalSessionManager` 命令执行中标志 | 直接 Boolean |
 * | hasError | `TaskExecutor` / `ErrorAnalyzer` 最近错误标志 | 直接 Boolean |
 * | isIdle | 终端空闲(无执行无思考) | 直接 Boolean |
 * | isSleeping | 无活动超时 | 直接 Boolean |
 * | isRemembering | `MemoryStorageSkill` / `ShortTermMemory` 读写中 | 直接 Boolean |
 * | isAnalyzing | `CodeAnalyzer` 扫描中 | 直接 Boolean |
 * | isLearning | AI 推理中 | 直接 Boolean |
 * | isNetworking | `NetworkTool` 请求中 | 直接 Boolean |
 * | isRootActive | `ShizukuManager.hasShizukuPermission()` | 直接 Boolean |
 * | isPlanning | `TaskPlanner` 分解中 | 直接 Boolean |
 * | isCompiling | `CodeGenerator` 生成中 | 直接 Boolean |
 * | isConnecting | `PluginManager` / `IntegrationCenter` 连接中 | 直接 Boolean |
 * | isTooling | `ToolExecutor` / `ToolRegistry` 调用工具中 | 直接 Boolean |
 * | isSkilling | `SkillManager` / `BurstSkillContext` 加载技能中 | 直接 Boolean |
 * | isMcping | `McpModule` / `McpConfigGenerator` 接入 MCP 中 | 直接 Boolean |
 * | isCollaborating | `MultiAgentTerminalCoordinator` 多 Agent 模式 | 直接 Boolean |
 */
data class MascotStateSources(
    val kernelState: kotlinx.coroutines.flow.StateFlow<MascotKernelState>? = null,
    val isBerserk: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isThinking: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isExecuting: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val hasError: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isIdle: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isSleeping: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isRemembering: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isAnalyzing: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isLearning: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isNetworking: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isRootActive: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isPlanning: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isCompiling: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isConnecting: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isTooling: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isSkilling: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isMcping: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    val isCollaborating: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
)
