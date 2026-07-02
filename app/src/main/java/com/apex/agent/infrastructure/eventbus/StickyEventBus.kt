package com.apex.agent.infrastructure.eventbus

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * 粘性事件总线实现
 *
 * 维护每个事件类型的最后一条粘性事件，
 * 新订阅者自动接收到该类型的最后一条粘性事件。
 * 支持 TTL 过期、最大粘性事件数限制。
 */
class StickyEventBus(
    private val delegate: EventBus,
    private val maxStickyEvents: Int = 100,
    private val defaultTtlMs: Long = 30000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : EventBus {

    private data class StickyEntry(
        val event: Any,
        val timestamp: Long
    )

    private val stickyCache = ConcurrentHashMap<Class<*>, StickyEntry>()
    private val stickyFlows = ConcurrentHashMap<Class<*>, MutableSharedFlow<Any>>()
    private val expiryJobs = ConcurrentHashMap<Class<*>, Job>()
    private val delegateFlows = ConcurrentHashMap<Class<*>, SharedFlow<*>>()

    override fun <T : Any> publish(event: T) {
        delegate.publish(event)
        stickyFlows[event.javaClass]?.tryEmit(event)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventClass: Class<T>): SharedFlow<T> {
        val existing = stickyFlows[eventClass]
        if (existing != null) return existing as SharedFlow<T>

        val entry = stickyCache[eventClass]
        if (entry == null) return delegate.subscribe(eventClass)

        if (isExpired(entry)) {
            clearSticky(eventClass)
            return delegate.subscribe(eventClass)
        }

        val flow = MutableSharedFlow<Any>(replay = 1, extraBufferCapacity = 64)
        flow.tryEmit(entry.event)
        stickyFlows[eventClass] = flow

        scope.launch {
            val df = delegate.subscribe(eventClass)
            delegateFlows[eventClass] = df
            df.collect { flow.tryEmit(it) }
        }
        return flow as SharedFlow<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> stickyEvent(event: T) {
        val clazz = event.javaClass
        stickyCache[clazz] = StickyEntry(event, System.currentTimeMillis())

        delegate.publish(event)

        val flow = stickyFlows.getOrPut(clazz) {
            val f = MutableSharedFlow<Any>(replay = 1, extraBufferCapacity = 64)
            scope.launch {
                val df = delegate.subscribe(clazz as Class<Any>)
                delegateFlows[clazz] = df
                df.collect { f.tryEmit(it) }
            }
            f
        }
        flow.tryEmit(event)
        scheduleExpiry(clazz)
        enforceLimit()
    }

    override fun <T : Any> clearSticky(eventClass: Class<T>) {
        stickyCache.remove(eventClass)
        stickyFlows.remove(eventClass)
        delegateFlows.remove(eventClass)
        expiryJobs[eventClass]?.cancel()
        expiryJobs.remove(eventClass)
    }

    override fun clearAllSticky() {
        stickyCache.clear()
        stickyFlows.clear()
        delegateFlows.clear()
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
    }

    override fun stats(): EventBusStats = delegate.stats()

    override fun close() {
        clearAllSticky()
        delegate.close()
    }

    private fun isExpired(entry: StickyEntry): Boolean {
        return System.currentTimeMillis() - entry.timestamp > defaultTtlMs
    }

    private fun scheduleExpiry(clazz: Class<*>) {
        expiryJobs[clazz]?.cancel()
        expiryJobs[clazz] = scope.launch {
            delay(defaultTtlMs)
            stickyCache.remove(clazz)
            stickyFlows.remove(clazz)
            delegateFlows.remove(clazz)
            expiryJobs.remove(clazz)
        }
    }

    private fun enforceLimit() {
        if (stickyCache.size > maxStickyEvents) {
            val oldest = stickyCache.minByOrNull { it.value.timestamp }
            oldest?.let { clearSticky(it.key) }
        }
    }
}
