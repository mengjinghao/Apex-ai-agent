package com.apex.agent.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val KEY_SYNC_TYPE = "sync_type"
const val KEY_SYNC_DATA = "sync_data"
const val KEY_SYNC_RESULT = "sync_result"
const val KEY_DOWNLOAD_URL = "download_url"
const val KEY_DESTINATION = "destination"
const val KEY_DOWNLOAD_RESULT = "download_result"

/**
 * Worker 工厂：将 WorkManager 请求路由到具体的 Worker。
 *
 * 通过依赖注入容器获取 Worker 所需的 Manager 实例，
 * 这样既保持 Worker 的可测试性，又能让 Worker 真正执行业务逻辑。
 */
class BackgroundTaskFactory(
    private val dataSyncManagerProvider: () -> DataSyncManager,
    private val downloadManagerProvider: () -> BackgroundDownloadManager
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            PeriodicSyncWorker::class.java.name ->
                PeriodicSyncWorker(appContext, workerParameters, dataSyncManagerProvider())
            DataSyncWorker::class.java.name ->
                DataSyncWorker(appContext, workerParameters, dataSyncManagerProvider())
            DownloadWorker::class.java.name ->
                DownloadWorker(appContext, workerParameters, downloadManagerProvider())
            else -> null
        }
    }
}

/**
 * 周期性同步 Worker。
 *
 * 负责低频、后台、批量的数据同步：
 * - 拉取云端配置/规则更新
 * - 同步本地待上传数据
 * - 触发缓存清理
 *
 * 该 Worker 由 [BackgroundTaskManager.schedulePeriodicTask] 调度，
 * 默认最小间隔为 15 分钟（WorkManager 限制）。
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val dataSyncManager: DataSyncManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PeriodicSyncWorker"
        const val DEFAULT_SOURCE = "remote://config"
        const val DEFAULT_TARGET = "local://cache"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: "default"
        Log.i(TAG, "Periodic sync started, type=$syncType")

        return@withContext try {
            val result = dataSyncManager.syncData(
                source = inputData.getString("source") ?: DEFAULT_SOURCE,
                target = inputData.getString("target") ?: DEFAULT_TARGET
            )
            if (result.isSuccess) {
                Log.i(TAG, "Periodic sync completed successfully, type=$syncType")
                Result.success(workDataOf(KEY_SYNC_RESULT to "completed:$syncType"))
            } else {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.w(TAG, "Periodic sync failed: $err")
                // 周期任务失败时返回 retry，WorkManager 会按退避策略重试
                Result.retry(workDataOf(KEY_SYNC_RESULT to "failed:$err"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Periodic sync crashed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/**
 * 一次性数据同步 Worker。
 *
 * 由用户操作或事件触发（如配置变更、登录后的初始同步）。
 * 接收 [KEY_SYNC_TYPE] 和 [KEY_SYNC_DATA] 输入参数，
 * 通过 [DataSyncManager] 完成具体同步并输出 [KEY_SYNC_RESULT]。
 */
class DataSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val dataSyncManager: DataSyncManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DataSyncWorker"
        const val MAX_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: "default"
        val syncData = inputData.getString(KEY_SYNC_DATA) ?: ""
        Log.i(TAG, "Data sync started, type=$syncType, payloadSize=${syncData.length}")

        return@withContext try {
            // 构造 source/target：业务可自定义格式，这里使用约定俗成的 URI 风格
            val source = "payload://$syncType"
            val target = "local://store/$syncType"

            val result = dataSyncManager.syncData(source = source, target = target)
            if (result.isSuccess) {
                Log.i(TAG, "Data sync completed, type=$syncType")
                Result.success(workDataOf(KEY_SYNC_RESULT to "completed:$syncType"))
            } else {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.w(TAG, "Data sync failed: $err")
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry(workDataOf(KEY_SYNC_RESULT to "retry:$err"))
                } else {
                    Result.failure(workDataOf(KEY_SYNC_RESULT to "failed:$err"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Data sync crashed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}

/**
 * 下载 Worker。
 *
 * 输入：[KEY_DOWNLOAD_URL]、[KEY_DESTINATION]（绝对路径）。
 * 输出：[KEY_DOWNLOAD_RESULT]：completed/failed:<msg>。
 *
 * 使用 [BackgroundDownloadManager] 执行真实下载，支持：
 * - 进度上报（通过 setProgress）
 * - 断点失败重试（由 WorkManager 退避策略控制）
 * - 失败时清理半成品文件
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloadManager: BackgroundDownloadManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"
        const val MAX_ATTEMPTS = 3
        const val PROGRESS_KEY = "progress"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_DOWNLOAD_URL)
        val destination = inputData.getString(KEY_DESTINATION)

        if (url.isNullOrBlank() || destination.isNullOrBlank()) {
            Log.e(TAG, "Missing required input: url=$url, destination=$destination")
            return@withContext Result.failure(
                workDataOf(KEY_DOWNLOAD_RESULT to "failed:invalid_input")
            )
        }

        Log.i(TAG, "Download started: url=$url, dest=$destination")
        val destFile = File(destination)
        destFile.parentFile?.mkdirs()

        return@withContext try {
            // 通过 setProgress 上报百分比，UI 层可以观察 WorkInfo.progress
            val result = downloadManager.downloadFile(
                url = url,
                destFile = destFile,
                onProgress = { bytesRead, total ->
                    if (total > 0) {
                        val percent = (bytesRead * 100 / total).toInt()
                        setProgress(workDataOf(PROGRESS_KEY to percent))
                    }
                }
            )

            if (result.isSuccess) {
                Log.i(TAG, "Download completed: $destination (${destFile.length()} bytes)")
                Result.success(
                    workDataOf(
                        KEY_DOWNLOAD_RESULT to "completed",
                        KEY_DESTINATION to destFile.absolutePath
                    )
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.w(TAG, "Download failed: $err")
                cleanupPartialFile(destFile)
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry(workDataOf(KEY_DOWNLOAD_RESULT to "retry:$err"))
                } else {
                    Result.failure(workDataOf(KEY_DOWNLOAD_RESULT to "failed:$err"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download crashed", e)
            cleanupPartialFile(destFile)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun cleanupPartialFile(file: File) {
        try {
            if (file.exists() && file.length() > 0) {
                file.delete()
                Log.i(TAG, "Cleaned partial file: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup partial file: ${file.absolutePath}", e)
        }
    }
}
