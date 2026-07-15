package com.apex.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知工具类，提供通知渠道创建、通知发送与取消、通知权限检查等一站式功能
 *
 * 支持 Android 8.0 (API 26) 的通知渠道机制，自动向下兼容低版本。
 * 使用 AndroidX NotificationCompat 确保各版本表现一致。
 */
object NotificationUtils {

    /**
     * 通知渠道配置数据类
     *
     * @property id 渠道唯一标识符
     * @property name 渠道名称（用户可见）
     * @property description 渠道描述（可选，在系统设置中显示）
     * @property importance 通知重要性等级，默认为 IMPORTANCE_DEFAULT
     */
    data class NotificationChannelConfig(
        val id: String,
        val name: String,
        val description: String? = null,
        val importance: Int = NotificationManager.IMPORTANCE_DEFAULT
    )

    /** 默认通知渠道 ID */
    private const val DEFAULT_CHANNEL_ID = "default_channel"

    /** 默认通知渠道名称 */
    private const val DEFAULT_CHANNEL_NAME = "默认通知"

    /**
     * 创建通知渠道
     *
     * 在 Android 8.0 (API 26) 及以上系统中创建通知渠道，
     * 低版本系统自动忽略此操作。
     *
     * @param context 上下文
     * @param config 通知渠道配置
     */
    fun createNotificationChannel(context: Context, config: NotificationChannelConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(config.id, config.name, config.importance).apply {
                config.description?.let { description = it }
            }
        val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建默认通知渠道
     *
     * 使用预设的 ID 和名称创建一条默认级别的通知渠道，
     * 适用于未特殊指定渠道的场景。
     *
     * @param context 上下文
     */
    fun createDefaultChannel(context: Context) {
        createNotificationChannel(
            context,
            NotificationChannelConfig(
                id = DEFAULT_CHANNEL_ID,
                name = DEFAULT_CHANNEL_NAME,
                importance = NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /**
     * 创建通知构建器
     *
     * 基于指定的渠道 ID 创建 NotificationCompat.Builder 实例。
     * 确保在调用前已创建对应的通知渠道。
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @return NotificationCompat.Builder 实例
     */
    fun createNotificationBuilder(context: Context, channelId: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId)
    }

    /**
     * 发送通知
     *
     * 使用已构建好的 NotificationCompat.Builder 发送通知。
     *
     * @param context 上下文
     * @param id 通知的唯一标识 ID
     * @param builder 已配置好的通知构建器
     */
    fun sendNotification(context: Context, id: Int, builder: NotificationCompat.Builder) {
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * 发送简单文本通知
     *
     * 便捷方法，快速创建并发送一条包含标题和内容的简单通知。
     * 首次调用时会自动创建默认通知渠道。
     *
     * @param context 上下文
     * @param id 通知的唯一标识 ID
     * @param channelId 通知渠道 ID
     * @param title 通知标题
     * @param content 通知内容文本
     * @param smallIcon 通知小图标资源 ID
     */
    fun sendNotification(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        content: String,
        smallIcon: Int
    ) {
        createDefaultChannel(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * 取消指定 ID 的通知
     *
     * @param context 上下文
     * @param id 要取消的通知 ID
     */
    fun cancelNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /**
     * 取消当前应用的所有通知
     *
     * @param context 上下文
     */
    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * 创建进度条通知
     *
     * 显示一个带有进度条的通知，适用于文件下载、数据同步等耗时操作。
     * 当 progress 等于 max 时，进度条将显示为已完成状态。
     *
     * @param context 上下文
     * @param channelId 通知渠道 ID
     * @param title 通知标题
     * @param progress 当前进度值
     * @param max 最大进度值
     * @return 配置好的 Notification 对象
     */
    fun createProgressNotification(
        context: Context,
        channelId: String,
        title: String,
        progress: Int,
        max: Int
    ): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(max, progress, false)
            .setOngoing(progress < max)
            .build()
    }

    /**
     * 检查应用的通知是否已启用
     *
     * 在 Android 13 (API 33) 及以上需要检查 POST_NOTIFICATIONS 权限，
     * 低版本设备默认通知可用。
     *
     * @param context 上下文
     * @return true 通知已启用，false 通知被禁用
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 打开当前应用的通知设置页面
     *
     * 跳转到系统设置中当前应用的通知管理页面，
     * 用户可在该页面开启或关闭通知、配置通知渠道等。
     *
     * @param context 上下文
     */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_APP_UID, context.applicationInfo.uid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
