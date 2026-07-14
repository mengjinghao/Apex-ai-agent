package com.apex.agent.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apex.util.AppLogger

/**
 * 热更新通知器。
 *
 * 负责在以下场景显示系统通知：
 * - 后台发现新版本（用户可点击跳转设置页）
 * - 下载进度（带进度条，常驻通知栏）
 * - 下载完成（提醒用户在系统安装界面完成安装）
 * - 下载失败（提示用户重试）
 *
 * 通知渠道：
 * - `apex.update.available` — 发现新版本（IMPORTANCE_DEFAULT）
 * - `apex.update.progress` — 下载进度（IMPORTANCE_LOW，不打扰）
 * - `apex.update.result` — 下载完成/失败（IMPORTANCE_DEFAULT）
 */
class UpdateNotifier private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateNotifier"

        const val CHANNEL_AVAILABLE = "apex.update.available"
        const val CHANNEL_PROGRESS = "apex.update.progress"
        const val CHANNEL_RESULT = "apex.update.result"

        const val NOTIF_ID_AVAILABLE = 0xA001
        const val NOTIF_ID_PROGRESS = 0xA002
        const val NOTIF_ID_RESULT = 0xA003

        @Volatile private var INSTANCE: UpdateNotifier? = null
        fun getInstance(context: Context): UpdateNotifier {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateNotifier(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_AVAILABLE,
                "发现新版本",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Apex 检测到 GitHub 上有新版本时通知"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_PROGRESS,
                "更新下载进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Apex APK 下载进度（静默）"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_RESULT,
                "更新结果",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Apex 更新下载完成或失败"
            }
        )
        channels.forEach { notificationManager.createNotificationChannel(it) }
    }

    /** 通知"发现新版本"。点击跳转到 GitHub Release 页面。 */
    fun notifyUpdateAvailable(version: String, sizeText: String, htmlUrl: String?) {
        if (!hasNotificationPermission()) {
            AppLogger.w(TAG, "无通知权限，跳过 UpdateAvailable 通知")
            return
        }
        val tapIntent = if (htmlUrl != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
        val pi = tapIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_AVAILABLE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("发现 Apex 新版本 $version")
            .setContentText("大小 $sizeText · 点击查看")
            .setStyle(NotificationCompat.BigTextStyle().bigText("大小 $sizeText · 点击查看"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_AVAILABLE, notif)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "通知 UpdateAvailable 失败: ${t.message}")
        }
    }

    /** 更新下载进度。 */
    fun notifyProgress(percent: Int, bytesRead: Long, totalBytes: Long, mirrorName: String?) {
        if (!hasNotificationPermission()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 Apex 更新")
            .setContentText(if (percent >= 0) "$percent% · ${formatBytes(bytesRead)} / ${formatBytes(totalBytes)}" else "下载中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent >= 0) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        mirrorName?.let { builder.setSubText(it) }
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_PROGRESS, builder.build())
        } catch (_: Throwable) {}
    }

    /** 通知下载完成。 */
    fun notifyDownloadComplete(version: String) {
        if (!hasNotificationPermission()) return
        cancel(NOTIF_ID_PROGRESS)
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Apex 更新已下载")
            .setContentText("请在弹出的系统安装界面完成 $version 的安装")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_RESULT, notif)
        } catch (_: Throwable) {}
    }

    /** 通知下载失败。 */
    fun notifyDownloadFailed(reason: String) {
        if (!hasNotificationPermission()) return
        cancel(NOTIF_ID_PROGRESS)
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Apex 更新下载失败")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_RESULT, notif)
        } catch (_: Throwable) {}
    }

    /** 取消所有更新相关通知（用于用户主动忽略版本、关闭对话框）。 */
    fun cancelAll() {
        cancel(NOTIF_ID_AVAILABLE)
        cancel(NOTIF_ID_PROGRESS)
        cancel(NOTIF_ID_RESULT)
    }

    /** 仅取消进度通知（下载完成或失败时调用）。 */
    fun cancelProgress() {
        cancel(NOTIF_ID_PROGRESS)
    }

    private fun cancel(id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Throwable) {}
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }
}
