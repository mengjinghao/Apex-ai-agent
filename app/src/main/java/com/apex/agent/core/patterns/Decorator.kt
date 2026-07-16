package com.apex.agent.core.patterns

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis

/**
 * 装饰器模式 - 工具执行器增强
 * 通过装饰器为 ToolExecutor 动态添加日志、指标、缓存、重试、超时和限流能力
 */

/** 组件接口 */
interface Component<T, R> {
    suspend fun execute(input: T): R
}

/** 抽象装饰器 */
abstract class Decorator<T, R>(protected val wrapped: Component<T, R>) : Component<T, R> {
    override suspend fun execute(input: T): R = wrapped.execute(input)
}

/** 日志装饰器 */
class LoggingDecorator<T, R>(wrapped: Component<T, R>, private val tag: String = "ToolExecutor")
    : Decorator<T, R>(wrapped) {

    override suspend fun execute(input: T): R {
        val result = wrapped.execute(input)
        android.util.Log.d(tag, "Execute with input=$input, result=$result")
        return result
    }
}

/** 指标装饰器 */
class MetricsDecorator<T, R>(wrapped: Component<T, R>) : Decorator<T, R>(wrapped) {

    private var executionCount = 0L
    private var totalTime = 0L

    override suspend fun execute(input: T): R {
        var result: R
        val elapsed = measureTimeMillis {
            result = wrapped.execute(input)
        }
        executionCount++
        totalTime += elapsed
        return result
    }

    fun getAverageTime(): Double = if (executionCount > 0) totalTime.toDouble() / executionCount else 0.0
    fun getExecutionCount(): Long = executionCount
}

/** 缓存装饰器 */
class CachingDecorator<T, R>(wrapped: Component<T, R>, private val maxSize: Int = 100)
    : Decorator<T, R>(wrapped) {

    private val cache = LinkedHashMap<T, R>(maxSize, 0.75f, true)

    override suspend fun execute(input: T): R {
        return cache.getOrPut(input) { wrapped.execute(input) }.also {
            if (cache.size > maxSize) {
                cache.remove(cache.keys.first())
            }
        }
    }

    fun clearCache() = cache.clear()
    fun cacheSize(): Int = cache.size
}

/** 重试装饰器 */
class RetryDecorator<T, R>(
    wrapped: Component<T, R>,
    private val maxRetries: Int = 3,
    private val delayMs: Long = 100
) : Decorator<T, R>(wrapped) {

    override suspend fun execute(input: T): R {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return wrapped.execute(input)
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) delay(delayMs * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("Retry failed after $maxRetries attempts")
    }
}

/** 超时装饰器 */
class TimeoutDecorator<T, R>(wrapped: Component<T, R>, private val timeoutMs: Long = 5000)
    : Decorator<T, R>(wrapped) {

    override suspend fun execute(input: T): R {
        return withTimeout(timeoutMs) { wrapped.execute(input) }
    }
}

/** 限流装饰器 */
class RateLimitingDecorator<T, R>(
    wrapped: Component<T, R>,
    private val maxOps: Int = 10,
    private val windowMs: Long = 1000
) : Decorator<T, R>(wrapped) {

    private val timestamps = ArrayDeque<Long>()

    override suspend fun execute(input: T): R {
        val now = System.currentTimeMillis()
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxOps) {
            throw RateLimitExceededException("Rate limit: max $maxOps ops per ${windowMs}ms")
        }
        timestamps.addLast(now)
        return wrapped.execute(input)
    }
}

class RateLimitExceededException(message: String) : Exception(message)
