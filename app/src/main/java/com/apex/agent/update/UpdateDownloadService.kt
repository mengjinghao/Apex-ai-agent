package com.apex.agent.update

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apex.util.AppLogger
import com.apex.core.application.ForegroundServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 后台下载 Foreground Service。
 *
 * **用途**：当用户点击"立即更新"后，启动该服务把下载流程迁到前台服务中执行，
 * 即使 App 被系统杀死，下载仍能继续，直到完成或用户取消。
 *
 * **生命周期**：
 * - `startForegroundService` → `onStartCommand` → `startForeground` → 启动下载协程
 * - 下载完成 / 失败 / 取消 → `stopSelf`
 *
 * **通知**：服务自身维护一条常驻通知（进度条），与 [UpdateNotifier] 的 progress 通道复用，
 * 通知 ID 也复用 [UpdateNotifier.NOTIF_ID_PROGRESS]，避免重复。
 *
 * **调用方式**：
 * ```kotlin
 * val intent = Intent(context, UpdateDownloadService::class.java)
 * ContextCompat.startForegroundService(context, intent)
 * ```
 *
 * 取消：
 * ```kotlin
 * context.stopService(Intent(context, UpdateDownloadService::class.java))
 * // 或者
 * HotUpdateManager.getInstance(context).cancelDownload()
 * ```
 */
class UpdateDownloadService : Service() {

    companion object {
        private const val TAG = "UpdateDownloadService"
        private const val NOTIFICATION_ID = UpdateNotifier.NOTIF_ID_PROGRESS

        @Volatile private var isRunning = false

        /** 当前服务是否正在运行（用于 UI 判断是否需要启动）。 */
        fun isRunning(): Boolean = isRunning

        /**
         * 启动服务（兼容 Android 8+ 的前台服务要求）。
         * 若已经在运行则什么也不做。
         */
        fun start(context: Context) {
            if (isRunning) {
                AppLogger.d(TAG, "服务已在运行，跳过 start")
        return
            }
        val intent = Intent(context, UpdateDownloadService::class.java)
        try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务受限；但热更新下载由用户点击触发，属于前台
        AppLogger.e(TAG, "启动 UpdateDownloadService 失败", t)
            }
        }

        /** 停止服务。 */
        fun stop(context: Context) {
            val intent = Intent(context, UpdateDownloadService::class.java)
        context.stopService(intent)
        }
    }
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var progressJob: Job? = null
    private var downloadJob: Job? = null
    private val notifier by lazy { UpdateNotifier.getInstance(this) }
        override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppLogger.d(TAG, "UpdateDownloadService onCreate")
        // 立即启动前台通知，避免 Android 12+ 的 ForegroundServiceDidNotStartInTimeException
        startForegroundCompat()
    }
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "UpdateDownloadService onStartCommand")
        // 确保前台通知已启动（若被系统重启则需再次确认）
        startForegroundCompat()
        val manager = HotUpdateManager.getInstance(this)
        val state = manager.state.value

        // 重入保护：若下载已在进行中，不要重复启动
    if (state is UpdateState.Downloading) {
            AppLogger.d(TAG, "下载进行中，跳过重复启动")
        return START_NOT_STICKY
        }

        // 若已完成或失败，服务无需再启动下载
    if (state is UpdateState.Downloaded || state is UpdateState.Failed) {
            AppLogger.d(TAG, "下载已结束（$state），停止服务")
        stopSelfAndCleanup()
        return START_NOT_STICKY
        }

        // 若无可用更新，停止服务
    if (state !is UpdateState.UpdateAvailable || state.release == null || state.asset == null) {
            AppLogger.w(TAG, "无可用更新，停止服务")
        stopSelfAndCleanup()
        return START_NOT_STICKY
        }

        // 订阅进度更新通知（取消旧的订阅避免泄漏）
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            manager.state.collectLatest { s ->
                when (s) {
                    is UpdateState.Downloading -> {
                        notifier.notifyProgress(
                            percent = s.progress.percent,
                            bytesRead = s.progress.bytesRead,
                            totalBytes = s.progress.totalBytes,
                            mirrorName = null
                        )
                    }
        is UpdateState.Downloaded -> {
                        // 已在 HotUpdateManager 中触发通知，这里仅停止服务
        stopSelfAndCleanup()
                    }
        is UpdateState.Failed -> {
                        // 已在 HotUpdateManager 中触发通知，这里仅停止服务
        stopSelfAndCleanup()
                    }
        UpdateState.Idle -> {
                        // 用户取消或重置，停止服务
        stopSelfAndCleanup()
                    }
        else -> { /* Checking / UpdateAvailable: 维持前台 */ }
                }
            }
        }

        // 启动下载（HotUpdateManager.startDownload 内部会在自己的 scope 里 launch，
        // 这里仅做触发；downloadJob 用于追踪触发动作本身）
    if (downloadJob == null || downloadJob?.isActive != true) {
            downloadJob = serviceScope.launch {
                try {
                    manager.startDownload()
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "下载异常", t)
        stopSelfAndCleanup()
                }
            }
        }
        return START_NOT_STICKY
    }
        override fun onDestroy() {
        super.onDestroy()
        AppLogger.d(TAG, "UpdateDownloadService onDestroy")
        // 若下载仍在进行中，显式取消，确保 Service 生命周期与下载一致
    val manager = HotUpdateManager.getInstance(this)
        val s = manager.state.value
        if (s is UpdateState.Downloading) {
            manager.cancelDownload()
        }
        progressJob?.cancel()
        downloadJob?.cancel()
        serviceScope.cancel()
        isRunning = false
    }

    /** 统一通过 ForegroundServiceCompat 启动前台，处理 API 版本差异。 */
    private fun startForegroundCompat() {
        val notif = buildInitialNotification()
        val types = ForegroundServiceCompat.buildTypes(dataSync = true)
        ForegroundServiceCompat.startForeground(
            service = this,
            notificationId = NOTIFICATION_ID,
            notification = notif,
            types = types
        )
    }
        private fun stopSelfAndCleanup() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        stopSelf()
    }
        private fun buildInitialNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, UpdateNotifier.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 Apex 更新")
            .setContentText("准备中...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
        return builder.build()
    }
}
