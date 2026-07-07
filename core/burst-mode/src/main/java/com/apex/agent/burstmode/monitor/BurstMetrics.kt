package com.apex.agent.burstmode.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 狂暴模式指标快照。
 *
 * 记录某一时刻的累计指标。通过 [BurstMode.getMetrics] 或 [BurstMode.observeMetrics] 获取。
 *
 * @property totalTasks 总任务数（含失败）
 * @property successfulTasks 成功任务数
 * @property failedTasks 失败任务数
 * @property cancelledTasks 取消任务数
 * @property totalExecutionTimeMs 总执行时间（毫秒）
 * @property averageExecutionTimeMs 平均执行时间（毫秒）
 * @property successRate 成功率（0..1）
 * @property currentConcurrency 当前并发数
 * @property peakConcurrency 峰值并发数
 * @property totalTokensProcessed 总处理 token 数
 * @property totalMemoryUsedMb 累计使用内存（MB·秒）
 * @property lastTaskTime 最后一次任务完成时间戳
 */
data class BurstMetricsSnapshot(
    val totalTasks: Long,
    val successfulTasks: Long,
    val failedTasks: Long,
    val cancelledTasks: Long,
    val totalExecutionTimeMs: Long,
    val averageExecutionTimeMs: Double,
    val successRate: Double,
    val currentConcurrency: Int,
    val peakConcurrency: Int,
    val totalTokensProcessed: Long,
    val totalMemoryUsedMb: Long,
    val lastTaskTime: Long
) {
    companion object {
        val EMPTY = BurstMetricsSnapshot(
            totalTasks = 0,
            successfulTasks = 0,
            failedTasks = 0,
            cancelledTasks = 0,
            totalExecutionTimeMs = 0,
            averageExecutionTimeMs = 0.0,
            successRate = 0.0,
            currentConcurrency = 0,
            peakConcurrency = 0,
            totalTokensProcessed = 0,
            totalMemoryUsedMb = 0,
            lastTaskTime = 0
        )
    }
}

/**
 * 狂暴模式指标收集器。
 *
 * 线程安全的指标收集，每次任务完成后更新。
 * 通过 [snapshot] 获取当前快照，通过 [observe] 观察。
 */
class BurstMetrics {

    private val totalTasks = AtomicLong(0)
    private val successfulTasks = AtomicLong(0)
    private val failedTasks = AtomicLong(0)
    private val cancelledTasks = AtomicLong(0)
    private val totalExecutionTimeMs = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val totalMemoryMbSec = AtomicLong(0)

    @Volatile
    private var currentConcurrency = 0

    @Volatile
    private var peakConcurrency = 0

    @Volatile
    private var lastTaskTime = 0L

    private val _snapshot = MutableStateFlow(BurstMetricsSnapshot.EMPTY)
    val snapshot: StateFlow<BurstMetricsSnapshot> = _snapshot.asStateFlow()

    /**
     * 任务开始时调用。
     */
    fun onTaskStarted() {
        synchronized(this) {
            currentConcurrency++
            if (currentConcurrency > peakConcurrency) {
                peakConcurrency = currentConcurrency
            }
        }
        updateSnapshot()
    }

    /**
     * 任务成功完成时调用。
     */
    fun onTaskSucceeded(executionTimeMs: Long, tokensProcessed: Long, memoryMbSec: Long) {
        totalTasks.incrementAndGet()
        successfulTasks.incrementAndGet()
        totalExecutionTimeMs.addAndGet(executionTimeMs)
        totalTokens.addAndGet(tokensProcessed)
        totalMemoryMbSec.addAndGet(memoryMbSec)
        lastTaskTime = System.currentTimeMillis()
        synchronized(this) {
            currentConcurrency = (currentConcurrency - 1).coerceAtLeast(0)
        }
        updateSnapshot()
    }

    /**
     * 任务失败时调用。
     */
    fun onTaskFailed(executionTimeMs: Long, memoryMbSec: Long) {
        totalTasks.incrementAndGet()
        failedTasks.incrementAndGet()
        totalExecutionTimeMs.addAndGet(executionTimeMs)
        totalMemoryMbSec.addAndGet(memoryMbSec)
        lastTaskTime = System.currentTimeMillis()
        synchronized(this) {
            currentConcurrency = (currentConcurrency - 1).coerceAtLeast(0)
        }
        updateSnapshot()
    }

    /**
     * 任务取消时调用。
     */
    fun onTaskCancelled() {
        totalTasks.incrementAndGet()
        cancelledTasks.incrementAndGet()
        lastTaskTime = System.currentTimeMillis()
        synchronized(this) {
            currentConcurrency = (currentConcurrency - 1).coerceAtLeast(0)
        }
        updateSnapshot()
    }

    /**
     * 获取当前快照。
     */
    fun getSnapshot(): BurstMetricsSnapshot = _snapshot.value

    /**
     * 观察指标变化。
     */
    fun observe(): StateFlow<BurstMetricsSnapshot> = snapshot

    /**
     * 重置所有指标。
     */
    fun reset() {
        totalTasks.set(0)
        successfulTasks.set(0)
        failedTasks.set(0)
        cancelledTasks.set(0)
        totalExecutionTimeMs.set(0)
        totalTokens.set(0)
        totalMemoryMbSec.set(0)
        synchronized(this) {
            currentConcurrency = 0
            peakConcurrency = 0
        }
        lastTaskTime = 0L
        updateSnapshot()
    }

    private fun updateSnapshot() {
        val total = totalTasks.get()
        val success = successfulTasks.get()
        val totalTime = totalExecutionTimeMs.get()
        _snapshot.value = BurstMetricsSnapshot(
            totalTasks = total,
            successfulTasks = success,
            failedTasks = failedTasks.get(),
            cancelledTasks = cancelledTasks.get(),
            totalExecutionTimeMs = totalTime,
            averageExecutionTimeMs = if (total > 0) totalTime.toDouble() / total else 0.0,
            successRate = if (total > 0) success.toDouble() / total else 0.0,
            currentConcurrency = currentConcurrency,
            peakConcurrency = peakConcurrency,
            totalTokensProcessed = totalTokens.get(),
            totalMemoryUsedMb = totalMemoryMbSec.get(),
            lastTaskTime = lastTaskTime
        )
    }
}
