package com.apex.agent.burstmode.timeout

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 超时策略。
 *
 * 定义任务执行的时间限制。
 */
sealed class TimeoutStrategy {

    /**
     * 不超时。
     */
    object NoTimeout : TimeoutStrategy()

    /**
     * 固定超时。
     *
     * @property timeoutMs 超时时间（毫秒）
     */
    data class Fixed(val timeoutMs: Long) : TimeoutStrategy()

    /**
     * 多级超时。
     *
     * 不同阶段有不同的超时时间。
     *
     * @property stages 阶段超时配置（阶段名 -> 超时毫秒）
     */
    data class MultiStage(val stages: Map<String, Long>) : TimeoutStrategy()

    /**
     * 自适应超时。
     *
     * 根据历史执行时间动态调整超时。
     *
     * @property initialTimeoutMs 初始超时
     * @property multiplier 历史平均时间的倍数（如 1.5 = 历史平均的 1.5 倍）
     * @property minTimeoutMs 最小超时
     * @property maxTimeoutMs 最大超时
     */
    data class Adaptive(
        val initialTimeoutMs: Long = 30_000,
        val multiplier: Double = 1.5,
        val minTimeoutMs: Long = 5_000,
        val maxTimeoutMs: Long = 300_000
    ) : TimeoutStrategy()
}

/**
 * 超时事件。
 */
data class TimeoutEvent(
    val taskId: String,
    val timeoutMs: Long,
    val actualMs: Long,
    val stage: String? = null
)

/**
 * 超时管理器。
 *
 * 提供任务超时控制能力：
 * - 固定超时
 * - 多级超时（按阶段）
 * - 自适应超时（基于历史）
 * - 超时事件回调
 *
 * # 使用示例
 *
 * ```
 * val manager = TimeoutManager(TimeoutStrategy.Fixed(30_000))
 *
 * // 固定超时
 * val result = manager.withTimeout(taskId) {
 *     performTask()
 * }
 *
 * // 多级超时
 * val multiManager = TimeoutManager(
 *     TimeoutStrategy.MultiStage(mapOf(
 *         "init" to 5_000,
 *         "execute" to 30_000,
 *         "cleanup" to 3_000
 *     ))
 * )
 * multiManager.withStageTimeout(taskId, "execute") {
 *     performExecution()
 * }
 * ```
 */
class TimeoutManager(
    private val strategy: TimeoutStrategy,
    private val onTimeout: ((TimeoutEvent) -> Unit)? = null
) {

    /** 历史执行时间（taskId -> 列表），用于自适应超时 */
    private val executionTimes = ConcurrentHashMap<String, MutableList<Long>>()

    /** 当前活跃的超时任务数 */
    private val activeCount = AtomicLong(0)

    /**
     * 获取当前有效的超时时间。
     *
     * 对于自适应策略，会根据历史动态计算。
     */
    fun getEffectiveTimeout(taskId: String, stage: String? = null): Long? {
        return when (strategy) {
            is TimeoutStrategy.NoTimeout -> null
            is TimeoutStrategy.Fixed -> strategy.timeoutMs
            is TimeoutStrategy.MultiStage -> stage?.let { strategy.stages[it] }
            is TimeoutStrategy.Adaptive -> {
                val history = executionTimes[taskId]
                if (history.isNullOrEmpty()) {
                    strategy.initialTimeoutMs
                } else {
                    val avg = history.average().toLong()
                    (avg * strategy.multiplier).toLong()
                        .coerceIn(strategy.minTimeoutMs, strategy.maxTimeoutMs)
                }
            }
        }
    }

    /**
     * 在超时限制下执行操作。
     *
     * @param taskId 任务 ID
     * @param operation 要执行的操作
     * @return 操作结果
     * @throws kotlinx.coroutines.TimeoutCancellationException 如果超时
     */
    suspend fun <T> withTimeout(taskId: String, operation: suspend () -> T): T {
        val timeoutMs = getEffectiveTimeout(taskId)
            ?: return operation()

        activeCount.incrementAndGet()
        val startTime = System.currentTimeMillis()
        try {
            return kotlinx.coroutines.withTimeout(timeoutMs) {
                operation()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val event = TimeoutEvent(taskId, timeoutMs, System.currentTimeMillis() - startTime)
            onTimeout?.invoke(event)
            throw e
        } finally {
            activeCount.decrementAndGet()
        }
    }

    /**
     * 在超时限制下执行操作，超时返回 null。
     */
    suspend fun <T> withTimeoutOrNull(taskId: String, operation: suspend () -> T): T? {
        val timeoutMs = getEffectiveTimeout(taskId) ?: return operation()
        activeCount.incrementAndGet()
        val startTime = System.currentTimeMillis()
        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { operation() }
            if (result == null) {
                val event = TimeoutEvent(taskId, timeoutMs, System.currentTimeMillis() - startTime)
                onTimeout?.invoke(event)
            }
            return result
        } finally {
            activeCount.decrementAndGet()
        }
    }

    /**
     * 在指定阶段超时下执行操作。
     *
     * 仅适用于 [TimeoutStrategy.MultiStage]。
     *
     * @param taskId 任务 ID
     * @param stage 阶段名
     * @param operation 要执行的操作
     */
    suspend fun <T> withStageTimeout(taskId: String, stage: String, operation: suspend () -> T): T {
        val timeoutMs = getEffectiveTimeout(taskId, stage)
            ?: return operation()

        activeCount.incrementAndGet()
        val startTime = System.currentTimeMillis()
        try {
            return kotlinx.coroutines.withTimeout(timeoutMs) { operation() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val event = TimeoutEvent(taskId, timeoutMs, System.currentTimeMillis() - startTime, stage)
            onTimeout?.invoke(event)
            throw e
        } finally {
            activeCount.decrementAndGet()
        }
    }

    /**
     * 记录执行时间（用于自适应超时）。
     *
     * @param taskId 任务 ID
     * @param executionMs 实际执行时间
     */
    fun recordExecutionTime(taskId: String, executionMs: Long) {
        val list = executionTimes.computeIfAbsent(taskId) { mutableListOf() }
        synchronized(list) {
            list.add(executionMs)
            // 保留最近 20 次
            while (list.size > 20) list.removeAt(0)
        }
    }

    /**
     * 获取任务的历史执行时间。
     */
    fun getExecutionHistory(taskId: String): List<Long> {
        return executionTimes[taskId]?.toList() ?: emptyList()
    }

    /**
     * 获取当前活跃超时任务数。
     */
    fun getActiveCount(): Long = activeCount.get()

    /**
     * 清除历史执行时间。
     */
    fun clearHistory(taskId: String? = null) {
        if (taskId != null) {
            executionTimes.remove(taskId)
        } else {
            executionTimes.clear()
        }
    }
}

/**
 * 全局超时监控器。
 *
 * 监控所有活跃任务，支持：
 * - 查看当前活跃任务数
 * - 强制取消超时任务
 * - 超时统计
 */
object TimeoutMonitor {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val timeoutCount = AtomicLong(0)
    private val totalCount = AtomicLong(0)

    /**
     * 注册活跃任务。
     */
    internal fun register(taskId: String, job: Job) {
        activeJobs[taskId] = job
        totalCount.incrementAndGet()
    }

    /**
     * 注销任务。
     */
    internal fun unregister(taskId: String) {
        activeJobs.remove(taskId)
    }

    /**
     * 记录一次超时。
     */
    internal fun recordTimeout() {
        timeoutCount.incrementAndGet()
    }

    /**
     * 取消指定任务。
     */
    fun cancel(taskId: String, reason: String? = null): Boolean {
        val job = activeJobs[taskId] ?: return false
        job.cancel(CancellationException(reason ?: "Manually cancelled"))
        return true
    }

    /**
     * 取消所有活跃任务。
     */
    fun cancelAll(reason: String? = null) {
        for ((_, job) in activeJobs) {
            job.cancel(CancellationException(reason ?: "Cancel all"))
        }
        activeJobs.clear()
    }

    /**
     * 获取当前活跃任务数。
     */
    fun getActiveCount(): Int = activeJobs.size

    /**
     * 获取超时统计。
     */
    fun getStats(): TimeoutStats {
        return TimeoutStats(
            activeCount = activeJobs.size,
            timeoutCount = timeoutCount.get(),
            totalCount = totalCount.get()
        )
    }
}

/**
 * 超时统计。
 */
data class TimeoutStats(
    val activeCount: Int,
    val timeoutCount: Long,
    val totalCount: Long
) {
    /**
     * 超时率（0..1）。
     */
    val timeoutRate: Double
        get() = if (totalCount > 0) timeoutCount.toDouble() / totalCount else 0.0
}
