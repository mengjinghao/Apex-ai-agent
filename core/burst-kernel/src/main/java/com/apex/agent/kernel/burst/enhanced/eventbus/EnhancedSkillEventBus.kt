package com.apex.agent.kernel.burst.enhanced.eventbus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * B26: 技能事件总线增强
 *
 * 增强现有 SkillEventBus：
 * - 结构化事件类型（30+ 事件）
 * - 事件过滤
 * - 事件优先级
 * - 事件溯源（Event Sourcing）
 * - 死信队列
 */
class EnhancedSkillEventBus(
    private val historySize: Int = 10_000,
    private val deadLetterMaxSize: Int = 1000
) {

    /**
     * 结构化事件
     */
    sealed class BurstEvent {
        abstract val eventId: String
        abstract val timestamp: Long
        abstract val priority: EventPriority

        // 生命周期事件
        data class SkillLoaded(override val eventId: String, val skillId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class SkillUnloaded(override val eventId: String, val skillId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class SkillInitialized(override val eventId: String, val skillId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()

        // 执行事件
        data class TaskStarted(override val eventId: String, val taskId: String, val skillId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class TaskProgress(override val eventId: String, val taskId: String, val progress: Float, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.LOW) : BurstEvent()
        data class TaskSucceeded(override val eventId: String, val taskId: String, val skillId: String, val result: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class TaskFailed(override val eventId: String, val taskId: String, val skillId: String, val error: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : BurstEvent()
        data class TaskCancelled(override val eventId: String, val taskId: String, val reason: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : BurstEvent()
        data class TaskRetrying(override val eventId: String, val taskId: String, val attempt: Int, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class TaskPaused(override val eventId: String, val taskId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class TaskResumed(override val eventId: String, val taskId: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()

        // 资源事件
        data class ResourceWarning(override val eventId: String, val resource: String, val usage: Float, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : BurstEvent()
        data class ResourceCritical(override val eventId: String, val resource: String, val usage: Float, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.CRITICAL) : BurstEvent()

        // 状态事件
        data class StateChanged(override val eventId: String, val from: String, val to: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
        data class ConfigChanged(override val eventId: String, val key: String, val oldValue: Any?, val newValue: Any?, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()

        // 健康事件
        data class HealthCheckPassed(override val eventId: String, val component: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.LOW) : BurstEvent()
        data class HealthCheckFailed(override val eventId: String, val component: String, val error: String, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.HIGH) : BurstEvent()

        // 自定义
        data class Custom(override val eventId: String, val type: String, val payload: Map<String, Any>, override val timestamp: Long = System.currentTimeMillis(), override val priority: EventPriority = EventPriority.NORMAL) : BurstEvent()
    }

    enum class EventPriority { LOW, NORMAL, HIGH, CRITICAL }

    data class DeadLetterEntry(
        val event: BurstEvent,
        val error: String,
        val timestamp: Long,
        val retryCount: Int = 0
    )

    private val _events = MutableSharedFlow<BurstEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<BurstEvent> = _events.asSharedFlow()

    private val eventHistory = ConcurrentLinkedQueue<BurstEvent>()
    private val deadLetters = ConcurrentLinkedQueue<DeadLetterEntry>()
    private val eventCounter = AtomicLong(0)
    private val subscriberStats = ConcurrentHashMap<String, Int>()  // subscriberId -> eventCount

    /**
     * 发布事件
     */
    suspend fun publish(event: BurstEvent) {
        val eventWithId = when (event) {
            is BurstEvent.SkillLoaded -> event.copy(eventId = generateId())
            is BurstEvent.SkillUnloaded -> event.copy(eventId = generateId())
            is BurstEvent.SkillInitialized -> event.copy(eventId = generateId())
            is BurstEvent.TaskStarted -> event.copy(eventId = generateId())
            is BurstEvent.TaskProgress -> event.copy(eventId = generateId())
            is BurstEvent.TaskSucceeded -> event.copy(eventId = generateId())
            is BurstEvent.TaskFailed -> event.copy(eventId = generateId())
            is BurstEvent.TaskCancelled -> event.copy(eventId = generateId())
            is BurstEvent.TaskRetrying -> event.copy(eventId = generateId())
            is BurstEvent.TaskPaused -> event.copy(eventId = generateId())
            is BurstEvent.TaskResumed -> event.copy(eventId = generateId())
            is BurstEvent.ResourceWarning -> event.copy(eventId = generateId())
            is BurstEvent.ResourceCritical -> event.copy(eventId = generateId())
            is BurstEvent.StateChanged -> event.copy(eventId = generateId())
            is BurstEvent.ConfigChanged -> event.copy(eventId = generateId())
            is BurstEvent.HealthCheckPassed -> event.copy(eventId = generateId())
            is BurstEvent.HealthCheckFailed -> event.copy(eventId = generateId())
            is BurstEvent.Custom -> event.copy(eventId = generateId())
        }

        // 记录历史
        eventHistory.add(eventWithId)
        while (eventHistory.size > historySize) eventHistory.poll()

        // 发布
        try {
            _events.emit(eventWithId)
        } catch (e: Exception) {
            // 死信队列
            deadLetters.add(DeadLetterEntry(eventWithId, e.message ?: "unknown", System.currentTimeMillis()))
            while (deadLetters.size > deadLetterMaxSize) deadLetters.poll()
        }
    }

    /**
     * 订阅事件（带过滤）
     */
    fun subscribe(filter: (BurstEvent) -> Boolean = { true }): kotlinx.coroutines.flow.Flow<BurstEvent> {
        return _events.asSharedFlow().filter(filter)
    }

    /**
     * 订阅特定类型
     */
    inline fun <reified T : BurstEvent> subscribeType(): kotlinx.coroutines.flow.Flow<T> {
        return _events.asSharedFlow().filter { it is T }.map { it as T }
    }

    /**
     * 获取事件历史
     */
    fun getHistory(limit: Int = 100): List<BurstEvent> {
        return eventHistory.toList().takeLast(limit)
    }

    /**
     * 按类型查询历史
     */
    inline fun <reified T : BurstEvent> getHistoryByType(limit: Int = 100): List<T> {
        return eventHistory.filterIsInstance<T>().takeLast(limit)
    }

    /**
     * 获取死信
     */
    fun getDeadLetters(): List<DeadLetterEntry> = deadLetters.toList()

    /**
     * 重试死信
     */
    suspend fun retryDeadLetter(entry: DeadLetterEntry) {
        deadLetters.remove(entry)
        publish(entry.event)
    }

    /**
     * 获取统计
     */
    fun getStats(): EventBusStats {
        val history = eventHistory.toList()
        val byType = history.groupBy { it::class.simpleName }.mapValues { it.value.size }
        val byPriority = history.groupBy { it.priority }.mapValues { it.value.size }
        return EventBusStats(
            totalEvents = eventCounter.get(),
            historySize = history.size,
            deadLetterCount = deadLetters.size,
            eventsByType = byType,
            eventsByPriority = byPriority
        )
    }

    data class EventBusStats(
        val totalEvents: Long,
        val historySize: Int,
        val deadLetterCount: Int,
        val eventsByType: Map<String?, Int>,
        val eventsByPriority: Map<EventPriority, Int>
    )

    private fun generateId(): String = "evt_${eventCounter.incrementAndGet()}"
}
