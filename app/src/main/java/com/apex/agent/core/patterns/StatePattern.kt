package com.apex.agent.core.patterns

/**
 * 状态模式 - 有限状态机驱动的代理生命周期管理
 * 每个状态封装了事件处理逻辑、进入/退出动作和允许的转换
 */

/** 代理事件 */
sealed class AgentEvent {
    object Start : AgentEvent()
    object Complete : AgentEvent()
    object Wait : AgentEvent()
    object Fail : AgentEvent()
    object Recover : AgentEvent()
    object Timeout : AgentEvent()
    object Reset : AgentEvent()
    data class Custom(val type: String, val data: Any? = null) : AgentEvent()
}

/** 状态监听器 */
interface StateChangeListener {
    fun onStateChange(from: State, to: State, event: AgentEvent)
    fun onTimeout(state: State)
}

/** 抽象状态 */
abstract class State(val name: String) {
    /** 处理事件并返回下一个状态 */
    abstract fun handle(event: AgentEvent): State

    /** 进入状态时调用 */
    open fun enter() {}

    /** 退出状态时调用 */
    open fun exit() {}

    /** 允许的转换集合 */
    abstract fun getAllowedTransitions(): Set<State>

    /** 状态超时时间(ms)，-1 表示无超时 */
    open fun getTimeoutMs(): Long = -1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is State && this::class == other::class
    }

    override fun hashCode(): Int = this::class.hashCode()
    override fun toString(): String = name
}

/** 空闲状态 */
class IdleState : State("Idle") {
    override fun handle(event: AgentEvent): State = when (event) {
        AgentEvent.Start -> ProcessingState().also { enter(); it.enter() }
        AgentEvent.Reset -> this
        else -> this
    }

    override fun getAllowedTransitions(): Set<State> = setOf(ProcessingState())
    override fun enter() {}
}

/** 处理状态 */
class ProcessingState : State("Processing") {
    override fun handle(event: AgentEvent): State = when (event) {
        AgentEvent.Complete -> IdleState()
        AgentEvent.Wait -> WaitingState()
        AgentEvent.Fail -> ErrorState()
        AgentEvent.Timeout -> ErrorState()
        else -> this
    }

    override fun getAllowedTransitions(): Set<State> = setOf(IdleState(), WaitingState(), ErrorState())
    override fun getTimeoutMs(): Long = 30000
}

/** 等待状态 */
class WaitingState : State("Waiting") {
    override fun handle(event: AgentEvent): State = when (event) {
        AgentEvent.Recover -> ProcessingState()
        AgentEvent.Fail -> ErrorState()
        AgentEvent.Timeout -> ErrorState()
        else -> this
    }

    override fun getAllowedTransitions(): Set<State> = setOf(ProcessingState(), ErrorState())
    override fun getTimeoutMs(): Long = 60000
}

/** 错误状态 */
class ErrorState : State("Error") {
    override fun handle(event: AgentEvent): State = when (event) {
        AgentEvent.Recover -> RecoveryState()
        AgentEvent.Reset -> IdleState()
        else -> this
    }

    override fun getAllowedTransitions(): Set<State> = setOf(RecoveryState(), IdleState())
}

/** 恢复状态 */
class RecoveryState : State("Recovery") {
    private var retryCount = 0

    override fun handle(event: AgentEvent): State = when (event) {
        AgentEvent.Complete -> IdleState()
        AgentEvent.Fail -> {
            retryCount++
            if (retryCount >= 3) ErrorState() else this
        }
        AgentEvent.Timeout -> ErrorState()
        else -> this
    }

    override fun getAllowedTransitions(): Set<State> = setOf(IdleState(), ErrorState())
    override fun getTimeoutMs(): Long = 15000
    override fun enter() { retryCount = 0 }
}

/** 状态机 */
class StateMachine {
    private val history = ArrayDeque<State>()
    private val listeners = mutableListOf<StateChangeListener>()
    var currentState: State = IdleState()
        private set

    fun addListener(listener: StateChangeListener) { listeners.add(listener) }
    fun removeListener(listener: StateChangeListener) { listeners.remove(listener) }

    fun sendEvent(event: AgentEvent): State {
        val newState = currentState.handle(event)
        val allowed = currentState.getAllowedTransitions()
        if (newState != currentState && newState !in allowed) {
            throw IllegalStateException("Transition from ${currentState.name} to ${newState.name} not allowed")
        }
        if (newState != currentState) {
            currentState.exit()
            history.addLast(currentState)
            if (history.size > 10) history.removeFirst()
            currentState = newState
            currentState.enter()
            listeners.forEach { it.onStateChange(history.last(), currentState, event) }
        }
        return currentState
    }

    fun getHistory(): List<State> = history.toList()

    fun canTransitionTo(target: State): Boolean = target in currentState.getAllowedTransitions()
}
