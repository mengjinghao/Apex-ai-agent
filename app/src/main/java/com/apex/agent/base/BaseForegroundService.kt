package com.apex.base

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务基类，提供通知渠道创建、前台通知管理、默认生命周期实现和错误处理。
 *
 * 使用示例：
 * ```
 * class AudioService : BaseForegroundService() {
 *     override val channelId = "audio_service"
 *     override val channelName = "音频服务"
 *     override val notificationId = 1001
 *
 *     override fun buildNotification(): Notification {
 *         return createNotificationBuilder("正在播放音频")
 *             .setSmallIcon(R.drawable.ic_audio)
 *             .build()
 *     }
 * }
 * ```
 */
abstract class BaseForegroundService : Service() {

    /** 通知渠道 ID */
    protected abstract val channelId: String

    /** 通知渠道名称（用户可见） */
    protected abstract val channelName: String

    /** 前台通知 ID */
    protected abstract val notificationId: Int

    /** 通知渠道描述 */
    protected open val channelDescription: String = ""

    /** 通知优先级，默认为 PRIORITY_LOW */
    protected open val notificationPriority: Int = NotificationCompat.PRIORITY_LOW

    /**
     * 创建通知渠道。
     * 在 Android 8.0（API 26）及以上版本必须为每个通知创建渠道。
     * 此方法可安全地重复调用，系统会忽略已存在的渠道。
     */
    protected fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建默认的通知构建器，自动设置渠道 ID 和优先级。
     *
     * @param contentText 通知内容文本
     * @return NotificationCompat.Builder 用于进一步配置（设置图标、标题等）
     */
    protected fun createNotificationBuilder(contentText: String): NotificationCompat.Builder {
        createNotificationChannel()
        return NotificationCompat.Builder(this, channelId)
            .setContentText(contentText)
            .setPriority(notificationPriority)
    }

    /**
     * 启动前台服务并显示通知。
     *
     * @param notification 要显示的前台通知
     */
    protected fun startForegroundWithNotification(notification: Notification) {
        startForeground(notificationId, notification)
    }

    /**
     * 停止前台服务并移除通知。
     *
     * @param removeNotification 是否移除通知，默认为 true
     */
    protected fun stopForegroundService(removeNotification: Boolean = true) {
        stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
