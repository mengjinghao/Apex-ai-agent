package com.apex.agent.burstmode.ratelimit

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 限流策略。
 */
sealed class RateLimitStrategy {

    /**
     * 固定窗口限流。
     *
     * 在固定时间窗口内最多允许 N 次请求。
     *
     * @property maxRequests 最大请求数
     * @property windowMs 窗口大小（毫秒）
     */
    data class FixedWindow(
        val maxRequests: Int,
        val windowMs: Long
    ) : RateLimitStrategy()

    /**
     * 令牌桶限流。
     *
     * 以固定速率生成令牌，请求消耗令牌。支持突发流量。
     *
     * @property capacity 桶容量（最大突发量）
     * @property refillRateMs 每 refillRateMs 毫秒补充 1 个令牌
     */
    data class TokenBucket(
        val capacity: Int,
        val refillRateMs: Long
    ) : RateLimitStrategy()

    /**
     * 滑动窗口限流。
     *
     * 基于最近 N 毫秒内的请求计数。
     *
     * @property maxRequests 最大请求数
     * @property windowMs 窗口大小（毫秒）
     */
    data class SlidingWindow(
        val maxRequests: Int,
        val windowMs: Long
    ) : RateLimitStrategy()

    /**
     * 并发数限制。
     *
     * 同时最多允许 N 个请求。
     *
     * @property maxConcurrent 最大并发数
     */
    data class ConcurrentLimit(
        val maxConcurrent: Int
    ) : RateLimitStrategy()

    /**
     * 不限流。
     */
    object Unlimited : RateLimitStrategy()
}

/**
 * 限流决策结果。
 */
data class RateLimitDecision(
    val allowed: Boolean,
    val waitTimeMs: Long = 0,
    val reason: String? = null
) {
    companion object {
        val ALLOWED = RateLimitDecision(allowed = true)
        fun rejected(reason: String) = RateLimitDecision(allowed = false, reason = reason)
        fun delayed(waitMs: Long) = RateLimitDecision(allowed = true, waitTimeMs = waitMs)
    }
}

/**
 * 限流器。
 *
 * 支持多种限流策略，线程安全。
 *
 * # 使用示例
 *
 * ```
 * val limiter = RateLimiter(RateLimitStrategy.TokenBucket(capacity = 10, refillRateMs = 100))
 *
 * // 检查是否允许
 * val decision = limiter.tryAcquire()
 * if (decision.allowed) {
 *     // 执行任务
 * } else {
 *     delay(decision.waitTimeMs)
 * }
 *
 * // 阻塞式获取
 * limiter.acquire()  // 等待直到获得许可
 * ```
 */
class RateLimiter(private val strategy: RateLimitStrategy) {

    private val requestTimestamps = ConcurrentHashMap<Long, AtomicLong>()
    private val tokenCount = AtomicLong(0)
    private val lastRefillTime = AtomicLong(System.currentTimeMillis())
    private val currentConcurrent = AtomicLong(0)

    init {
        when (strategy) {
            is RateLimitStrategy.TokenBucket -> {
                tokenCount.set(strategy.capacity.toLong())
            }
            else -> {}
        }
    }

    /**
     * 尝试获取许可（非阻塞）。
     *
     * @return 限流决策
     */
    fun tryAcquire(): RateLimitDecision {
        return when (strategy) {
            is RateLimitStrategy.FixedWindow -> tryAcquireFixedWindow(strategy)
            is RateLimitStrategy.TokenBucket -> tryAcquireTokenBucket(strategy)
            is RateLimitStrategy.SlidingWindow -> tryAcquireSlidingWindow(strategy)
            is RateLimitStrategy.ConcurrentLimit -> tryAcquireConcurrent(strategy)
            RateLimitStrategy.Unlimited -> RateLimitDecision.ALLOWED
        }
    }

    /**
     * 阻塞式获取许可。
     * 如果被限流，会等待直到获得许可。
     */
    suspend fun acquire() {
        while (true) {
            val decision = tryAcquire()
            if (decision.allowed && decision.waitTimeMs == 0L) return
            if (decision.waitTimeMs > 0) {
                delay(decision.waitTimeMs)
            } else if (!decision.allowed) {
                delay(100)  // 被拒绝时短暂等待后重试
            }
        }
    }

    /**
     * 释放资源（仅 ConcurrentLimit 需要）。
     */
    fun release() {
        if (strategy is RateLimitStrategy.ConcurrentLimit) {
            currentConcurrent.decrementAndGet()
        }
    }

    private fun tryAcquireFixedWindow(strategy: RateLimitStrategy.FixedWindow): RateLimitDecision {
        val now = System.currentTimeMillis()
        val windowStart = now - (now % strategy.windowMs)
        val counter = requestTimestamps.computeIfAbsent(windowStart) { AtomicLong(0) }

        val current = counter.incrementAndGet()
        return if (current <= strategy.maxRequests) {
            RateLimitDecision.ALLOWED
        } else {
            val waitTime = strategy.windowMs - (now % strategy.windowMs)
            RateLimitDecision.rejected("Rate limit exceeded. Retry in ${waitTime}ms")
        }
    }

    private fun tryAcquireTokenBucket(strategy: RateLimitStrategy.TokenBucket): RateLimitDecision {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefillTime.get()
            val newTokens = elapsed / strategy.refillRateMs
            if (newTokens > 0) {
                val newCount = minOf(tokenCount.get() + newTokens, strategy.capacity.toLong())
                tokenCount.set(newCount)
                lastRefillTime.set(now)
            }

            val current = tokenCount.get()
            return if (current > 0) {
                tokenCount.decrementAndGet()
                RateLimitDecision.ALLOWED
            } else {
                val waitTime = strategy.refillRateMs
                RateLimitDecision.delayed(waitTime)
            }
        }
    }

    private fun tryAcquireSlidingWindow(strategy: RateLimitStrategy.SlidingWindow): RateLimitDecision {
        val now = System.currentTimeMillis()
        val windowStart = now - strategy.windowMs

        // 清理过期的时间戳
        requestTimestamps.entries.removeIf { it.key < windowStart }

        // 统计当前窗口内的请求数
        val currentCount = requestTimestamps.values.sumOf { it.get() }
        return if (currentCount < strategy.maxRequests) {
            val counter = requestTimestamps.computeIfAbsent(now) { AtomicLong(0) }
            counter.incrementAndGet()
            RateLimitDecision.ALLOWED
        } else {
            val oldestInWindow = requestTimestamps.keys.minOrNull() ?: now
            val waitTime = oldestInWindow + strategy.windowMs - now
            RateLimitDecision.delayed(waitTime.coerceAtLeast(100))
        }
    }

    private fun tryAcquireConcurrent(strategy: RateLimitStrategy.ConcurrentLimit): RateLimitDecision {
        val current = currentConcurrent.incrementAndGet()
        return if (current <= strategy.maxConcurrent) {
            RateLimitDecision.ALLOWED
        } else {
            currentConcurrent.decrementAndGet()
            RateLimitDecision.delayed(200)  // 短暂等待后重试
        }
    }
}

/**
 * 降级策略。
 *
 * 当系统负载过高或部分功能不可用时，决定如何降级服务。
 */
sealed class DegradationStrategy {

    /**
     * 直接拒绝新请求。
     */
    object Reject : DegradationStrategy()

    /**
     * 使用缓存结果（如果有）。
     */
    object UseCache : DegradationStrategy()

    /**
     * 降级到更简单的处理方式。
     *
     * @property fallbackHandler 降级处理函数
     */
    data class Fallback(val fallbackHandler: () -> Any?) : DegradationStrategy()

    /**
     * 延迟处理后重试。
     *
     * @property delayMs 延迟时间
     * @property maxRetries 最大重试次数
     */
    data class DelayRetry(val delayMs: Long, val maxRetries: Int) : DegradationStrategy()
}

/**
 * 系统负载监控器。
 *
 * 监控系统资源使用情况，决定是否需要降级。
 */
class LoadMonitor(
    private val memoryThresholdMb: Int = 256,
    private val concurrencyThreshold: Int = 16
) {

    enum class LoadLevel {
        LOW,       // 负载低，正常运行
        MEDIUM,    // 负载中等，开始监控
        HIGH,      // 负载高，考虑降级
        CRITICAL   // 负载严重，必须降级
    }

    /**
     * 获取当前负载级别。
     */
    fun getCurrentLoad(currentConcurrency: Int): LoadLevel {
        val runtime = Runtime.getRuntime()
        val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val memoryRatio = usedMemoryMb.toDouble() / memoryThresholdMb
        val concurrencyRatio = currentConcurrency.toDouble() / concurrencyThreshold

        val maxRatio = maxOf(memoryRatio, concurrencyRatio)

        return when {
            maxRatio >= 1.0 -> LoadLevel.CRITICAL
            maxRatio >= 0.8 -> LoadLevel.HIGH
            maxRatio >= 0.5 -> LoadLevel.MEDIUM
            else -> LoadLevel.LOW
        }
    }

    /**
     * 是否需要降级。
     */
    fun shouldDegrade(currentConcurrency: Int): Boolean {
        return getCurrentLoad(currentConcurrency) in listOf(LoadLevel.HIGH, LoadLevel.CRITICAL)
    }

    /**
     * 获取当前内存使用（MB）。
     */
    fun getUsedMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }
}
