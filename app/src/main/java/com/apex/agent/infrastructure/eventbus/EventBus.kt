package com.apex.agent.infrastructure.eventbus

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * EventBus 统计数据
 */
data class EventBusStats(
    val subscriberCount: Int,
    val eventCount: Long,
    val throughput: Double
)

/**
 * 事件总线接口
 */
interface EventBus {

    fun <T : Any> publish(event: T)

    fun <T : Any> subscribe(eventClass: Class<T>): SharedFlow<T>

    fun <T : Any> stickyEvent(event: T)

    fun <T : Any> clearSticky(eventClass: Class<T>)

    fun clearAllSticky()

    fun stats(): EventBusStats

    fun close()

    @Singleton
    class Default constructor() : EventBus {

        private val config: EventBusConfig = EventBusConfig.DEFAULT
        private val bus = MutableSharedFlow<Any>(
            extraBufferCapacity = config.bufferSize,
            onBufferOverflow = config.overflowStrategy
        )
        private val scope = CoroutineScope(SupervisorJob() + config.dispatcher)
        private val subscribers = ConcurrentHashMap<Class<*>, MutableSharedFlow<Any>>()
        private val stickyEvents = ConcurrentHashMap<Class<*>, Any>()
        private val totalEvents = AtomicLong(0)
        private val startTime = System.nanoTime()

        override fun <T : Any> publish(event: T) {
            bus.tryEmit(event)
            totalEvents.incrementAndGet()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> subscribe(eventClass: Class<T>): SharedFlow<T> {
            val existing = subscribers[eventClass]
            if (existing != null) return existing as SharedFlow<T>
            val flow = MutableSharedFlow<Any>(
                replay = if (stickyEvents.containsKey(eventClass)) 1 else 0,
                extraBufferCapacity = config.bufferSize,
                onBufferOverflow = config.overflowStrategy
            )
            stickyEvents[eventClass]?.let { flow.tryEmit(it) }
            subscribers[eventClass] = flow
            scope.launch {
                bus.filterIsInstance(eventClass).collect { flow.tryEmit(it) }
            }
            return flow as SharedFlow<T>
        }

        override fun <T : Any> stickyEvent(event: T) {
            stickyEvents[event.javaClass] = event
            publish(event)
            subscribers[event.javaClass]?.tryEmit(event)
        }

        override fun <T : Any> clearSticky(eventClass: Class<T>) {
            stickyEvents.remove(eventClass)
        }

        override fun clearAllSticky() {
            stickyEvents.clear()
        }

        override fun stats(): EventBusStats {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
            return EventBusStats(
                subscriberCount = subscribers.size,
                eventCount = totalEvents.get(),
                throughput = if (elapsed > 0) totalEvents.get() / elapsed else 0.0
            )
        }

        override fun close() {
            stickyEvents.clear()
            subscribers.clear()
        }
    }
}
