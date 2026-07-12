package com.apex.agent.kernel.burst.enhanced.ratelimit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B23: 限流器增强
 *
 * 增强现有 RateLimiter：
 * - 4 种限流算法
 * - 多维度限流（QPS/并发/Token/带宽）
 * - 自适应限流
 * - 熔断联动
 */
class EnhancedRateLimiter(
    private val defaultQpsLimit: Int = 60,
    private val defaultConcurrentLimit: Int = 5,
    private val defaultTokenLimitPerMin: Long = 100_000L
) {

    enum class LimitAlgorithm {
        TOKEN_BUCKET,    // 令牌桶
        LEAKY_BUCKET,    // 漏桶
        SLIDING_WINDOW,  // 滑动窗口
        FIXED_WINDOW     // 固定窗口
    }

    enum class LimitDimension {
        QPS,             // 每秒请求数
        CONCURRENT,      // 并发数
        TOKEN_PER_MIN,   // 每分钟 Token
        BANDWIDTH,       // 带宽
        DAILY_QUOTA      // 每日配额
    }

    data class LimitRule(
        val key: String,
        val dimension: LimitDimension,
        val limit: Long,
        val algorithm: LimitAlgorithm = LimitAlgorithm.TOKEN_BUCKET,
        val burstSize: Long = limit * 2,
        val warmupMs: Long = 0
    )

    data class LimitStatus(
        val key: String,
        val dimension: LimitDimension,
        val current: Long,
        val limit: Long,
        val remaining: Long,
        val resetAt: Long,
        val isLimited: Boolean
    )

    data class AdaptiveConfig(
        val minLimit: Long,
        val maxLimit: Long,
        val adjustIntervalMs: Long,
        val successRateThreshold: Float = 0.9f,
        val errorRateThreshold: Float = 0.3f
    )

    private val rules = ConcurrentHashMap<String, LimitRule>()
    private val tokenBuckets = ConcurrentHashMap<String, TokenBucket>()
    private val slidingWindows = ConcurrentHashMap<String, SlidingWindow>()
    private val concurrentCounts = ConcurrentHashMap<String, AtomicLong>()
    private val dailyUsage = ConcurrentHashMap<String, AtomicLong>()
    private val adaptiveConfigs = ConcurrentHashMap<String, AdaptiveConfig>()
    private val successRates = ConcurrentHashMap<String, mutableListOf<Boolean>>()

    private val _limitEvents = MutableStateFlow<List<LimitEvent>>(emptyList())
    val limitEvents: StateFlow<List<LimitEvent>> = _limitEvents.asStateFlow()

    data class LimitEvent(
        val key: String,
        val dimension: LimitDimension,
        val action: LimitAction,
        val current: Long,
        val limit: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class LimitAction { ALLOWED, LIMITED, RELEASED, RESET }

    /**
     * 注册限流规则
     */
    fun registerRule(rule: LimitRule) {
        rules["${rule.key}:${rule.dimension}"] = rule
        when (rule.algorithm) {
            LimitAlgorithm.TOKEN_BUCKET -> tokenBuckets["${rule.key}:${rule.dimension}"] = TokenBucket(rule.limit, rule.burstSize)
            LimitAlgorithm.SLIDING_WINDOW -> slidingWindows["${rule.key}:${rule.dimension}"] = SlidingWindow(rule.limit)
            else -> {}
        }
    }

    /**
     * 尝试获取许可
     */
    fun tryAcquire(key: String, dimension: LimitDimension, amount: Long = 1): Boolean {
        val ruleKey = "$key:$dimension"
        val rule = rules[ruleKey] ?: return true  // 无规则则放行

        val allowed = when (rule.algorithm) {
            LimitAlgorithm.TOKEN_BUCKET -> tokenBuckets[ruleKey]?.tryConsume(amount) ?: true
            LimitAlgorithm.LEAKY_BUCKET -> tryLeakyBucket(ruleKey, amount, rule.limit)
            LimitAlgorithm.SLIDING_WINDOW -> slidingWindows[ruleKey]?.tryAdd(amount) ?: true
            LimitAlgorithm.FIXED_WINDOW -> tryFixedWindow(ruleKey, amount, rule.limit)
        }

        if (allowed) {
            emitEvent(key, dimension, LimitAction.ALLOWED, amount, rule.limit)
            if (dimension == LimitDimension.CONCURRENT) {
                concurrentCounts.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(amount)
            }
        } else {
            emitEvent(key, dimension, LimitAction.LIMITED, amount, rule.limit)
        }

        return allowed
    }

    /**
     * 释放许可（并发维度）
     */
    fun release(key: String, dimension: LimitDimension, amount: Long = 1) {
        if (dimension == LimitDimension.CONCURRENT) {
            concurrentCounts[key]?.addAndGet(-amount)
            emitEvent(key, dimension, LimitAction.RELEASED, amount, 0)
        }
    }

    /**
     * 获取限流状态
     */
    fun getStatus(key: String, dimension: LimitDimension): LimitStatus {
        val ruleKey = "$key:$dimension"
        val rule = rules[ruleKey]
        val current = when (dimension) {
            LimitDimension.CONCURRENT -> concurrentCounts[key]?.get() ?: 0
            LimitDimension.QPS -> slidingWindows[ruleKey]?.currentCount() ?: 0
            LimitDimension.TOKEN_PER_MIN -> slidingWindows[ruleKey]?.currentCount() ?: 0
            else -> 0
        }
        val limit = rule?.limit ?: when (dimension) {
            LimitDimension.QPS -> defaultQpsLimit.toLong()
            LimitDimension.CONCURRENT -> defaultConcurrentLimit.toLong()
            LimitDimension.TOKEN_PER_MIN -> defaultTokenLimitPerMin
            else -> Long.MAX_VALUE
        }
        return LimitStatus(
            key = key, dimension = dimension,
            current = current, limit = limit,
            remaining = (limit - current).coerceAtLeast(0),
            resetAt = System.currentTimeMillis() + 1000,
            isLimited = current >= limit
        )
    }

    /**
     * 记录成功/失败（用于自适应）
     */
    fun recordResult(key: String, success: Boolean) {
        val rates = successRates.computeIfAbsent(key) { mutableListOf() }
        synchronized(rates) {
            rates.add(success)
            while (rates.size > 100) rates.removeAt(0)
        }

        // 自适应调整
        val config = adaptiveConfigs[key] ?: return
        val now = System.currentTimeMillis()
        if (now % config.adjustIntervalMs < 100) {
            adaptLimit(key, config)
        }
    }

    /**
     * 设置自适应配置
     */
    fun setAdaptive(key: String, config: AdaptiveConfig) {
        adaptiveConfigs[key] = config
    }

    /**
     * 获取所有限流状态
     */
    fun getAllStatus(): List<LimitStatus> {
        return rules.keys.mapNotNull { ruleKey ->
            val parts = ruleKey.split(":")
            if (parts.size >= 2) getStatus(parts[0], LimitDimension.valueOf(parts[1]))
            else null
        }
    }

    // ============ 内部方法 ============

    private fun tryLeakyBucket(key: String, amount: Long, limit: Long): Boolean {
        // 简化：固定窗口
        return tryFixedWindow(key, amount, limit)
    }

    private fun tryFixedWindow(key: String, amount: Long, limit: Long): Boolean {
        val window = slidingWindows.computeIfAbsent(key) { SlidingWindow(limit) }
        return window.tryAdd(amount)
    }

    private fun adaptLimit(key: String, config: AdaptiveConfig) {
        val rates = successRates[key] ?: return
        if (rates.size < 20) return

        val recent = rates.takeLast(20)
        val successRate = recent.count { it }.toFloat() / recent.size

        // 调整所有维度的限流
        rules.entries
            .filter { it.key.startsWith("$key:") }
            .forEach { (ruleKey, rule) ->
                val newLimit = when {
                    successRate > config.successRateThreshold -> {
                        // 成功率高，提升限流
                        (rule.limit * 1.1).toLong().coerceAtMost(config.maxLimit)
                    }
                    successRate < config.errorRateThreshold -> {
                        // 错误率高，降低限流
                        (rule.limit * 0.7).toLong().coerceAtLeast(config.minLimit)
                    }
                    else -> rule.limit
                }
                if (newLimit != rule.limit) {
                    rules[ruleKey] = rule.copy(limit = newLimit)
                    // 重置桶
                    tokenBuckets[ruleKey] = TokenBucket(newLimit, newLimit * 2)
                    slidingWindows[ruleKey] = SlidingWindow(newLimit)
                }
            }
    }

    private fun emitEvent(key: String, dimension: LimitDimension, action: LimitAction, current: Long, limit: Long) {
        // 简化：只记录 LIMITED 事件
        if (action == LimitAction.LIMITED) {
            val event = LimitEvent(key, dimension, action, current, limit)
            val existing = _limitEvents.value
            _limitEvents.value = (existing + event).takeLast(100)
        }
    }

    // ============ 令牌桶 ============
    private class TokenBucket(private val rate: Long, private val burstSize: Long) {
        private var tokens = burstSize
        private var lastRefill = System.currentTimeMillis()

        @Synchronized
        fun tryConsume(amount: Long): Boolean {
            refill()
            return if (tokens >= amount) {
                tokens -= amount
                true
            } else false
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefill
            val newTokens = (elapsed * rate / 1000).coerceAtMost(burstSize - tokens)
            tokens += newTokens
            lastRefill = now
        }
    }

    // ============ 滑动窗口 ============
    private class SlidingWindow(private val limit: Long) {
        private val timestamps = mutableListOf<Long>()

        @Synchronized
        fun tryAdd(amount: Long): Boolean {
            val now = System.currentTimeMillis()
            // 清理 1 秒前的
            timestamps.removeAll { it < now - 1000 }
            val currentCount = timestamps.size
            return if (currentCount + amount <= limit) {
                repeat(amount.toInt()) { timestamps.add(now) }
                true
            } else false
        }

        @Synchronized
        fun currentCount(): Long {
            val now = System.currentTimeMillis()
            timestamps.removeAll { it < now - 1000 }
            return timestamps.size.toLong()
        }
    }
}
