package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 调度策略
 */
sealed interface DispatchingStrategy {
    /** 顺序调度 - 事件按顺序逐个处理 */
    data object Sequential : DispatchingStrategy

    /** 并行调度 - 事件并发处理 */
    data class Parallel(val parallelism: Int = 4) : DispatchingStrategy

    /** 有序调度 - 按键分组，每组内顺序处理 */
    data class Ordered(val keyExtractor: (Any) -> Any) : DispatchingStrategy

    /** 优先级调度 - 按优先级排序处理 */
    data class Priority(val comparator: Comparator<Any>) : DispatchingStrategy
}

/**
 * 背压策略
 */
sealed interface BackpressureStrategy {
    /** 丢弃新事件 */
    data object Drop : BackpressureStrategy

    /** 缓冲等待 */
    data class Buffer(val capacity: Int = 256) : BackpressureStrategy

    /** 反压等待 */
    data object Backpressure : BackpressureStrategy
}

/**
 * 错误处理策略
 */
sealed interface ErrorStrategy {
    /** 快速失败 */
    data object FailFast : ErrorStrategy

    /** 忽略错误继续 */
    data object ContinueOnError : ErrorStrategy

    /** 重试 */
    data class RetryOnError(val maxRetries: Int = 3, val retryDelayMs: Long = 100) : ErrorStrategy
}

/**
 * 事件调度器 - 管理事件的分发策略
 */
class EventDispatcher(
    private val eventBus: EventBus,
    private val strategy: DispatchingStrategy = DispatchingStrategy.Sequential,
    private val errorStrategy: ErrorStrategy = ErrorStrategy.FailFast,
    private val backpressureStrategy: BackpressureStrategy = BackpressureStrategy.Drop,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : EventBus {

    private val dispatchChannel = Channel<Any>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<Any, Job>()
    private val seqCounter = AtomicInteger(0)
    private val isClosed = AtomicBoolean(false)

    init {
        startDispatching()
    }

    private fun startDispatching() {
        when (strategy) {
            is DispatchingStrategy.Sequential -> startSequential()
            is DispatchingStrategy.Parallel -> startParallel(strategy.parallelism)
            is DispatchingStrategy.Ordered -> startOrdered(strategy.keyExtractor)
            is DispatchingStrategy.Priority -> startPriority(strategy.comparator)
        }
    }

    private fun startSequential() {
        scope.launch {
            for (event in dispatchChannel) {
                processWithErrorHandling(event)
            }
        }
    }

    private fun startParallel(parallelism: Int) {
        val effectiveParallelism = max(1, min(parallelism, Runtime.getRuntime().availableProcessors() * 2))
        repeat(effectiveParallelism) {
            scope.launch {
                for (event in dispatchChannel) {
                    processWithErrorHandling(event)
                }
            }
        }
    }

    private fun startOrdered(keyExtractor: (Any) -> Any) {
        val keyChannels = ConcurrentHashMap<Any, Channel<Any>>()
        scope.launch {
            for (event in dispatchChannel) {
                val key = keyExtractor(event)
                val channel = keyChannels.getOrPut(key) {
                    Channel<Any>(Channel.UNLIMITED).also { ch ->
                        scope.launch {
                            for (e in ch) {
                                processWithErrorHandling(e)
                            }
                        }
                    }
                }
                channel.send(event)
            }
        }
    }

    private fun startPriority(comparator: Comparator<Any>) {
        scope.launch {
            val buffer = mutableListOf<Any>()
            for (event in dispatchChannel) {
                buffer.add(event)
                buffer.sortWith(comparator)
                if (buffer.isNotEmpty()) {
                    processWithErrorHandling(buffer.removeAt(0))
                }
            }
        }
    }

    private suspend fun processWithErrorHandling(event: Any) {
        when (errorStrategy) {
            is ErrorStrategy.FailFast -> {
                eventBus.publish(event)
            }
            is ErrorStrategy.ContinueOnError -> {
                try {
                    eventBus.publish(event)
                } catch (_: Exception) {
                    // 忽略错误继续处理
                }
            }
            is ErrorStrategy.RetryOnError -> {
                var lastException: Exception? = null
                for (attempt in 1..errorStrategy.maxRetries) {
                    try {
                        eventBus.publish(event)
                        lastException = null
                        break
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt < errorStrategy.maxRetries) {
                            kotlinx.coroutines.delay(errorStrategy.retryDelayMs)
                        }
                    }
                }
                lastException?.let { throw it }
            }
        }
    }

    override fun <T : Any> publish(event: T) {
        if (isClosed.get()) return
        when (backpressureStrategy) {
            is BackpressureStrategy.Drop -> {
                if (!dispatchChannel.trySend(event).isSuccess) {
                    // 丢弃
                }
            }
            is BackpressureStrategy.Buffer -> {
                dispatchChannel.trySend(event)
            }
            is BackpressureStrategy.Backpressure -> {
                kotlinx.coroutines.runBlocking {
                    dispatchChannel.send(event)
                }
            }
        }
    }

    override fun <T : Any> subscribe(eventClass: Class<T>): SharedFlow<T> {
        return eventBus.subscribe(eventClass)
    }

    override fun <T : Any> stickyEvent(event: T) {
        eventBus.stickyEvent(event)
    }

    override fun <T : Any> clearSticky(eventClass: Class<T>) {
        eventBus.clearSticky(eventClass)
    }

    override fun clearAllSticky() {
        eventBus.clearAllSticky()
    }

    override fun stats(): EventBusStats = eventBus.stats()

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            dispatchChannel.close()
        }
    }
}
