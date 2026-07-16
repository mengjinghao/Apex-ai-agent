package com.apex.base

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlin.math.min
import kotlin.random.Random

/**
 * 重试策略配置
 *
 * @param maxRetries 最大重试次数
 * @param initialDelayMs 初始延迟（毫秒）
 * @param maxDelayMs 最大延迟（毫秒）
 * @param backoffMultiplier 退避倍数（每次重试延迟乘以该值）
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    val backoffMultiplier: Double = 2.0
)

/**
 * WorkManager Worker 基类
 *
 * 提供统一的错误处理、指数退避重试和进度追踪功能。
 *
 * 使用示例：
 * ```
 * class SyncWorker(context: Context, params: WorkerParameters) : BaseWorker(context, params) {
 *     override suspend fun doWork(): Result = runCatching {
 *         val data = api.syncData()
 *         setProgress(100, 100, "同步完成")
 *         Result.success()
 *     }.getOrDefault(handleRetryOrFail())
 * }
 * ```
 *
 * @param context Android Context
 * @param params WorkManager参数
 * @param retryPolicy 重试策略
 */
abstract class BaseWorker(
    context: Context,
    workerParams: WorkerParameters,
    val retryPolicy: RetryPolicy = RetryPolicy()
) : CoroutineWorker(context, workerParams) {

    /** 当前已尝试执行的次数（从1开始） */
    protected val currentRunAttemptCount: Int get() = runAttemptCount

    /** 是否已达到最大重试次数 */
    protected val isMaxRetriesReached: Boolean get() = runAttemptCount >= retryPolicy.maxRetries

    /**
     * 设置进度（支持进度数值 + 描述文本）
     *
     * @param progress 当前进度值
     * @param max 最大进度值
     * @param message 进度描述
     */
    protected suspend fun setProgress(progress: Int, max: Int, message: String) {
        val data = workDataOf(
            KEY_PROGRESS to progress,
            KEY_PROGRESS_MAX to max,
            KEY_PROGRESS_MESSAGE to message
        )
        setProgress(data)
    }

    /**
     * 设置带有自定义数据的进度
     *
     * @param data 进度数据
     */
    protected suspend fun setProgressData(data: Data) {
        setProgress(data)
    }

    /**
     * 根据当前重试次数决定后续策略：
     * - 未达最大重试次数时返回 [Result.retry] 并使用指数退避 + 抖动
     * - 已达最大重试次数时返回 [Result.failure] 以标记任务失败
     *
     * 退避算法：delay = min(initialDelay * multiplier^(attempt-1), maxDelay) + jitter
     *
     * @param outputData 失败时携带的输出数据
     * @return retry 或 failure 的 Work Result
     */
    protected fun handleRetryOrFail(outputData: Data = workDataOf()): Result {
        return if (!isMaxRetriesReached) {
            val backoffDelay = calculateBackoffDelay()
            Result.retry()
        } else {
            Result.failure(outputData)
        }
    }

    /**
     * 计算指数退避延迟（包含随机抖动）
     *
     * @return 计算后的延迟毫秒数
     */
    private fun calculateBackoffDelay(): Long {
        val exponentialDelay = (retryPolicy.initialDelayMs *
            Math.pow(retryPolicy.backoffMultiplier, (runAttemptCount - 1).toDouble())).toLong()
        val clampedDelay = min(exponentialDelay, retryPolicy.maxDelayMs)
        val jitter = (clampedDelay * 0.1).toLong().coerceAtLeast(1L)
        return clampedDelay + Random.nextLong(-jitter, jitter + 1)
    }

    companion object {
        private const val KEY_PROGRESS = "progress"
        private const val KEY_PROGRESS_MAX = "progress_max"
        private const val KEY_PROGRESS_MESSAGE = "progress_message"
    }
}
