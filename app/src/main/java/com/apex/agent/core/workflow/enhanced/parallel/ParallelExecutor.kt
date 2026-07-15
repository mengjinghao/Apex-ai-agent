package com.apex.agent.core.workflow.enhanced.parallel

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 并行执行事件 - 用于追踪 fan-out 进度
 */
sealed class ParallelExecutionEvent {
    data class BranchStarted(val branchIndex: Int, val totalBranches: Int, val input: Any?) : ParallelExecutionEvent()
    data class BranchCompleted(val branchIndex: Int, val output: Any?) : ParallelExecutionEvent()
    data class BranchFailed(val branchIndex: Int, val error: Throwable) : ParallelExecutionEvent()
    data class AllCompleted(val outputs: Map<Int, Any?>, val failures: Map<Int, Throwable>) : ParallelExecutionEvent()
    data class BarrierReached(val nodeId: String, val arrivedCount: Int, val expectedCount: Int) : ParallelExecutionEvent()
}

/**
 * 聚合器 - 决定 fan-in 如何合并多个分支的输出
 *
 * 参照 Dify 的 Variable Aggregator、PocketFlow 的 map-reduce、LlamaIndex 的 collect_events
 */
interface Aggregator {
    /** 把多个分支的输出合并成单一 output */
    fun merge(branchOutputs: Map<Int, Any?>): Any
}

object Aggregators {
    /** 取第一个完成的分支结果 */
    val First: Aggregator = object : Aggregator {
        override fun merge(branchOutputs: Map<Int, Any?>): Any =
            branchOutputs.entries.firstOrNull { it.value != null }?.value ?: ""
    }

    /** 取最后一个完成的分支结果 */
    val Last: Aggregator = object : Aggregator {
        override fun merge(branchOutputs: Map<Int, Any?>): Any =
            branchOutputs.entries.lastOrNull { it.value != null }?.value ?: ""
    }

    /** 保留所有分支结果为 List */
    val All: Aggregator = object : Aggregator {
        override fun merge(branchOutputs: Map<Int, Any?>): Any =
            branchOutputs.toSortedMap().values.toList()
    }

    /** 按 key 合并所有 Map 输出 */
    val MergeByKey: Aggregator = object : Aggregator {
        override fun merge(branchOutputs: Map<Int, Any?>): Any {
            val result = mutableMapOf<String, Any?>()
            branchOutputs.values.forEach { v ->
                if (v is Map<*, *>) @Suppress("UNCHECKED_CAST")
                    result.putAll(v as Map<String, Any?>)
            }
        return result
        }
    }

    /** 合并为列表（每个分支输出一个元素） */
    val MergeList: Aggregator = object : Aggregator {
        override fun merge(branchOutputs: Map<Int, Any?>): Any =
            branchOutputs.toSortedMap().values.map { it }.toList()
    }
}

/**
 * 并行执行器 - 实现 Fan-out / Fan-in + Barrier 同步
 *
 * 参照 Dify Variable Aggregator、PocketFlow Parallel Flow、LlamaIndex collect_events
 */
class ParallelExecutor {

    private val _events = MutableSharedFlow<ParallelExecutionEvent>(extraBufferCapacity = 64)
        val events: SharedFlow<ParallelExecutionEvent> = _events.asSharedFlow()

    /**
     * Fan-out：将 items 列表分裂成 N 个并行执行
     *
     * @param items 输入列表
     * @param maxConcurrency 最大并发数（限流，防打爆下游）
     * @param failFast true=任一失败即取消其他；false=等所有完成
     * @param block 每个分支的执行逻辑，返回结果
     * @return 各分支结果（index -> output）
     */
    suspend fun <T, R> fanOut(
        items: List<T>,
        maxConcurrency: Int = 5,
        failFast: Boolean = true,
        block: suspend (index: Int, item: T) -> R
    ): FanOutResult<R> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        val results = ConcurrentHashMap<Int, R>()
        val failures = ConcurrentHashMap<Int, Throwable>()
        val deferred = items.mapIndexed { idx, item ->
            async {
                semaphore.withPermit {
                    _events.emit(ParallelExecutionEvent.BranchStarted(idx, items.size, item))
                    try {
                        val r = block(idx, item)
                        results[idx] = r
                        _events.emit(ParallelExecutionEvent.BranchCompleted(idx, r))
                        r
                    } catch (e: Throwable) {
                        failures[idx] = e
                        _events.emit(ParallelExecutionEvent.BranchFailed(idx, e))
        if (failFast) throw e
                        null
                    }
                }
            }
        }
        if (failFast) {
            try {
                deferred.awaitAll()
            } catch (e: Throwable) {
                // 取消其他分支由 coroutineScope 自动完成
            }
        } else {
            deferred.awaitAll()
        }
        val finalResult = FanOutResult(results.toSortedMap(), failures.toSortedMap())
        _events.emit(
            ParallelExecutionEvent.AllCompleted(
                finalResult.outputs.mapKeys { it.key.toString() }.toMap()
                    .mapValues { it.value as Any },
                finalResult.failures.mapKeys { it.key.toString() }.toMap()
                    .mapValues { it.value as Throwable }
            )
        )
        finalResult
    }

    /**
     * Barrier 同步 - 等待 N 个分支全部到达后继续
     *
     * @param barrierId barrier 标识
     * @param expectedCount 预期到达数
     * @param timeoutMs 超时（0=无限等待）
     */
    suspend fun awaitBarrier(
        barrierId: String,
        expectedCount: Int,
        nodeId: String,
        timeoutMs: Long = 0
    ): BarrierResult {
        val barrier = barriers.computeIfAbsent(barrierId) { BarrierState(expectedCount) }
        val arrived = barrier.incrementArrived()
        _events.emit(ParallelExecutionEvent.BarrierReached(nodeId, arrived, expectedCount))
        return barrier.awaitAll(timeoutMs)
    }

    /**
     * Fan-in：合并 fan-out 的结果
     */
    fun <R> fanIn(result: FanOutResult<R>, aggregator: Aggregator): Any {
        @Suppress("UNCHECKED_CAST")
        return aggregator.merge(result.outputs as Map<Int, Any?>)
    }
        fun resetBarrier(barrierId: String) {
        barriers.remove(barrierId)
    }
        fun clearAllBarriers() {
        barriers.clear()
    }
        private val barriers = ConcurrentHashMap<String, BarrierState>()
}

/**
 * Fan-out 执行结果
 */
data class FanOutResult<R>(
    val outputs: Map<Int, R>,
    val failures: Map<Int, Throwable>
) {
    val isFullSuccess: Boolean get() = failures.isEmpty()
        val successCount: Int get() = outputs.size
    val failureCount: Int get() = failures.size
}

/**
 * Barrier 等待结果
 */
sealed class BarrierResult {
    data object Reached : BarrierResult()
    data class TimedOut(val arrivedCount: Int, val expectedCount: Int) : BarrierResult()
    data class Cancelled(val reason: String) : BarrierResult()
}

private class BarrierState(private val expectedCount: Int) {
    private val arrivedCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val latch = java.util.concurrent.CountDownLatch(expectedCount)
        private val allArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        fun incrementArrived(): Int {
        val n = arrivedCount.incrementAndGet()
        if (n >= expectedCount) allArrived.set(true)
        latch.countDown()
        return n
    }

    suspend fun awaitAll(timeoutMs: Long): BarrierResult {
        return if (timeoutMs <= 0) {
            latch.await()
            BarrierResult.Reached
        } else {
            val reached = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (reached) BarrierResult.Reached
            else BarrierResult.TimedOut(arrivedCount.get(), expectedCount)
        }
    }
}
