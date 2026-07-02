package com.apex.agent.burstmode.retry

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * 重试策略。
 *
 * 决定任务失败后是否重试、何时重试、重试多少次。
 */
sealed class RetryStrategy {

    /**
     * 不重试。
     */
    object NoRetry : RetryStrategy()

    /**
     * 固定间隔重试。
     *
     * @property maxRetries 最大重试次数（不含首次执行）
     * @property delayMs 每次重试的固定间隔
     */
    data class FixedDelay(
        val maxRetries: Int,
        val delayMs: Long
    ) : RetryStrategy()

    /**
     * 指数退避重试。
     *
     * 每次重试间隔 = baseDelayMs * (multiplier ^ attempt)
     *
     * @property maxRetries 最大重试次数
     * @property baseDelayMs 初始延迟
     * @property multiplier 倍数（通常 2.0）
     * @property maxDelayMs 最大延迟上限
     */
    data class ExponentialBackoff(
        val maxRetries: Int,
        val baseDelayMs: Long = 1000,
        val multiplier: Double = 2.0,
        val maxDelayMs: Long = 60000
    ) : RetryStrategy()

    /**
     * 指数退避 + 抖动。
     *
     * 在指数退避基础上加入随机抖动，避免多个客户端同时重试（thundering herd）。
     *
     * @property maxRetries 最大重试次数
     * @property baseDelayMs 初始延迟
     * @property multiplier 倍数
     * @property maxDelayMs 最大延迟
     * @property jitterFactor 抖动因子（0..1，0.2 = ±20%）
     */
    data class ExponentialBackoffWithJitter(
        val maxRetries: Int,
        val baseDelayMs: Long = 1000,
        val multiplier: Double = 2.0,
        val maxDelayMs: Long = 60000,
        val jitterFactor: Double = 0.2
    ) : RetryStrategy()

    /**
     * 自定义重试。
     *
     * @property maxRetries 最大重试次数
     * @property delayCalculator 自定义延迟计算函数（attempt 从 1 开始）
     */
    data class Custom(
        val maxRetries: Int,
        val delayCalculator: (attempt: Int) -> Long
    ) : RetryStrategy()
}

/**
 * 重试决策。
 */
data class RetryDecision(
    val shouldRetry: Boolean,
    val delayMs: Long = 0,
    val attempt: Int,
    val reason: String? = null
) {
    companion object {
        fun retry(attempt: Int, delayMs: Long) = RetryDecision(
            shouldRetry = true,
            delayMs = delayMs,
            attempt = attempt
        )

        fun stop(attempt: Int, reason: String) = RetryDecision(
            shouldRetry = false,
            attempt = attempt,
            reason = reason
        )
    }
}

/**
 * 重试策略执行器。
 *
 * 封装重试逻辑，支持：
 * - 多种重试策略
 * - 条件重试（根据异常类型决定是否重试）
 * - 重试次数限制
 * - 重试事件回调
 *
 * # 使用示例
 *
 * ```
 * val executor = RetryExecutor(ExponentialBackoff(maxRetries = 3, baseDelayMs = 1000))
 *
 * val result = executor.execute {
 *     // 可能失败的操作
 *     performRiskyOperation()
 * }
 * ```
 *
 * ## 自定义重试条件
 * ```
 * val executor = RetryExecutor(
 *     strategy = ExponentialBackoff(maxRetries = 3),
 *     retryCondition = { e -> e is java.io.IOException }  // 仅 IO 异常重试
 * )
 * ```
 */
class RetryExecutor(
    private val strategy: RetryStrategy,
    private val retryCondition: (Exception) -> Boolean = { true },
    private val onRetry: ((attempt: Int, error: Exception, nextDelayMs: Long) -> Unit)? = null
) {

    /**
     * 执行带重试的操作。
     *
     * @param operation 要执行的操作
     * @return 操作结果
     * @throws Exception 如果所有重试都失败，抛出最后一次异常
     */
    suspend fun <T> execute(operation: suspend () -> T): T {
        var lastException: Exception? = null
        val maxRetries = when (strategy) {
            is RetryStrategy.NoRetry -> 0
            is RetryStrategy.FixedDelay -> strategy.maxRetries
            is RetryStrategy.ExponentialBackoff -> strategy.maxRetries
            is RetryStrategy.ExponentialBackoffWithJitter -> strategy.maxRetries
            is RetryStrategy.Custom -> strategy.maxRetries
        }

        for (attempt in 0..maxRetries) {
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e

                // 检查是否应该重试
                if (attempt >= maxRetries || !retryCondition(e)) {
                    throw e
                }

                // 计算延迟
                val delayMs = calculateDelay(attempt + 1)
                onRetry?.invoke(attempt + 1, e, delayMs)
                delay(delayMs)
            }
        }

        throw lastException ?: RuntimeException("Retry exhausted without exception")
    }

    /**
     * 执行带重试的操作（返回 Result）。
     *
     * 不抛异常，而是返回 Result.success 或 Result.failure。
     */
    suspend fun <T> executeResult(operation: suspend () -> T): Result<T> {
        return try {
            Result.success(execute(operation))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 预计算重试时间表。
     *
     * 返回每次重试的延迟时间列表（不含首次执行）。
     */
    fun getSchedule(): List<Long> {
        val maxRetries = when (strategy) {
            is RetryStrategy.NoRetry -> 0
            is RetryStrategy.FixedDelay -> strategy.maxRetries
            is RetryStrategy.ExponentialBackoff -> strategy.maxRetries
            is RetryStrategy.ExponentialBackoffWithJitter -> strategy.maxRetries
            is RetryStrategy.Custom -> strategy.maxRetries
        }

        return (1..maxRetries).map { calculateDelay(it) }
    }

    private fun calculateDelay(attempt: Int): Long {
        return when (strategy) {
            is RetryStrategy.NoRetry -> 0
            is RetryStrategy.FixedDelay -> strategy.delayMs
            is RetryStrategy.ExponentialBackoff -> {
                val delay = strategy.baseDelayMs * Math.pow(strategy.multiplier, (attempt - 1).toDouble())
                min(delay.toLong(), strategy.maxDelayMs)
            }
            is RetryStrategy.ExponentialBackoffWithJitter -> {
                val baseDelay = strategy.baseDelayMs * Math.pow(strategy.multiplier, (attempt - 1).toDouble())
                val capped = min(baseDelay.toLong(), strategy.maxDelayMs)
                val jitter = capped * strategy.jitterFactor
                val minDelay = (capped - jitter).toLong().coerceAtLeast(0)
                val maxDelay = (capped + jitter).toLong()
                Random.nextLong(minDelay, maxDelay + 1)
            }
            is RetryStrategy.Custom -> strategy.delayCalculator(attempt)
        }
    }
}

/**
 * 重试历史记录。
 *
 * 记录某个操作的重试次数和结果，用于分析。
 */
class RetryHistory {

    private val records = ConcurrentHashMap<String, MutableList<RetryRecord>>()

    /**
     * 记录一次重试。
     */
    fun record(taskId: String, attempt: Int, success: Boolean, error: String? = null) {
        val list = records.computeIfAbsent(taskId) { mutableListOf() }
        synchronized(list) {
            list.add(RetryRecord(taskId, attempt, success, error, System.currentTimeMillis()))
        }
    }

    /**
     * 获取任务的重试记录。
     */
    fun getHistory(taskId: String): List<RetryRecord> {
        return records[taskId]?.toList() ?: emptyList()
    }

    /**
     * 获取所有任务的重试次数统计。
     */
    fun getRetryStats(): Map<String, Int> {
        return records.mapValues { it.value.size }
    }

    /**
     * 清空历史。
     */
    fun clear() {
        records.clear()
    }
}

/**
 * 重试记录。
 */
data class RetryRecord(
    val taskId: String,
    val attempt: Int,
    val success: Boolean,
    val error: String?,
    val timestamp: Long
)
