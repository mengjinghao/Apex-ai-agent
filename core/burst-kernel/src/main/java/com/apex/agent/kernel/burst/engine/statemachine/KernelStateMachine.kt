package com.apex.agent.kernel.burst.engine.statemachine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * E1: 内核状态机增强
 *
 * 增强现有 KernelState（6态）到 10 态完整状态机：
 * - 新增 INITIALIZING / DEGRADED / MAINTENANCE / FROZEN
 * - 状态转换校验（非法转换拒绝）
 * - 状态历史记录
 * - 状态变更监听器
 */
class KernelStateMachine {

    enum class KernelState {
        STOPPED,        // 已停止
        INITIALIZING,   // 初始化中（新）
        STARTING,       // 启动中
        RUNNING,        // 运行中
        DEGRADED,       // 降级运行（新）
        PAUSED,         // 已暂停
        FROZEN,         // 冻结（新，不接受新任务）
        MAINTENANCE,    // 维护模式（新）
        STOPPING,       // 停止中
        ERROR           // 错误
    }

    data class StateTransition(
        val from: KernelState,
        val to: KernelState,
        val timestamp: Long,
        val reason: String,
        val metadata: Map<String, Any> = emptyMap()
    )

    data class StateContext(
        val state: KernelState,
        val enteredAt: Long,
        val transitions: List<StateTransition>,
        val timeInStateMs: Long
    )

    private val validTransitions = mapOf(
        KernelState.STOPPED to setOf(KernelState.INITIALIZING),
        KernelState.INITIALIZING to setOf(KernelState.STARTING, KernelState.ERROR, KernelState.STOPPED),
        KernelState.STARTING to setOf(KernelState.RUNNING, KernelState.ERROR, KernelState.STOPPING),
        KernelState.RUNNING to setOf(KernelState.PAUSED, KernelState.DEGRADED, KernelState.FROZEN, KernelState.MAINTENANCE, KernelState.STOPPING, KernelState.ERROR),
        KernelState.DEGRADED to setOf(KernelState.RUNNING, KernelState.PAUSED, KernelState.FROZEN, KernelState.STOPPING, KernelState.ERROR),
        KernelState.PAUSED to setOf(KernelState.RUNNING, KernelState.STOPPING, KernelState.ERROR),
        KernelState.FROZEN to setOf(KernelState.RUNNING, KernelState.STOPPING),
        KernelState.MAINTENANCE to setOf(KernelState.RUNNING, KernelState.STOPPING),
        KernelState.STOPPING to setOf(KernelState.STOPPED),
        KernelState.ERROR to setOf(KernelState.STOPPED, KernelState.STARTING)
    )

    private val _state = MutableStateFlow(KernelState.STOPPED)
    val state: StateFlow<KernelState> = _state.asStateFlow()

    private val _context = MutableStateFlow(StateContext(KernelState.STOPPED, System.currentTimeMillis(), emptyList(), 0))
    val context: StateFlow<StateContext> = _context.asStateFlow()

    private val transitions = mutableListOf<StateTransition>()
    private val listeners = mutableListOf<(StateTransition) -> Unit>()
    private val stateEnteredAt = ConcurrentHashMap<KernelState, Long>()

    /**
     * 状态转换
     */
    fun transition(to: KernelState, reason: String = "", metadata: Map<String, Any> = emptyMap()): Boolean {
        val from = _state.value
        val allowed = validTransitions[from] ?: emptySet()
        if (to !in allowed && from != to) return false

        val transition = StateTransition(from, to, System.currentTimeMillis(), reason, metadata)
        transitions.add(transition)
        while (transitions.size > 500) transitions.removeAt(0)

        _state.value = to
        stateEnteredAt[to] = System.currentTimeMillis()

        val timeInState = stateEnteredAt[from]?.let { System.currentTimeMillis() - it } ?: 0
        _context.value = StateContext(to, System.currentTimeMillis(), transitions.toList(), timeInState)

        listeners.forEach { it(transition) }
        return true
    }

    /**
     * 添加监听器
     */
    fun addListener(listener: (StateTransition) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 是否可接受新任务
     */
    fun canAcceptTasks(): Boolean = _state.value in setOf(KernelState.RUNNING, KernelState.DEGRADED)

    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = _state.value in setOf(KernelState.RUNNING, KernelState.DEGRADED, KernelState.PAUSED)

    /**
     * 是否处于异常状态
     */
    fun isAbnormal(): Boolean = _state.value in setOf(KernelState.ERROR, KernelState.FROZEN, KernelState.MAINTENANCE)

    /**
     * 获取状态历史
     */
    fun getHistory(): List<StateTransition> = transitions.toList()

    /**
     * 获取状态统计
     */
    fun getStats(): StateMachineStats {
        val byState = transitions.groupingBy { it.to }.eachCount()
        val totalTime = transitions.zipWithNext { a, b -> b.timestamp - a.timestamp }
        return StateMachineStats(
            currentState = _state.value,
            totalTransitions = transitions.size,
            transitionsByTargetState = byState,
            avgTimeInStateMs = if (totalTime.isNotEmpty()) totalTime.average().toLong() else 0
        )
    }

    data class StateMachineStats(
        val currentState: KernelState,
        val totalTransitions: Int,
        val transitionsByTargetState: Map<KernelState, Int>,
        val avgTimeInStateMs: Long
    )
}
