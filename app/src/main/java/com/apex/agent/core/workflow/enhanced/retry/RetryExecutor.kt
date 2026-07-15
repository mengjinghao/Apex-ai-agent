package com.apex.agent.core.workflow.enhanced.retry

import com.apex.agent.core.workflow.enhanced.model.RetryPolicyDef
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 错误分类 - 用于决定是否重试
 *
 * 参照 AWS Prescriptive Guidance 的重试模式分类
 */
enum class ErrorCategory {
    /** 瞬时错误（网络抖动、临时不可用） - 可重试 */
    TRANSIENT,
    /** 超时 - 可重试 */
    TIMEOUT,
    /** 限流 - 可重试（需退避） */
    RATE_LIMIT,
    /** 网络错误 - 可重试 */
    NETWORK,
    /** 校验错误 - 不可重试 */
    VALIDATION,
    /** 权限错误 - 不可重试 */
    PERMISSION,
    /** 持久性错误 - 不可重试 */
    PERSISTENT,
    /** 未知错误 - 默认可重试 */
    UNKNOWN
}

/**
 * 错误分类器接口
 * 业务侧可实现此接口，根据异常类型决定错误分类
 */
fun interface ErrorClassifier {
    fun classify(throwable: Throwable): ErrorCategory
}

/**
 * 默认错误分类器 - 基于异常类型与消息关键字匹配
 */
class DefaultErrorClassifier : ErrorClassifier {
    override fun classify(throwable: Throwable): ErrorCategory {
        val message = throwable.message?.lowercase() ?: ""
        val typeName = throwable::class.simpleName?.lowercase() ?: ""
        return when {
            // 权限类
            typeName.contains("security") || typeName.contains("permission") ||
                typeName.contains("auth") || message.contains("unauthorized") ||
                message.contains("forbidden") || message.contains("403") -> ErrorCategory.PERMISSION

            // 校验类
            typeName.contains("illegalargument") || typeName.contains("validation") ||
                message.contains("invalid") || message.contains("bad request") ||
                message.contains("400") -> ErrorCategory.VALIDATION

            // 超时
            typeName.contains("timeout") || typeName.contains("cancelled") ||
                message.contains("timed out") || message.contains("deadline") -> ErrorCategory.TIMEOUT

            // 限流
            message.contains("rate limit") || message.contains("too many requests") ||
                message.contains("429") || message.contains("throttl") -> ErrorCategory.RATE_LIMIT

            // 网络
            typeName.contains("ioexception") || typeName.contains("socket") ||
                typeName.contains("connect") || message.contains("network") ||
                message.contains("connection") || message.contains("503") ||
                message.contains("502") || message.contains("504") -> ErrorCategory.NETWORK

            // 持久性
            message.contains("not found") || message.contains("404") ||
                message.contains("gone") || message.contains("410") -> ErrorCategory.PERSISTENT

            else -> ErrorCategory.UNKNOWN
        }
    }
}

/**
 * 断路器 - 防止下游服务持续故障时拖垮整个引擎
 *
 * 三态：
 * - CLOSED: 正常，请求通过
 * - OPEN: 熔断，所有请求快速失败
 * - HALF_OPEN: 半开，允许少量试探请求
 *
 * 参照 Martin Fowler 的 Circuit Breaker 模式与 AWS 的 retry-with-backoff 指南
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openDurationMs: Long = 60_000L,
    private val halfOpenTrialLimit: Int = 1,
    private val onStateChange: ((State, State) -> Unit)? = null
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }
        private val state = AtomicReference(State.CLOSED)
        private val failureCount = AtomicInteger(0)
        private val successCount = AtomicInteger(0)
        private val openedAt = AtomicLong(0)
        private val halfOpenTrials = AtomicInteger(0)
        val currentState: State get() = state.get()

    /**
     * 检查是否允许请求通过
     * @throws CircuitOpenException 断路器开启时抛出
     */
    fun tryAcquire() {
        val now = System.currentTimeMillis()
        when (state.get()) {
            State.CLOSED -> { /* 允许 */ }
            State.OPEN -> {
                if (now - openedAt.get() >= openDurationMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenTrials.set(0)
                        onStateChange?.invoke(State.OPEN, State.HALF_OPEN)
                    }
                } else {
                    throw CircuitOpenException("断路器开启中，剩余 ${openDurationMs - (now - openedAt.get())}ms")
                }
            }
            State.HALF_OPEN -> {
                if (halfOpenTrials.incrementAndGet() > halfOpenTrialLimit) {
                    throw CircuitOpenException("断路器半开状态，试探请求已满")
                }
            }
        }
    }

    /** 记录成功 */
    fun recordSuccess() {
        failureCount.set(0)
        if (state.get() == State.HALF_OPEN) {
            if (successCount.incrementAndGet() >= halfOpenTrialLimit) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    onStateChange?.invoke(State.HALF_OPEN, State.CLOSED)
                }
            }
        }
    }

    /** 记录失败 */
    fun recordFailure() {
        successCount.set(0)
        if (state.get() == State.HALF_OPEN) {
            // 半开状态下失败，重新熔断
    if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis())
                onStateChange?.invoke(State.HALF_OPEN, State.OPEN)
            }
        } else if (state.get() == State.CLOSED) {
            if (failureCount.incrementAndGet() >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(System.currentTimeMillis())
                    onStateChange?.invoke(State.CLOSED, State.OPEN)
                }
            }
        }
    }

    /** 手动重置 */
    fun reset() {
        state.set(State.CLOSED)
        failureCount.set(0)
        successCount.set(0)
        halfOpenTrials.set(0)
    }
}

class CircuitOpenException(message: String) : RuntimeException(message)

/**
 * 断路器注册表 - 按 (workflowId + nodeId) 维度缓存
 */
object CircuitBreakerRegistry {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()
        fun get(key: String, config: CircuitBreakerConfig = CircuitBreakerConfig()): CircuitBreaker {
        return breakers.computeIfAbsent(key) {
            CircuitBreaker(
                failureThreshold = config.failureThreshold,
                openDurationMs = config.openDurationMs,
                halfOpenTrialLimit = config.halfOpenTrialLimit,
                onStateChange = config.onStateChange
            )
        }
    }
        fun reset(key: String) = breakers.remove(key)
        fun resetAll() = breakers.clear()
        fun snapshot(): Map<String, CircuitBreaker.State> = breakers.mapValues { it.value.currentState }
}

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val openDurationMs: Long = 60_000L,
    val halfOpenTrialLimit: Int = 1,
    val onStateChange: ((CircuitBreaker.State, CircuitBreaker.State) -> Unit)? = null
)

/**
 * 重试执行器 - 实现指数退避 + 抖动
 *
 * 参照 Temporal 的 RetryPolicy 与 AWS 的 Exponential Backoff With Jitter
 */
class RetryExecutor(
    private val classifier: ErrorClassifier = DefaultErrorClassifier()
) {
    /**
     * 带重试地执行块
     * @param policy 重试策略
     * @param breaker 可选断路器
     * @param block 业务逻辑，attempt 为当前尝试次数（从 1 开始）
     */
    suspend fun <T> runWithRetry(
        policy: RetryPolicyDef,
        breaker: CircuitBreaker? = null,
        block: suspend (attempt: Int) -> T
    ): T {
        var lastError: Throwable? = null
        val maxAttempts = policy.maxAttempts.coerceAtLeast(1)
        for (attempt in 1..maxAttempts) {
            try {
                breaker?.tryAcquire()
        val result = block(attempt)
                breaker?.recordSuccess()
        return result
            } catch (e: CircuitOpenException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                breaker?.recordFailure()
        val category = classifier.classify(e)
        if (category in parseNonRetryable(policy.nonRetryableErrorCategories)) {
                    throw e
                }
        if (category !in parseRetryable(policy.retryableErrorCategories) && category != ErrorCategory.UNKNOWN) {
                    throw e
                }
        if (attempt >= maxAttempts) break

                val delayMs = computeBackoff(policy, attempt)
                delay(delayMs)
            }
        }
        throw lastError ?: RuntimeException("重试耗尽但无异常")
    }

    /**
     * 计算退避时间（指数退避 + 抖动）
     */
    private fun computeBackoff(policy: RetryPolicyDef, attempt: Int): Long {
        val base = policy.initialIntervalMs *
            Math.pow(policy.backoffCoefficient, (attempt - 1).toDouble())
        val capped = min(base.roundToLong(), policy.maxIntervalMs)
        if (policy.jitterRatio <= 0f) return capped
        val jitter = capped * policy.jitterRatio
        val minJ = (capped - jitter).toLong().coerceAtLeast(0)
        val maxJ = (capped + jitter).toLong()
        return if (maxJ <= minJ) capped else Random.nextLong(minJ, maxJ + 1)
    }
        private fun parseRetryable(set: Set<String>): Set<ErrorCategory> =
        set.mapNotNull { runCatching { ErrorCategory.valueOf(it) }.getOrNull() }.toSet()
        private fun parseNonRetryable(set: Set<String>): Set<ErrorCategory> =
        set.mapNotNull { runCatching { ErrorCategory.valueOf(it) }.getOrNull() }.toSet()
}
