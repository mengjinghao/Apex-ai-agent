package com.apex.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apex.agent.BuildConfig
import com.apex.agent.R

/**
 * 永久后台服务 - 提供长期运行的后台处理能力
 *
 * 用于数据同步、定时任务、Agent后台监控等场景。
 * 在Android 12+上使用 foregroundServiceType="dataSync|specialUse"。
 */
class PermanentBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "permanent_background_channel"
        const val NOTIFICATION_ID = 1003
        const val TAG = "PermanentBgService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * 创建通知渠道（Android O+ 必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "永久后台服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "用于保持应用后台运行和数据同步"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Apex Agent")
            .setContentText("后台服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
