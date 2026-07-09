package com.apex.agent.kernel.burst.engine.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.map

/**
 * E6: 内核事件系统
 *
 * 内核级事件发布/订阅：
 * - 20+ 内核事件类型
 * - 事件优先级
 * - 事件过滤
 * - 事件溯源
 */
class KernelEventSystem {

    sealed class KernelEvent {
        abstract val id: String
        abstract val timestamp: Long
        abstract val priority: EventPriority

        // 生命周期事件
        data class KernelStarted(override val id: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()
        data class KernelStopped(override val id: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()
        data class KernelPaused(override val id: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
        data class KernelResumed(override val id: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
        data class KernelDegraded(override val id: String, val reason: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()
        data class KernelError(override val id: String, val error: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.CRITICAL) : KernelEvent()

        // 配置事件
        data class ConfigChanged(override val id: String, val key: String, val oldValue: Any?, val newValue: Any?, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()

        // 执行事件
        data class TaskAccepted(override val id: String, val taskId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
        data class TaskRejected(override val id: String, val taskId: String, val reason: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()

        // 资源事件
        data class ResourceWarning(override val id: String, val resource: String, val usage: Float, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()
        data class ResourceCritical(override val id: String, val resource: String, val usage: Float, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.CRITICAL) : KernelEvent()

        // 插件事件
        data class PluginLoaded(override val id: String, val pluginId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
        data class PluginUnloaded(override val id: String, val pluginId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
        data class PluginError(override val id: String, val pluginId: String, val error: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : KernelEvent()

        // 自定义
        data class Custom(override val id: String, val type: String, val payload: Map<String, Any>, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : KernelEvent()
    }

    enum class EventPriority { LOW, NORMAL, HIGH, CRITICAL }

    data class EventStats(
        val totalEvents: Long,
        val eventsByType: Map<String, Int>,
        val eventsByPriority: Map<EventPriority, Int>
    )

    private val _events = MutableSharedFlow<KernelEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<KernelEvent> = _events.asSharedFlow()

    private val eventHistory = mutableListOf<KernelEvent>()
    private val eventCounter = AtomicLong(0)
    private val eventTypeCounts = ConcurrentHashMap<String, Int>()
    private val eventPriorityCounts = ConcurrentHashMap<EventPriority, Int>()

    suspend fun publish(event: KernelEvent) {
        val id = "kevt_${eventCounter.incrementAndGet()}"
        val eventWithId = when (event) {
            is KernelEvent.KernelStarted -> event.copy(id = id)
            is KernelEvent.KernelStopped -> event.copy(id = id)
            is KernelEvent.KernelPaused -> event.copy(id = id)
            is KernelEvent.KernelResumed -> event.copy(id = id)
            is KernelEvent.KernelDegraded -> event.copy(id = id)
            is KernelEvent.KernelError -> event.copy(id = id)
            is KernelEvent.ConfigChanged -> event.copy(id = id)
            is KernelEvent.TaskAccepted -> event.copy(id = id)
            is KernelEvent.TaskRejected -> event.copy(id = id)
            is KernelEvent.ResourceWarning -> event.copy(id = id)
            is KernelEvent.ResourceCritical -> event.copy(id = id)
            is KernelEvent.PluginLoaded -> event.copy(id = id)
            is KernelEvent.PluginUnloaded -> event.copy(id = id)
            is KernelEvent.PluginError -> event.copy(id = id)
            is KernelEvent.Custom -> event.copy(id = id)
        }

        eventHistory.add(eventWithId)
        while (eventHistory.size > 1000) eventHistory.removeAt(0)

        val typeName = eventWithId::class.simpleName ?: "Unknown"
        eventTypeCounts[typeName] = (eventTypeCounts[typeName] ?: 0) + 1
        eventPriorityCounts[eventWithId.priority] = (eventPriorityCounts[eventWithId.priority] ?: 0) + 1

        _events.emit(eventWithId)
    }

    fun <T : KernelEvent> subscribe(type: Class<T>): kotlinx.coroutines.flow.Flow<T> {
        return _events.asSharedFlow().filter { type.isInstance(it) }.map { @Suppress("UNCHECKED_CAST") it as T }
    }

    fun subscribe(predicate: (KernelEvent) -> Boolean): kotlinx.coroutines.flow.Flow<KernelEvent> {
        return _events.asSharedFlow().filter(predicate)
    }

    fun getHistory(limit: Int = 100): List<KernelEvent> = eventHistory.takeLast(limit)

    fun getStats(): EventStats {
        return EventStats(
            totalEvents = eventCounter.get(),
            eventsByType = eventTypeCounts.toMap(),
            eventsByPriority = eventPriorityCounts.toMap()
        )
    }

    fun clearHistory() {
        eventHistory.clear()
        eventTypeCounts.clear()
        eventPriorityCounts.clear()
    }
}

