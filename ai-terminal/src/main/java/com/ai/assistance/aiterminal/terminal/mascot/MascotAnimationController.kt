package com.ai.assistance.aiterminal.terminal.mascot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 吉祥物动画状态。
 */
data class MascotAnimationState(
    val form: AuraMascot.AuraForm = AuraMascot.AuraForm.IDLE,
    val currentFrameIndex: Int = 0,
    val isPlaying: Boolean = true,
    val loopCount: Int = 0
) {
    /** 当前帧的 ASCII 行。 */
    val currentFrame: List<String>
        get() = AuraMascot.getFrames(form).getOrNull(currentFrameIndex) ?: emptyList()

    /** 总帧数。 */
    val frameCount: Int
        get() = AuraMascot.getFrameCount(form)

    /** 动画间隔（毫秒）。 */
    val intervalMs: Long
        get() = AuraMascot.getAnimationIntervalMs(form)

    /** Emoji 表示。 */
    val emoji: String
        get() = AuraMascot.getEmoji(form)
}

/**
 * 吉祥物动画控制器。
 *
 * 管理 Aura 吉祥物的动画播放：
 * - 自动循环播放当前形态的帧
 * - 支持形态切换（带过渡动画）
 * - 支持暂停/恢复
 * - 支持"一次性"动画（执行完后回到上一形态）
 *
 * # 使用示例
 *
 * ```
 * val controller = MascotAnimationController()
 * controller.start()
 *
 * // 切换形态
 * controller.setForm(AuraMascot.AuraForm.THINKING)
 *
 * // 播放一次性动画后回到 IDLE
 * controller.playOnce(AuraMascot.AuraForm.SUCCESS) {
 *     controller.setForm(AuraMascot.AuraForm.IDLE)
 * }
 *
 * // 观察状态
 * controller.state.collect { state ->
 *     render(state.currentFrame)
 * }
 * ```
 */
class MascotAnimationController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _state = MutableStateFlow(MascotAnimationState())
    val state: StateFlow<MascotAnimationState> = _state.asStateFlow()

    private var animationJob: Job? = null
    private var previousForm: AuraMascot.AuraForm = AuraMascot.AuraForm.IDLE

    /**
     * 开始动画播放。
     */
    fun start() {
        if (animationJob?.isActive == true) return

        animationJob = scope.launch {
            while (_state.value.isPlaying) {
                val current = _state.value
                val frames = AuraMascot.getFrames(current.form)
                if (frames.isNotEmpty()) {
                    val nextIndex = (current.currentFrameIndex + 1) % frames.size
                    val newLoopCount = if (nextIndex == 0) current.loopCount + 1 else current.loopCount

                    _state.value = current.copy(
                        currentFrameIndex = nextIndex,
                        loopCount = newLoopCount
                    )

                    // 检查一次性动画是否完成
                    if (oneShotCallback != null && nextIndex == 0 && current.loopCount >= 0) {
                        val callback = oneShotCallback!!
                        oneShotCallback = null
                        callback()
                    }
                }
                delay(current.intervalMs)
            }
        }
    }

    /**
     * 暂停动画。
     */
    fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
        animationJob?.cancel()
    }

    /**
     * 恢复动画。
     */
    fun resume() {
        _state.value = _state.value.copy(isPlaying = true)
        start()
    }

    /**
     * 停止动画。
     */
    fun stop() {
        animationJob?.cancel()
        animationJob = null
        _state.value = MascotAnimationState(isPlaying = false)
    }

    /**
     * 设置形态。
     *
     * @param form 目标形态
     * @param resetFrame 是否重置帧索引到 0
     */
    fun setForm(form: AuraMascot.AuraForm, resetFrame: Boolean = true) {
        previousForm = _state.value.form
        _state.value = _state.value.copy(
            form = form,
            currentFrameIndex = if (resetFrame) 0 else _state.value.currentFrameIndex,
            loopCount = 0
        )
    }

    /**
     * 播放一次性动画。
     *
     * 播放指定形态的动画一个完整循环后，调用回调。
     * 通常在回调中切回之前的形态。
     *
     * @param form 要播放的形态
     * @param onComplete 完成回调
     */
    private var oneShotCallback: (() -> Unit)? = null

    fun playOnce(form: AuraMascot.AuraForm, onComplete: () -> Unit) {
        oneShotCallback = onComplete
        setForm(form)
    }

    /**
     * 切回上一个形态。
     */
    fun revertForm() {
        setForm(previousForm)
    }

    /**
     * 获取当前帧的 ASCII 文本。
     */
    fun getCurrentFrame(): List<String> {
        return _state.value.currentFrame
    }

    /**
     * 获取当前形态。
     */
    fun getCurrentForm(): AuraMascot.AuraForm {
        return _state.value.form
    }

    /**
     * 获取当前 emoji。
     */
    fun getCurrentEmoji(): String {
        return _state.value.emoji
    }

    /**
     * 根据终端状态自动切换形态。
     *
     * @param isThinking 是否在思考
     * @param isExecuting 是否在执行
     * @param isBerserk 是否在狂暴模式
     * @param hasError 是否有错误
     * @param isIdle 是否空闲
     */
    fun autoSwitch(
        isThinking: Boolean = false,
        isExecuting: Boolean = false,
        isBerserk: Boolean = false,
        hasError: Boolean = false,
        isIdle: Boolean = false,
        isSleeping: Boolean = false
    ) {
        when {
            isBerserk -> setForm(AuraMascot.AuraForm.BERSERK)
            hasError -> setForm(AuraMascot.AuraForm.ERROR)
            isExecuting -> setForm(AuraMascot.AuraForm.EXECUTING)
            isThinking -> setForm(AuraMascot.AuraForm.THINKING)
            isSleeping -> setForm(AuraMascot.AuraForm.SLEEPING)
            isIdle -> setForm(AuraMascot.AuraForm.IDLE)
        }
    }
}

/**
 * 吉祥物状态映射器。
 *
 * 将终端/Agent/狂暴模式状态映射为吉祥物形态。
 */
object MascotStateMapper {

    /**
     * 根据终端输入模式映射。
     */
    fun fromInputMode(mode: String): AuraMascot.AuraForm = when (mode.lowercase()) {
        "shell" -> AuraMascot.AuraForm.IDLE
        "agent" -> AuraMascot.AuraForm.THINKING
        "burst" -> AuraMascot.AuraForm.BERSERK
        else -> AuraMascot.AuraForm.IDLE
    }

    /**
     * 根据狂暴模式阶段映射。
     */
    fun fromBurstPhase(phase: String): AuraMascot.AuraForm = when (phase.lowercase()) {
        "idle", "stopped" -> AuraMascot.AuraForm.IDLE
        "initializing" -> AuraMascot.AuraForm.THINKING
        "thinking" -> AuraMascot.AuraForm.THINKING
        "executing", "running", "active" -> AuraMascot.AuraForm.EXECUTING
        "optimizing" -> AuraMascot.AuraForm.TYPING
        "completed" -> AuraMascot.AuraForm.SUCCESS
        "failed", "error" -> AuraMascot.AuraForm.ERROR
        "paused" -> AuraMascot.AuraForm.SLEEPING
        "berserk", "extreme" -> AuraMascot.AuraForm.BERSERK
        else -> AuraMascot.AuraForm.IDLE
    }

    /**
     * 根据 Agent 模式映射。
     */
    fun fromAgentMode(mode: String): AuraMascot.AuraForm = when (mode.lowercase()) {
        "none", "standard" -> AuraMascot.AuraForm.IDLE
        "single" -> AuraMascot.AuraForm.THINKING
        "multi" -> AuraMascot.AuraForm.TYPING
        "burst" -> AuraMascot.AuraForm.BERSERK
        else -> AuraMascot.AuraForm.IDLE
    }

    /**
     * 根据真实功能状态快照映射形态(由 MascotStateBinder 调用)。
     *
     * 优先级(从高到低):
     *  1. ERROR       — 错误最优先
     *  2. BERSERK     — 狂暴模式
     *  3. 功能态      — REMEMBERING/ANALYZING/LEARNING/NETWORKING/ROOT/PLANNING/COMPILING/CONNECTING
     *  4. COLLABORATING — 多 Agent 协作
     *  5. EXECUTING   — 执行中
     *  6. THINKING    — 思考中
     *  7. SLEEPING    — 休眠
     *  8. IDLE        — 空闲
     *
     * @param st 真实功能状态快照
     */
    fun fromFunctionalState(st: com.ai.assistance.aiterminal.terminal.mascot.MascotFunctionalState): AuraMascot.AuraForm = when {
        st.hasError -> AuraMascot.AuraForm.ERROR
        st.isBerserk -> AuraMascot.AuraForm.BERSERK
        st.isCompiling -> AuraMascot.AuraForm.COMPILING
        st.isAnalyzing -> AuraMascot.AuraForm.ANALYZING
        st.isLearning -> AuraMascot.AuraForm.LEARNING
        st.isRemembering -> AuraMascot.AuraForm.REMEMBERING
        st.isPlanning -> AuraMascot.AuraForm.PLANNING
        st.isNetworking -> AuraMascot.AuraForm.NETWORKING
        st.isConnecting -> AuraMascot.AuraForm.CONNECTING
        st.isTooling -> AuraMascot.AuraForm.TOOLING
        st.isSkilling -> AuraMascot.AuraForm.SKILLING
        st.isMcping -> AuraMascot.AuraForm.MCPING
        st.isRootActive -> AuraMascot.AuraForm.ROOT
        st.isCollaborating -> AuraMascot.AuraForm.COLLABORATING
        st.isExecuting -> AuraMascot.AuraForm.EXECUTING
        st.isThinking -> AuraMascot.AuraForm.THINKING
        st.isSleeping -> AuraMascot.AuraForm.SLEEPING
        st.isIdle -> AuraMascot.AuraForm.IDLE
        else -> AuraMascot.AuraForm.IDLE
    }
}
