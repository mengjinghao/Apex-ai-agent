package com.apex.agent.common.result

import com.apex.agent.core.util.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 应用级错误层次结构。
 *
 * 提供统一的错误类型系统，用于在整个应用中表达和处理不同类别的错误。
 * 每个错误类别包含：
 * - [code]：错误码，用于程序化处理
 * - [message]：错误消息，用于用户显示
 * - [cause]：原始异常（可选）
 *
 * 使用示例：
 * ```
 * val error = AppError.NetworkError(
 *     message = "无法连接到服务器",
 *     cause = IOException("Connection refused")
 * )
 * ```
 */
sealed class AppError(
    /** 错误码，用于程序化识别错误类型 */
    val code: String,
    /** 用户可读的错误描述信息 */
    val message: String
) {

    /**
     * 网络错误 - 网络请求失败、连接中断等。
     *
     * @param code 错误码，默认为 "NETWORK_ERROR"
     * @param message 错误消息
     * @param cause 原始异常
     */

    /**
     * 数据库错误 - 数据库读写失败、查询超时等。
     *
     * @param code 错误码，默认为 "DB_ERROR"
     * @param message 错误消息
     * @param cause 原始异常
     */
    class DatabaseError(
        code: String = "DB_ERROR",
        message: String,
        cause: Throwable? = null
    ) : AppError(code, message) {
        val cause: Throwable? = cause
    }

    /**
     * 认证错误 - 用户未登录、Token 过期、权限不足等。
     *
     * @param code 错误码，默认为 "AUTH_ERROR"
     * @param message 错误消息
     * @param cause 原始异常
     */
    class AuthError(
        code: String = "AUTH_ERROR",
        message: String,
        cause: Throwable? = null
    ) : AppError(code, message) {
        val cause: Throwable? = cause
    }

    /**
     * 验证错误 - 输入数据不合法、格式错误等。
     *
     * @param code 错误码，默认为 "VALIDATION_ERROR"
     * @param message 错误消息
     */

    /**
     * 超时错误 - 操作执行超时。
     *
     * @param code 错误码，默认为 "TIMEOUT"
     * @param message 错误消息，默认为 "操作超时"
     */
    class TimeoutError(
        code: String = "TIMEOUT",
        message: String = "操作超时"
    ) : AppError(code, message)

    /**
     * 未找到错误 - 请求的资源不存在。
     *
     * @param code 错误码，默认为 "NOT_FOUND"
     * @param message 错误消息
     */
    class NotFoundError(
        code: String = "NOT_FOUND",
        message: String
    ) : AppError(code, message)

    /**
     * 权限错误 - 缺少必要的权限。
     *
     * @param code 错误码，默认为 "PERMISSION_DENIED"
     * @param message 错误消息
     */
    class PermissionError(
        code: String = "PERMISSION_DENIED",
        message: String
    ) : AppError(code, message)

    /**
     * 未知错误 - 无法归类的错误。
     *
     * @param code 错误码，默认为 "UNKNOWN"
     * @param message 错误消息，默认为 "未知错误"
     * @param cause 原始异常
     */
    class UnknownError(
        code: String = "UNKNOWN",
        message: String = "未知错误",
        cause: Throwable? = null
    ) : AppError(code, message) {
        val cause: Throwable? = cause
    }

    /**
     * 解析错误 - 数据解析失败，如 JSON/XML 解析错误。
     *
     * @param code 错误码，默认为 "PARSE_ERROR"
     * @param message 错误消息
     * @param cause 原始异常
     */

    companion object {
        /**
         * 根据 Throwable 类型自动匹配合适的 AppError。
         *
         * 转换规则：
         * - [SocketTimeoutException] -> [TimeoutError]
         * - [ConnectException]、[UnknownHostException]、[SSLException] -> [NetworkError]
         * - 其他 -> [UnknownError]
         *
         * @param throwable 原始异常
         * @return 匹配的 AppError 实例
         */
        fun fromThrowable(throwable: Throwable): AppError = when (throwable) {
            is SocketTimeoutException -> TimeoutError(
                message = throwable.message ?: "连接超时"
            )
            is ConnectException -> NetworkError(
                message = throwable.message ?: "连接失败",
                cause = throwable
            )
            is UnknownHostException -> NetworkError(
                message = "无法解析服务器地址",
                cause = throwable
            )
            is SSLException -> NetworkError(
                message = "SSL 连接错误",
                cause = throwable
            )
            is IOException -> NetworkError(
                message = throwable.message ?: "网络错误",
                cause = throwable
            )
            else -> UnknownError(
                message = throwable.message ?: "未知错误",
                cause = throwable
            )
        }
    }
}
