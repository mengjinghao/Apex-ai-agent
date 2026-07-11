package com.ai.assistance.aiterminal.terminal.burst

import com.ai.assistance.aiterminal.terminal.multiagent.MultiAgentTerminalAdapter
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole
import com.ai.assistance.aiterminal.terminal.ui.TerminalMessage
import com.ai.assistance.aiterminal.terminal.ui.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 狂暴模式终端集成。
 *
 * 将狂暴模式（BurstMode）与终端 UI 桥接：
 * - 实时显示执行进度和状态
 * - 多策略推理过程可视化
 * - GA 演化优化进度
 * - 技能执行结果展示
 * - 错误和恢复信息
 *
 * # 使用示例
 *
 * ```
 * val burst = BurstTerminalIntegration(sessionManager, multiAgentAdapter)
 *
 * // 开始狂暴模式任务
 * burst.startTask(sessionId, "分析这段代码的 bug", strategies = listOf("CoT", "ToT"))
 *
 * // 更新进度
 * burst.updateProgress(sessionId, 0.5f, "Chain-of-Thought 推理中...")
 *
 * // 显示推理步骤
 * burst.reasoningStep(sessionId, "步骤 1: 识别变量类型")
 * burst.reasoningStep(sessionId, "步骤 2: 检查空指针")
 *
 * // 完成任务
 * burst.completeTask(sessionId, success = true, "发现 3 个潜在 bug")
 * ```
 */
class BurstTerminalIntegration(
    private val sessionManager: TerminalSessionManager,
    private val multiAgentAdapter: MultiAgentTerminalAdapter
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 狂暴模式终端状态。 */
    private val _burstStates = MutableStateFlow<Map<String, BurstTerminalState>>(emptyMap())
    val burstStates: StateFlow<Map<String, BurstTerminalState>> = _burstStates.asStateFlow()

    /**
     * 狂暴模式终端状态。
     */
    data class BurstTerminalState(
        val sessionId: String,
        val taskDescription: String = "",
        val phase: BurstPhase = BurstPhase.IDLE,
        val progress: Float = 0f,
        val strategies: List<String> = emptyList(),
        val currentStrategy: String? = null,
        val reasoningSteps: List<String> = emptyList(),
        val startTime: Long = 0,
        val endTime: Long = 0
    ) {
        val elapsedMs: Long
            get() = (if (endTime > 0) endTime else System.currentTimeMillis()) - startTime

        val isActive: Boolean
            get() = phase == BurstPhase.EXECUTING || phase == BurstPhase.THINKING
    }

    /**
     * 狂暴模式阶段。
     */
    enum class BurstPhase(val displayName: String, val icon: String) {
        IDLE("空闲", "⏳"),
        INITIALIZING("初始化", "🔧"),
        THINKING("推理中", "🧠"),
        EXECUTING("执行中", "⚡"),
        OPTIMIZING("优化中", "📈"),
        COMPLETED("已完成", "✅"),
        FAILED("失败", "❌"),
        PAUSED("已暂停", "⏸️")
    }

    /**
     * 开始狂暴模式任务。
     *
     * @param sessionId 会话 ID
     * @param taskDescription 任务描述
     * @param strategies 使用的推理策略列表
     */
    fun startTask(
        sessionId: String,
        taskDescription: String,
        strategies: List<String> = listOf("Chain-of-Thought")
    ) {
        // 启用狂暴模式
        multiAgentAdapter.enableBurstMode(sessionId)

        val state = BurstTerminalState(
            sessionId = sessionId,
            taskDescription = taskDescription,
            phase = BurstPhase.INITIALIZING,
            strategies = strategies,
            startTime = System.currentTimeMillis()
        )
        updateState(sessionId) { state }

        // 终端显示
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("🔥 任务启动"))
        sessionManager.sendMessage(sessionId, TerminalMessage.burst("📋 任务: $taskDescription"))
        sessionManager.sendMessage(sessionId, TerminalMessage.burst(
            "🎯 策略: ${strategies.joinToString(" + ")}"
        ))

        // 切换到思考阶段
        scope.launch {
            delay(500)
            transitionPhase(sessionId, BurstPhase.THINKING)
        }
    }

    /**
     * 更新进度。
     *
     * @param sessionId 会话 ID
     * @param progress 0..1
     * @param message 进度描述
     */
    fun updateProgress(sessionId: String, progress: Float, message: String? = null) {
        updateState(sessionId) { it.copy(progress = progress) }
        if (message != null) {
            val percent = (progress * 100).toInt()
            sessionManager.sendMessage(sessionId, TerminalMessage.burst("📊 [$percent%] $message"))
        }
    }

    /**
     * 添加推理步骤。
     *
     * @param sessionId 会话 ID
     * @param step 步骤描述
     */
    fun reasoningStep(sessionId: String, step: String) {
        updateState(sessionId) { it.copy(reasoningSteps = it.reasoningSteps + step) }
        sessionManager.sendMessage(sessionId, TerminalMessage.burst("🧠 $step"))
    }

    /**
     * 切换策略。
     *
     * @param sessionId 会话 ID
     * @param strategy 新策略名
     */
    fun switchStrategy(sessionId: String, strategy: String) {
        updateState(sessionId) { it.copy(currentStrategy = strategy) }
        sessionManager.sendMessage(sessionId, TerminalMessage.burst("🔄 切换策略: $strategy"))
    }

    /**
     * 显示 GA 演化优化进度。
     *
     * @param sessionId 会话 ID
     * @param generation 当前代数
     * @param maxGenerations 最大代数
     * @param bestFitness 最佳适应度
     */
    fun evolutionProgress(
        sessionId: String,
        generation: Int,
        maxGenerations: Int,
        bestFitness: Double
    ) {
        transitionPhase(sessionId, BurstPhase.OPTIMIZING)
        sessionManager.sendMessage(sessionId, TerminalMessage.burst(
            "📈 GA 演化: 第 $generation/$maxGenerations 代, 最佳适应度: ${"%.4f".format(bestFitness)}"
        ))
    }

    /**
     * 显示 Agent 协作。
     *
     * @param sessionId 会话 ID
     * @param agentId Agent ID
     * @param role Agent 角色
     * @param message 消息
     */
    fun agentContribution(
        sessionId: String,
        agentId: String,
        role: TerminalAgentRole,
        message: String
    ) {
        multiAgentAdapter.agentMessage(sessionId, agentId, "${role.icon} $message", isMultiAgent = true)
    }

    /**
     * 完成任务。
     *
     * @param sessionId 会话 ID
     * @param success 是否成功
     * @param result 结果描述
     */
    fun completeTask(sessionId: String, success: Boolean, result: String) {
        val endTime = System.currentTimeMillis()
        updateState(sessionId) {
            it.copy(
                phase = if (success) BurstPhase.COMPLETED else BurstPhase.FAILED,
                progress = 1f,
                endTime = endTime
            )
        }

        val state = _burstStates.value[sessionId]
        val elapsed = state?.elapsedMs ?: 0
        val elapsedStr = formatElapsed(elapsed)

        sessionManager.sendMessage(sessionId, TerminalMessage.divider(
            if (success) "✅ 任务完成" else "❌ 任务失败"
        ))
        sessionManager.sendMessage(sessionId, TerminalMessage.burst(result))
        sessionManager.sendMessage(sessionId, TerminalMessage.info("⏱️ 耗时: $elapsedStr"))

        if (state != null && state.reasoningSteps.isNotEmpty()) {
            sessionManager.sendMessage(sessionId, TerminalMessage.info(
                "🧠 推理步骤: ${state.reasoningSteps.size} 步"
            ))
        }

        multiAgentAdapter.burstStatus(sessionId, if (success) "completed" else "failed")
    }

    /**
     * 暂停任务。
     */
    fun pauseTask(sessionId: String) {
        transitionPhase(sessionId, BurstPhase.PAUSED)
        sessionManager.sendMessage(sessionId, TerminalMessage.warning("⏸️ 任务已暂停"))
    }

    /**
     * 恢复任务。
     */
    fun resumeTask(sessionId: String) {
        transitionPhase(sessionId, BurstPhase.EXECUTING)
        sessionManager.sendMessage(sessionId, TerminalMessage.burst("▶️ 任务已恢复"))
    }

    /**
     * 显示错误。
     *
     * @param sessionId 会话 ID
     * @param error 错误信息
     * @param recoverable 是否可恢复
     */
    fun error(sessionId: String, error: String, recoverable: Boolean = true) {
        sessionManager.sendMessage(sessionId, TerminalMessage.error("🔥 狂暴模式错误: $error"))
        if (recoverable) {
            sessionManager.sendMessage(sessionId, TerminalMessage.warning("⚠️ 正在尝试恢复..."))
        } else {
            updateState(sessionId) { it.copy(phase = BurstPhase.FAILED) }
        }
    }

    /**
     * 获取会话的狂暴模式状态。
     */
    fun getState(sessionId: String): BurstTerminalState? {
        return _burstStates.value[sessionId]
    }

    /**
     * 重置状态。
     */
    fun reset(sessionId: String) {
        updateState(sessionId) {
            BurstTerminalState(sessionId = sessionId)
        }
    }

    // ===== 内部方法 =====

    private fun transitionPhase(sessionId: String, phase: BurstPhase) {
        updateState(sessionId) { it.copy(phase = phase) }
        sessionManager.setBurstState(sessionId, phase.name.lowercase())

        // 阶段切换通知
        if (phase != BurstPhase.IDLE && phase != BurstPhase.COMPLETED && phase != BurstPhase.FAILED) {
            sessionManager.sendMessage(sessionId, TerminalMessage.burst(
                "${phase.icon} 进入${phase.displayName}阶段"
            ))
        }
    }

    private fun updateState(sessionId: String, update: (BurstTerminalState) -> BurstTerminalState) {
        val current = _burstStates.value.toMutableMap()
        val existing = current[sessionId] ?: BurstTerminalState(sessionId = sessionId)
        current[sessionId] = update(existing)
        _burstStates.value = current
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
