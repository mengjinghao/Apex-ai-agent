package com.apex.agent.core.patterns

import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 观察者模式 - 代理状态观察
 * 当 Agent 状态发生变化时，自动通知所有已订阅的观察者
 */

/** 订阅令牌，支持 AutoCloseable 自动取消订阅 */
class Subscription(
    val id: String = UUID.randomUUID().toString(),
    private val onClose: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
        val isActive: Boolean get() = !closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClose()
        }
    }
}

/** 可观察接口 */
interface Observable<T> {
    fun subscribe(observer: T): Subscription
    fun unsubscribe(observer: T)
        fun notifyObservers()
}

/** 线程安全的可观察实现 */
class ObservableImpl<T> : Observable<T> {

    private val observers = CopyOnWriteArrayList<T>()
        private val subscriptions = CopyOnWriteArrayList<Subscription>()

    override fun subscribe(observer: T): Subscription {
        observers.add(observer)
        val sub = Subscription(onClose = { unsubscribe(observer) })
        subscriptions.add(sub)
        return sub
    }

    override fun unsubscribe(observer: T) {
        observers.remove(observer)
        subscriptions.removeAll { !it.isActive }
    }

    override fun notifyObservers() {
        observers.forEach { /* 子类实现具体通知逻辑 */ }
    }
        fun getObservers(): List<T> = observers.toList()
        fun clear() {
        observers.clear()
        subscriptions.clear()
    }
}

/** Agent 生命周期事件 */
sealed class AgentLifecycleEvent {
    object Initialized : AgentLifecycleEvent()
    data class StateChanged(val from: String, val to: String) : AgentLifecycleEvent()
    data class ErrorOccurred(val error: String, val severity: String) : AgentLifecycleEvent()
    data class MetricsUpdated(val cpuUsage: Double, val memoryUsage: Long, val activeTasks: Int) : AgentLifecycleEvent()
        object Shutdown : AgentLifecycleEvent()
}

/** 代理状态观察者接口 */
interface AgentStateObserver {
    fun onStateChanged(event: AgentLifecycleEvent.StateChanged)
        fun onError(event: AgentLifecycleEvent.ErrorOccurred)
        fun onMetricsUpdate(event: AgentLifecycleEvent.MetricsUpdated)
        fun onInitialized() {}
        fun onShutdown() {}
}

/** Agent 状态可观察实现 */
class AgentStateObservable : ObservableImpl<AgentStateObserver>() {

    fun fireStateChanged(from: String, to: String) {
        val event = AgentLifecycleEvent.StateChanged(from, to)
        getObservers().forEach { it.onStateChanged(event) }
    }
        fun fireError(error: String, severity: String = "ERROR") {
        val event = AgentLifecycleEvent.ErrorOccurred(error, severity)
        getObservers().forEach { it.onError(event) }
    }
        fun fireMetricsUpdate(cpu: Double, memory: Long, tasks: Int) {
        val event = AgentLifecycleEvent.MetricsUpdated(cpu, memory, tasks)
        getObservers().forEach { it.onMetricsUpdate(event) }
    }
        fun fireInitialized() {
        getObservers().forEach { it.onInitialized() }
    }
        fun fireShutdown() {
        getObservers().forEach { it.onShutdown() }
    }
}
