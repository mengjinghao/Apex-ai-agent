package com.ai.assistance.aiterminal.terminal.mascot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 狂暴内核状态(mascot 模块自洽定义,避免硬依赖 :core:burst-kernel)。
 *
 * 由调用方(app/DI 层)从真实的 [com.apex.agent.kernel.burst.KernelState] 映射而来。
 * 这样 mascot 模块零外部模块依赖,可独立编译。
 */
enum class MascotKernelState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    ERROR;
}

/**
 * 真实功能状态快照 — 从软件各模块收集,驱动吉祥物形态切换。
 *
 * 这些字段对应软件真实运行状态,由 [MascotStateBinder] 从各模块 StateFlow 订阅填充。
 * 任何字段变化都会触发吉祥物形态重新映射。
 *
 * 注意:所有字段都是基础类型(Boolean / [MascotKernelState]),不依赖任何外部模块类型,
 * 保证 mascot 模块可独立编译。调用方负责把各模块真实 StateFlow 映射成这些字段。
 */
data class MascotFunctionalState(
    /** 狂暴模式内核状态(由调用方从 BurstKernel.state 映射)。 */
    val kernelState: MascotKernelState = MascotKernelState.STOPPED,
    /** 是否处于狂暴/极限模式(来自 BurstStateManager phase == "berserk"/"extreme")。 */
    val isBerserk: Boolean = false,
    /** 终端是否正在思考(LLM 推理中)。 */
    val isThinking: Boolean = false,
    /** 终端是否正在执行命令/任务。 */
    val isExecuting: Boolean = false,
    /** 最近一次执行是否出错。 */
    val hasError: Boolean = false,
    /** 终端是否空闲。 */
    val isIdle: Boolean = true,
    /** 终端是否休眠(无活动超时)。 */
    val isSleeping: Boolean = false,
    // ===== 功能扩展状态 =====
    /** 记忆系统正在读写(来自 MemoryStorageSkill / ShortTermMemory)。 */
    val isRemembering: Boolean = false,
    /** 正在进行代码分析/审计(来自 CodeAnalyzer / 分析类 Skill)。 */
    val isAnalyzing: Boolean = false,
    /** AI 正在推理。 */
    val isLearning: Boolean = false,
    /** 正在进行网络请求(来自 NetworkTool)。 */
    val isNetworking: Boolean = false,
    /** Shizuku/Root 权限已激活(来自 ShizukuManager.hasShizukuPermission)。 */
    val isRootActive: Boolean = false,
    /** 正在任务分解规划(来自 TaskPlanner)。 */
    val isPlanning: Boolean = false,
    /** 正在代码生成/编译(来自 CodeGenerator)。 */
    val isCompiling: Boolean = false,
    /** 正在连接 MCP/插件(来自 PluginManager / IntegrationCenter)。 */
    val isConnecting: Boolean = false,
    /** 正在调用工具(来自 ToolExecutor / ToolRegistry)。 */
    val isTooling: Boolean = false,
    /** 正在加载 Skills 技能(来自 SkillManager / BurstSkillContext)。 */
    val isSkilling: Boolean = false,
    /** 正在接入 MCP 服务(来自 McpModule / McpConfigGenerator)。 */
    val isMcping: Boolean = false,
    /** 多 Agent 协作模式激活。 */
    val isCollaborating: Boolean = false,
)

/**
 * 吉祥物真实状态绑定器。
 *
 * 订阅软件各模块的真实 StateFlow(已映射为基础类型),合并为 [MascotFunctionalState],
 * 并通过 [MascotStateMapper.fromFunctionalState] 映射为 [AuraMascot.AuraForm],
 * 自动驱动 [MascotAnimationController] 切换形态。
 *
 * # 模块独立性
 *
 * 本类只依赖 kotlinx.coroutines + 本模块内的类型,**不依赖任何外部模块**,
 * 保证 mascot 模块可独立编译。调用方(app/DI 层)负责:
 * 1. 收集各模块真实 StateFlow
 * 2. 把 `com.apex.agent.kernel.burst.KernelState` 等外部类型映射成 [MascotKernelState]
 * 3. 传入 [bind]
 *
 * # 使用示例
 *
 * ```
 * val binder = MascotStateBinder(controller)
 * binder.bind(
 *     kernelState = burstKernel.state.map { it.toMascotKernelState() }.stateIn(...),
 *     isRootActive = shizukuManager.permissionState,
 *     isThinking = aiService.thinkingState,
 *     // ...
 * )
 * binder.start()
 * ```
 *
 * 设计原则:优先级从高到低 — 错误 > 狂暴 > 功能态 > 协作 > 执行 > 思考 > 休眠 > 空闲。
 */
class MascotStateBinder(
    private val controller: MascotAnimationController,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _functionalState = MutableStateFlow(MascotFunctionalState())
    val functionalState: StateFlow<MascotFunctionalState> = _functionalState.asStateFlow()

    private var binderJob: Job? = null

    /**
     * 绑定真实状态源。各参数为软件模块的 StateFlow(已映射为基础类型),可为 null 表示未接入。
     * 任一状态变化都会更新 [functionalState] 并触发形态重映射。
     */
    fun bind(
        kernelState: StateFlow<MascotKernelState>? = null,
        isBerserk: StateFlow<Boolean>? = null,
        isThinking: StateFlow<Boolean>? = null,
        isExecuting: StateFlow<Boolean>? = null,
        hasError: StateFlow<Boolean>? = null,
        isIdle: StateFlow<Boolean>? = null,
        isSleeping: StateFlow<Boolean>? = null,
        isRemembering: StateFlow<Boolean>? = null,
        isAnalyzing: StateFlow<Boolean>? = null,
        isLearning: StateFlow<Boolean>? = null,
        isNetworking: StateFlow<Boolean>? = null,
        isRootActive: StateFlow<Boolean>? = null,
        isPlanning: StateFlow<Boolean>? = null,
        isCompiling: StateFlow<Boolean>? = null,
        isConnecting: StateFlow<Boolean>? = null,
        isTooling: StateFlow<Boolean>? = null,
        isSkilling: StateFlow<Boolean>? = null,
        isMcping: StateFlow<Boolean>? = null,
        isCollaborating: StateFlow<Boolean>? = null,
    ) {
        bindKernel(kernelState)
        bindBool(isBerserk) { st, v -> st.copy(isBerserk = v) }
        bindBool(isThinking) { st, v -> st.copy(isThinking = v) }
        bindBool(isExecuting) { st, v -> st.copy(isExecuting = v) }
        bindBool(hasError) { st, v -> st.copy(hasError = v) }
        bindBool(isIdle) { st, v -> st.copy(isIdle = v) }
        bindBool(isSleeping) { st, v -> st.copy(isSleeping = v) }
        bindBool(isRemembering) { st, v -> st.copy(isRemembering = v) }
        bindBool(isAnalyzing) { st, v -> st.copy(isAnalyzing = v) }
        bindBool(isLearning) { st, v -> st.copy(isLearning = v) }
        bindBool(isNetworking) { st, v -> st.copy(isNetworking = v) }
        bindBool(isRootActive) { st, v -> st.copy(isRootActive = v) }
        bindBool(isPlanning) { st, v -> st.copy(isPlanning = v) }
        bindBool(isCompiling) { st, v -> st.copy(isCompiling = v) }
        bindBool(isConnecting) { st, v -> st.copy(isConnecting = v) }
        bindBool(isTooling) { st, v -> st.copy(isTooling = v) }
        bindBool(isSkilling) { st, v -> st.copy(isSkilling = v) }
        bindBool(isMcping) { st, v -> st.copy(isMcping = v) }
        bindBool(isCollaborating) { st, v -> st.copy(isCollaborating = v) }
    }

    /** 绑定 KernelState 流(同时派生 isBerserk / isExecuting 等)。 */
    private fun bindKernel(src: StateFlow<MascotKernelState>?) {
        if (src == null) return
        scope.launch {
            src.collect { ks ->
                _functionalState.update { st ->
                    st.copy(
                        kernelState = ks,
                        // RUNNING 且非 ERROR 时视为执行中
                        isExecuting = ks == MascotKernelState.RUNNING && !st.isBerserk,
                        // ERROR 状态映射到 hasError
                        hasError = ks == MascotKernelState.ERROR,
                        // PAUSED 映射到休眠
                        isSleeping = ks == MascotKernelState.PAUSED,
                    )
                }
            }
        }
    }

    /** 绑定单个 Boolean StateFlow 到状态字段。 */
    private fun bindBool(src: StateFlow<Boolean>?, updater: (MascotFunctionalState, Boolean) -> MascotFunctionalState) {
        if (src == null) return
        scope.launch {
            src.collect { v ->
                _functionalState.update { updater(it, v) }
            }
        }
    }

    /**
     * 启动自动形态切换。订阅 [functionalState] 变化,映射为形态并驱动 controller。
     */
    fun start() {
        if (binderJob?.isActive == true) return
        binderJob = scope.launch {
            _functionalState.collect { st ->
                val form = MascotStateMapper.fromFunctionalState(st)
                if (controller.getCurrentForm() != form) {
                    controller.setForm(form)
                }
            }
        }
    }

    /** 停止自动切换(手动控制形态时调用)。 */
    fun stop() {
        binderJob?.cancel()
        binderJob = null
    }

    /** 手动触发一次性成功动画(任务完成后调用),完成后回到上一个形态。 */
    fun notifySuccess() {
        controller.playOnce(AuraMascot.AuraForm.SUCCESS) {
            controller.revertForm()
        }
    }

    /** 手动触发一次性错误动画。 */
    fun notifyError() {
        controller.playOnce(AuraMascot.AuraForm.ERROR) {
            controller.revertForm()
        }
    }
}

/**
 * 真实 KernelState → [MascotKernelState] 的映射由调用方(app/DI 层)负责。
 *
 * mascot 模块本身不依赖 :core:burst-kernel,保证可独立编译。
 * 调用方在 app 层写一个简单 when 映射即可,例如:
 *
 * ```
 * // 在 app 层(已依赖 :core:burst-kernel 和 :ai-terminal)
 * fun KernelState.toMascot(): MascotKernelState = when (this) {
 *     KernelState.STOPPED -> MascotKernelState.STOPPED
 *     KernelState.STARTING -> MascotKernelState.STARTING
 *     KernelState.RUNNING -> MascotKernelState.RUNNING
 *     KernelState.PAUSED -> MascotKernelState.PAUSED
 *     KernelState.STOPPING -> MascotKernelState.STOPPING
 *     KernelState.ERROR -> MascotKernelState.ERROR
 * }
 *
 * val mascotKernelFlow = burstKernel.state.map { it.toMascot() }
 *     .stateIn(scope, SharingStarted.Eagerly, MascotKernelState.STOPPED)
 *
 * integration.bindRealStateSources(sessionId, MascotStateSources(kernelState = mascotKernelFlow, ...))
 * ```
 */

/** MutableStateFlow.update 兼容扩展(Kotlin coroutines 1.6+ 自带,此处兜底)。 */
private inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        val prev = value
        val next = function(prev)
        if (compareAndSet(prev, next)) return
    }
}
