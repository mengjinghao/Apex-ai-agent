package com.apex.agent.common.result

/**
 * Either 类型，函数式编程中的错误处理容器。
 *
 * Either 表示一个操作可能产生两种结果之一：
 * - [Left]：通常表示错误、异常或失败情况
 * - [Right]：通常表示成功的值
 *
 * 遵循函数式编程惯例，Right 投影用于成功值，Left 投影用于错误值。
 *
 * 使用示例：
 * ```
 * fun divide(a: Int, b: Int): Either<String, Int> {
 *     if (b == 0) return Either.Left("除数不能为零")
 *     return Either.Right(a / b)
 * }
 *
 * val result = divide(10, 2)
 *     .map { it * 2 }
 *     .getOrDefault(0)
 * ```
 */
sealed class Either<out L, out R> {

    /**
     * Left 值，通常表示错误或异常情况。
     *
     * @param value 错误值
     */
    data class Left<out L>(val value: L) : Either<L, Nothing>()

    /**
     * Right 值，通常表示成功结果。
     *
     * @param value 成功值
     */
    data class Right<out R>(val value: R) : Either<Nothing, R>()

    /** 如果当前值为 [Left] 则返回 true */
    val isLeft: Boolean get() = this is Left

    /** 如果当前值为 [Right] 则返回 true */
    val isRight: Boolean get() = this is Right

    /**
     * 将 Either 转换为一个值，通过提供两个转换函数分别处理 Left 和 Right 情况。
     *
     * @param foldLeft 处理 Left 值的函数
     * @param foldRight 处理 Right 值的函数
     * @return 转换后的值
     */
    fun <T> fold(foldLeft: (L) -> T, foldRight: (R) -> T): T = when (this) {
        is Left -> foldLeft(value)
        is Right -> foldRight(value)
    }

    /**
     * 如果当前值为 [Right]，则对其应用转换函数；否则直接返回 [Left]。
     *
     * @param transform 对 Right 值进行转换的函数
     * @return 转换后的 Either
     */
    fun <T> map(transform: (R) -> T): Either<L, T> = when (this) {
        is Left -> this
        is Right -> Right(transform(value))
    }

    /**
     * 如果当前值为 [Right]，则对其应用返回 Either 的转换函数；否则直接返回 [Left]。
     * 用于链式调用多个返回 Either 的操作。
     *
     * @param transform 返回 Either 的转换函数
     * @return 嵌套展开后的 Either
     */
    fun <T> flatMap(transform: (R) -> Either<L, T>): Either<L, T> = when (this) {
        is Left -> this
        is Right -> transform(value)
    }

    /** 如果当前值为 [Right] 则返回其值，否则返回 null */
    fun getOrNull(): R? = when (this) {
        is Left -> null
        is Right -> value
    }

    /**
     * 如果当前值为 [Right] 则返回其值，否则返回默认值。
     *
     * @param default 当为 Left 时返回的默认值
     * @return Right 的值或默认值
     */
    fun getOrDefault(default: @UnsafeVariance R): R = when (this) {
        is Left -> default
        is Right -> value
    }

    /** 如果当前值为 [Left] 则返回其值，否则返回 null */
    fun leftOrNull(): L? = when (this) {
        is Left -> value
        is Right -> null
    }

    companion object {
        /**
         * 创建一个成功的 Either（Right 值）。
         *
         * @param value 成功值
         * @return 包含成功值的 Either
         */
        fun <R> success(value: R): Either<Nothing, R> = Right(value)

        /**
         * 创建一个错误的 Either（Left 值）。
         *
         * @param value 错误值
         * @return 包含错误值的 Either
         */
        fun <L> error(value: L): Either<L, Nothing> = Left(value)
    }
}

/**
 * 将 [Either] 转换为 [Result] 类型。
 * - Left (Throwable) 转换为 Result.Failure
 * - Right (T) 转换为 Result.Success
 */
fun <T> Either<Throwable, T>.toResult(): Result<T> = when (this) {
    is Either.Left -> Result.Failure(value)
    is Either.Right -> Result.Success(value)
}

/**
 * 将 [Result] 转换为 [Either] 类型。
 * - Result.Success 转换为 Either.Right
 * - Result.Failure 转换为 Either.Left
 */
fun <T> Result<T>.toEither(): Either<Throwable, T> = when (this) {
    is Result.Success -> Either.Right(data)
    is Result.Failure -> Either.Left(error)
}
