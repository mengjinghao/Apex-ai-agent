package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.apex.agent.core.tools.skill.SkillCompleted
import com.apex.agent.core.tools.skill.SkillInvoked
import com.apex.agent.core.tools.skill.SkillLoaded

class SkillEventBus private constructor() {

    companion object {
        private const val TAG = "SkillEventBus"
        private const val MAX_EVENT_HISTORY = 1000
        private const val MAX_LISTENERS_PER_EVENT = 100

        @Volatile private var INSTANCE: SkillEventBus? = null

        fun getInstance(): SkillEventBus {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillEventBus().also { INSTANCE = it }
            }
        }
    }

    sealed class SkillEvent {
        abstract val eventId: String
        abstract val timestamp: Long
        abstract val source: String

        data class SkillLoaded(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val skillName: String,
            val loadDurationMs: Long
        ) : SkillEvent()

        data class SkillUnloaded(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val skillName: String
        ) : SkillEvent()

        data class SkillInvoked(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val skillName: String,
            val toolName: String? = null,
            val executionTimeMs: Long = 0
        ) : SkillEvent()

        data class SkillCompleted(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val skillName: String,
            val success: Boolean,
            val executionTimeMs: Long
        ) : SkillEvent()

        data class WorkflowTriggered(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val workflowId: String,
            val workflowName: String,
            val triggerType: String
        ) : SkillEvent()

        data class WorkflowNodeExecuted(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val workflowId: String,
            val nodeId: String,
            val nodeType: String,
            val success: Boolean,
            val executionTimeMs: Long
        ) : SkillEvent()

        data class WorkflowCompleted(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val workflowId: String,
            val success: Boolean,
            val totalExecutionTimeMs: Long
        ) : SkillEvent()

        data class TaskScheduled(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val taskId: String,
            val taskName: String,
            val scheduleType: String,
            val nextExecutionTime: Long
        ) : SkillEvent()

        data class TaskExecuted(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val taskId: String,
            val taskName: String,
            val success: Boolean,
            val executionTimeMs: Long
        ) : SkillEvent()

        data class CustomEvent(
            override val eventId: String = generateEventId(),
            override val timestamp: Long = System.currentTimeMillis(),
            override val source: String,
            val eventType: String,
            val data: Map<String, Any> = emptyMap()
        ) : SkillEvent()
    }

    data class EventListener(
        val id: String = generateEventId(),
        val eventType: String,
        val priority: Int = 0,
        val callback: (SkillEvent) -> Boolean
    )

    data class EventSubscription(
        val subscriptionId: String,
        val eventType: String,
        val subscriber: Any,
        val listener: EventListener
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _events = MutableSharedFlow<SkillEvent>(
        extraBufferCapacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SkillEvent> = _events.asSharedFlow()

    private val eventHistory = ConcurrentHashMap<String, MutableList<SkillEvent>>()
    private val listeners = ConcurrentHashMap<String, MutableList<EventListener>>()
    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()
    private val eventTypeCounters = ConcurrentHashMap<String, Long>()

    private val listenerCounts = ConcurrentHashMap<String, Int>()

    interface EventFilter {
        val eventType: String
        fun matches(event: SkillEvent): Boolean
    }

    private val globalFilters = mutableListOf<EventFilter>()

    fun addGlobalFilter(filter: EventFilter) {
        globalFilters.add(filter)
    }

    fun removeGlobalFilter(filter: EventFilter) {
        globalFilters.remove(filter)
    }

    fun subscribe(
        subscriber: Any,
        eventType: String,
        priority: Int = 0,
        callback: (SkillEvent) -> Boolean
    ): String {
        val subscriptionId = generateEventId()
        val listener = EventListener(
            eventType = eventType,
            priority = priority,
            callback = callback
        )

        val subscription = EventSubscription(
            subscriptionId = subscriptionId,
            eventType = eventType,
            subscriber = subscriber,
            listener = listener
        )

        subscriptions[subscriptionId] = subscription

        scope.launch {
            mutex.withLock {
                val typeListeners = listeners.getOrPut(eventType) { mutableListOf() }
                if (typeListeners.size < MAX_LISTENERS_PER_EVENT) {
                    typeListeners.add(listener)
                    typeListeners.sortByDescending { it.priority }
                    listenerCounts[eventType] = typeListeners.size
                } else {
                    AppLogger.w(TAG, "Max listeners reached for event type: ${eventType}")
                }
            }
        }

        AppLogger.d(TAG, "Subscribed ${subscriber} to event type: ${eventType}, subscriptionId: ${subscriptionId}")
        return subscriptionId
    }

    fun unsubscribe(subscriptionId: String) {
        val subscription = subscriptions.remove(subscriptionId) ?: return

        scope.launch {
            mutex.withLock {
                listeners[subscription.eventType]?.let { typeListeners ->
                    typeListeners.removeAll { it.id == subscription.listener.id }
                    listenerCounts[subscription.eventType] = typeListeners.size
                }
            }
        }

        AppLogger.d(TAG, "Unsubscribed subscription: ${subscriptionId}")
    }

    fun unsubscribeAll(subscriber: Any) {
        val toRemove = subscriptions.values.filter { it.subscriber == subscriber }.map { it.subscriptionId }

        scope.launch {
            mutex.withLock {
                toRemove.forEach { subId ->
                    subscriptions.remove(subId)?.let { subscription ->
                        listeners[subscription.eventType]?.let { typeListeners ->
                            typeListeners.removeAll { it.id == subscription.listener.id }
                            listenerCounts[subscription.eventType] = typeListeners.size
                        }
                    }
                }
            }
        }

        AppLogger.d(TAG, "Unsubscribed all for subscriber: ${subscriber} (removed ${toRemove.size} subscriptions)")
    }

    suspend fun emit(event: SkillEvent) {
        eventTypeCounters[event.eventType] = (eventTypeCounters[event.eventType] ?: 0) + 1

        val shouldEmit = globalFilters.isEmpty() || globalFilters.all { filter ->
            filter.eventType == event.eventType && filter.matches(event)
        }

        if (!shouldEmit) {
            AppLogger.d(TAG, "Event filtered out: ${event.eventId}")
            return
        }

        val typeHistory = eventHistory.getOrPut(event.eventType) { mutableListOf() }
        typeHistory.add(event)
        if (typeHistory.size > MAX_EVENT_HISTORY) {
            typeHistory.removeAt(0)
        }

        _events.emit(event)

        notifyListeners(event)

        AppLogger.d(TAG, "Event emitted: ${event.eventType} [${event.eventId}], timestamp: ${event.timestamp}")
    }

    fun emitSync(event: SkillEvent) {
        scope.launch { emit(event) }
    }

    private fun notifyListeners(event: SkillEvent) {
        val typeListeners = listeners[event.eventType] ?: return

        for (listener in typeListeners) {
            try {
                val shouldContinue = listener.callback(event)
                if (!shouldContinue) {
                    AppLogger.d(TAG, "Listener ${listener.id} requested stop propagation")
                    break
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in event listener ${listener.id}", e)
            }
        }
    }

    fun getEventHistory(eventType: String? = null, limit: Int = 100): List<SkillEvent> {
        return if (eventType != null) {
            eventHistory[eventType]?.takeLast(limit) ?: emptyList()
        } else {
            eventHistory.values.flatten().sortedByDescending { it.timestamp }.take(limit)
        }
    }

    fun getEventCount(eventType: String): Long {
        return eventTypeCounters[eventType] ?: 0
    }

    fun getListenerCount(eventType: String): Int {
        return listenerCounts[eventType] ?: 0
    }

    fun getActiveSubscriptionCount(): Int {
        return subscriptions.size
    }

    fun clearHistory(eventType: String? = null) {
        scope.launch {
            mutex.withLock {
                if (eventType != null) {
                    eventHistory.remove(eventType)
                    eventTypeCounters.remove(eventType)
                } else {
                    eventHistory.clear()
                    eventTypeCounters.clear()
                }
            }
        }
        AppLogger.d(TAG, "Cleared event history for type: ${eventType ?: "ALL"}")
    }

    fun getStats(): EventBusStats {
        return EventBusStats(
            totalEventTypes = listeners.keys.size,
            totalListeners = subscriptions.size,
            totalEventsEmitted = eventTypeCounters.values.sum(),
            eventTypeCounts = eventTypeCounters.toMap(),
            listenerCounts = listenerCounts.toMap()
        )
    }

    data class EventBusStats(
        val totalEventTypes: Int,
        val totalListeners: Int,
        val totalEventsEmitted: Long,
        val eventTypeCounts: Map<String, Long>,
        val listenerCounts: Map<String, Int>
    )

    private fun generateEventId(): String {
        return "evt_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}

inline fun<reified T : SkillEventBus.SkillEvent> SkillEventBus.subscribeTo(
    noinline callback: (T) -> Boolean
): String {
    return subscribe(
        subscriber = callback,
        eventType = T::class.simpleName ?: "",
        callback = { event -> callback(event as T) }
    )
}

suspend fun SkillEventBus.emitSkillLoaded(source: String, skillName: String, loadDurationMs: Long) {
    emit(SkillEventBus.SkillLoaded(
        source = source,
        skillName = skillName,
        loadDurationMs = loadDurationMs
    ))
}

suspend fun SkillEventBus.emitSkillInvoked(source: String, skillName: String, toolName: String? = null, executionTimeMs: Long = 0) {
    emit(SkillEventBus.SkillInvoked(
        source = source,
        skillName = skillName,
        toolName = toolName,
        executionTimeMs = executionTimeMs
    ))
}

suspend fun SkillEventBus.emitSkillCompleted(source: String, skillName: String, success: Boolean, executionTimeMs: Long) {
    emit(SkillEventBus.SkillCompleted(
        source = source,
        skillName = skillName,
        success = success,
        executionTimeMs = executionTimeMs
    ))
}
