package com.apex.agent.common.result

/**
 * Result 类型 - 表示一个操作可能成功或失败。
 *
 * 这是 Kotlin 标准库 Result 的替代实现，提供了更丰富的操作符和更好的兼容性。
 *
 * 使用示例：
 * ```
 * val result = runCatching { fetchUserFromNetwork() }
 * result
 *     .onSuccess { user -> updateUI(user) }
 *     .onFailure { error -> showError(error.message) }
 *
 * val data = result.getOrDefault(defaultUser)
 * ```
 */
sealed class Result<out T> {

    /**
     * 成功结果，包含成功数据。
     *
     * @param data 成功时的数据
     */
    data class Success<out T>(val data: T) : Result<T>()

    /**
     * 失败结果，包含异常信息。
     *
     * @param error 失败时的异常
     */
    data class Failure(val error: Throwable) : Result<Nothing>()

    /** 如果结果为 [Success] 则返回 true */
    fun isSuccess(): Boolean = this is Success

    /** 如果结果为 [Failure] 则返回 true */
    fun isFailure(): Boolean = this is Failure

    /** 如果结果为 [Success] 则返回数据，否则返回 null */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    /**
     * 如果结果为 [Success] 则返回数据，否则返回默认值。
     *
     * @param default 失败时返回的默认值
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> default
    }

    /**
     * 对成功结果应用转换函数，返回新的 Result。
     *
     * @param transform 转换函数
     * @return 转换后的 Result
     */
    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    /**
     * 对成功结果应用返回 Result 的转换函数，用于链式调用。
     *
     * @param transform 返回 Result 的转换函数
     * @return 嵌套展开后的 Result
     */
    fun <R> flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }

    /**
     * 从失败中恢复：如果失败则使用恢复函数返回默认值。
     *
     * @param recover 恢复函数，接收异常并返回默认值
     * @return 成功时的数据，或恢复函数返回的值
     */
    fun recover(recover: (Throwable) -> @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> recover(error)
    }

    /**
     * 如果结果为 [Success]，执行指定操作。
     *
     * @param action 对成功数据执行的操作
     * @return 当前 Result，用于链式调用
     */
    fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    /**
     * 如果结果为 [Failure]，执行指定操作。
     *
     * @param action 对异常执行的操作
     * @return 当前 Result，用于链式调用
     */
    fun onFailure(action: (Throwable) -> Unit): Result<T> {
        if (this is Failure) action(error)
        return this
    }

    /**
     * 将 Result 转换为单个值，提供成功和失败两种情况的处理函数。
     *
     * @param onSuccess 成功时的转换函数
     * @param onFailure 失败时的转换函数
     * @return 转换后的值
     */
    fun <R> fold(onSuccess: (T) -> R, onFailure: (Throwable) -> R): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }

    /**
     * 如果成功则返回值，如果失败则抛出异常。
     * 用于在确信操作不会失败时快速获取值。
     *
     * @return 成功的数据
     * @throws Throwable 如果结果为 [Failure]
     */
    fun throwOnFailure(): T = when (this) {
        is Success -> data
        is Failure -> throw error
    }

    companion object {
        /**
         * 创建一个成功的 Result。
         *
         * @param data 成功数据
         */
        fun <T> success(data: T): Result<T> = Success(data)

        /**
         * 创建一个失败的 Result。
         *
         * @param error 异常信息
         */
        fun failure(error: Throwable): Result<Nothing> = Failure(error)
    }
}
