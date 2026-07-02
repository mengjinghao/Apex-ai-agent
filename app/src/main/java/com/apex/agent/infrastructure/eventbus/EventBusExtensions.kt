package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件流包装类
 */
class EventFlow<T>(val flow: SharedFlow<T>)

/**
 * 订阅事件流
 */
fun <T> EventFlow<T>.subscribe(scope: CoroutineScope): Flow<T> {
    scope.launch {
        flow.collect { /* 启动收集 */ }
    }
    return flow
}

/**
 * 延迟发布事件
 */
fun <T : Any> EventBus.delayedEvent(
    event: T,
    delayMs: Long,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Job {
    return scope.launch {
        delay(delayMs)
        publish(event)
    }
}

/**
 * 批量发布事件 - 原子性地发布一批事件
 */
fun EventBus.batchEvent(events: List<Any>) {
    events.forEach { publish(it) }
}

// 节流状态存储
private val lastThrottleTimes = ConcurrentHashMap<Class<*>, AtomicLong>()

/**
 * 节流发布事件 - 在最小间隔内只发布一次
 */
fun <T : Any> EventBus.throttledEvent(event: T, minIntervalMs: Long) {
    val now = System.currentTimeMillis()
    val lastTime = lastThrottleTimes.getOrPut(event.javaClass) { AtomicLong(0) }
    if (now - lastTime.get() >= minIntervalMs) {
        lastTime.set(now)
        publish(event)
    }
}

// 防抖状态存储
private val debounceJobs = ConcurrentHashMap<Class<*>, Job>()

/**
 * 防抖发布事件 - 等待静默期后才发布
 */
fun <T : Any> EventBus.debouncedEvent(
    event: T,
    waitMs: Long,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    val key = event.javaClass
    debounceJobs[key]?.cancel()
    debounceJobs[key] = scope.launch {
        delay(waitMs)
        publish(event)
        debounceJobs.remove(key)
    }
}

/**
 * 观察事件 - 返回冷 Flow，每次订阅时开始接收事件
 */
fun <T : Any> EventBus.observeEvent(
    eventClass: Class<T>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Flow<T> {
    return callbackFlow {
        val job = scope.launch {
            subscribe(eventClass).collect { trySend(it) }
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.Default)
}

/**
 * 等待事件 - 挂起直到收到指定类型的事件（可超时）
 */
suspend fun <T : Any> EventBus.awaitEvent(
    eventClass: Class<T>,
    timeoutMs: Long = Long.MAX_VALUE
): T {
    return withTimeout(timeoutMs) {
        subscribe(eventClass).first()
    }
}

/**
 * 元数据包装器
 */
data class MetadataEnvelope(
    val event: Any,
    val metadata: Map<String, Any>
)

/**
 * 附加元数据发布事件
 */
fun EventBus.withMetadata(
    event: Any,
    metadata: Map<String, Any>
) {
    publish(MetadataEnvelope(event, metadata))
}

/**
 * 事件管道 - 将源事件类型转换为目标事件类型并发布
 */
fun <S : Any, T : Any> EventBus.eventPipe(
    sourceClass: Class<S>,
    targetClass: Class<T>,
    transform: (S) -> T,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Job {
    return scope.launch {
        subscribe(sourceClass).collect { source ->
            publish(transform(source))
        }
    }
}
