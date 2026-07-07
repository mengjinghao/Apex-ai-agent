package com.apex.agent.common.extensions

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen

/**
 * [Flow] 的扩展函数集合，提供错误处理、节流、重试、状态获取等便捷操作。
 */

/**
 * 捕获 [Flow] 中的异常并发射默认值代替。
 * 若 [defaultValue] 为 null 则静默吞掉异常，不发射任何值。
 *
 * @param defaultValue 发生异常时发射的默认值（可选）
 * @return 安全处理后的 Flow
 */
fun <T> Flow<T>.result(defaultValue: T? = null): Flow<T> = catch { e ->
    if (defaultValue != null) {
        emit(defaultValue)
    }
}

/**
 * 在 Flow 开始收集时执行 [block]。
 *
 * @param block 起始回调（可挂起）
 * @return 包装后的 Flow
 */
fun <T> Flow<T>.onStart(block: suspend () -> Unit): Flow<T> = flow {
    block()
    collect { emit(it) }
}

/**
 * 在 Flow 完成收集时执行 [block]（无论正常完成还是异常）。
 *
 * @param block 完成回调，参数为异常原因（正常完成为 null）
 * @return 包装后的 Flow
 */
fun <T> Flow<T>.onCompletion(block: suspend (cause: Throwable?) -> Unit): Flow<T> = flow {
    try {
        collect { emit(it) }
        block(null)
    } catch (e: Throwable) {
        block(e)
        throw e
    }
}

/**
 * 节流操作：在 [periodMillis] 时间内只发射第一个值，之后的值被忽略。
 * 适用于搜索框输入等高频触发场景。
 *
 * @param periodMillis 节流时间窗口（毫秒）
 * @return 节流后的 Flow
 */
fun <T> Flow<T>.throttleFirst(periodMillis: Long): Flow<T> = flow {
    var lastEmissionTime = 0L
    collect { value ->
        val now = System.currentTimeMillis()
        if (now - lastEmissionTime >= periodMillis) {
            lastEmissionTime = now
            emit(value)
        }
    }
}

/**
 * 带指数退避延迟的重试机制。
 * 每次重试的等待时间 = initialDelay * (2 ^ attempt)，最大不超过 [maxDelay]。
 *
 * @param maxRetries   最大重试次数，默认 3
 * @param initialDelay 初始延迟（毫秒），默认 100ms
 * @param maxDelay     最大延迟（毫秒），默认 5000ms
 * @return 重试安全后的 Flow
 */
fun <T> Flow<T>.retryWithDelay(
    maxRetries: Int = 3,
    initialDelay: Long = 100,
    maxDelay: Long = 5000
): Flow<T> = retryWhen { cause, attempt ->
    if (attempt >= maxRetries) {
        false
    } else {
        val delayMs = (initialDelay * Math.pow(2.0, attempt.toDouble()))
            .toLong().coerceAtMost(maxDelay)
        delay(delayMs)
        true
    }
}

/**
 * 安全的 [flatMapLatest] 版本：转换函数或内部 Flow 抛出异常时静默忽略，
 * 不会中断整个 Flow。
 *
 * @param transform 将值转换为 Flow 的转换函数
 * @return 转换后的 Flow
 */
fun <T, R> Flow<T>.flatMapLatestSafe(transform: (T) -> Flow<R>): Flow<R> = flatMapLatest { value ->
    try {
        transform(value).catch { /* 忽略内部 Flow 的异常 */ }
    } catch (_: Exception) {
        emptyFlow()
    }
}

/**
 * 获取 [StateFlow] 的当前值，异常时返回 null。
 * 适用于需要非空安全的场景。
 */
val <T> StateFlow<T>.valueOrNull: T?
    get() = try {
        value
    } catch (_: Exception) {
        null
    }
