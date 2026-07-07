package com.apex.agent.common.result

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 挂起等待 Result 的结果。
 * 如果为 [Result.Success] 返回数据，如果为 [Result.Failure] 则抛出异常。
 *
 * @return 成功数据
 * @throws Throwable 如果结果为 Failure
 */
suspend fun <T> Result<T>.await(): T = when (this) {
    is Result.Success -> data
    is Result.Failure -> throw error
}

/**
 * 对成功结果应用可能抛出异常的转换函数，并将异常捕获为新的 Failure。
 *
 * @param transform 可能抛出异常的转换函数
 * @return 转换后的 Result，如果转换抛出异常则返回 Failure
 */
fun <T, R> Result<T>.mapCatching(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> {
        try {
            Result.Success(transform(data))
        } catch (e: Throwable) {
            Result.Failure(e)
        }
    }
    is Result.Failure -> this
}

/**
 * 将 Result 转换为 Flow，成功时发射数据，失败时发射错误。
 *
 * @return 包装为 Flow 的 Result
 */
fun <T> Result<T>.asFlow(): Flow<T> = when (this) {
    is Result.Success -> flowOf(data)
    is Result.Failure -> kotlinx.coroutines.flow.flow { throw error }
}

/**
 * 将 Result 转换为可空值，并提供自定义恢复函数。
 *
 * @param recover 将异常转换为可空值的函数，默认返回 null
 * @return 成功数据或通过 recover 转换后的值
 */
fun <T> Result<T>.orElse(recover: (Throwable) -> T? = { null }): T? = when (this) {
    is Result.Success -> data
    is Result.Failure -> recover(error)
}
