package com.ai.assistance.aiterminal.terminal.agent.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 任务通知管理器 - 专门负责任务相关的通知管理
 * 
 * 职责：
 * 1. 创建和管理通知渠道
 * 2. 显示任务开始、完成、失败、已调度通知
 * 3. 支持通知样式定制和点击处理
 * 4. 管理通知渠道的启用/禁用状态
 */
class TaskNotificationManager(
    private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "task_executor_channel"
        private const val CHANNEL_ID_IMPORTANT = "task_executor_channel_important"
        private const val CHANNEL_ID_SILENT = "task_executor_channel_silent"
        
        const val NOTIFICATION_ID_TASK_START = 1001
        const val NOTIFICATION_ID_TASK_COMPLETE = 1002
        const val NOTIFICATION_ID_TASK_ERROR = 1003
        const val NOTIFICATION_ID_TASK_SCHEDULED = 1004
    }
    
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
    private val notificationManagerCompat: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }
    
    init {
        createNotificationChannels()
    }
    
    /**
     * 创建所有通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 普通渠道
            createChannel(
                channelId = CHANNEL_ID,
                name = "任务执行通知",
                description = "任务执行状态的常规通知",
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                lightColor = Color.BLUE
            )
            
            // 重要渠道
            createChannel(
                channelId = CHANNEL_ID_IMPORTANT,
                name = "重要任务通知",
                description = "重要任务的通知，会弹出提醒",
                importance = NotificationManager.IMPORTANCE_HIGH,
                lightColor = Color.GREEN,
                enableVibration = true,
                vibrationPattern = longArrayOf(100, 200, 300, 400)
            )
            
            // 静默渠道
            createChannel(
                channelId = CHANNEL_ID_SILENT,
                name = "静默任务通知",
                description = "仅记录，不弹出通知",
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false
            )
        }
    }
    
    /**
     * 创建单个通知渠道
     */
    private fun createChannel(
        channelId: String,
        name: String,
        description: String,
        importance: Int,
        lightColor: Int = Color.BLUE,
        enableVibration: Boolean = false,
        vibrationPattern: LongArray? = null,
        showBadge: Boolean = true
    ) {
        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
            enableLights(true)
            this.lightColor = lightColor
            setShowBadge(showBadge)
            
            if (enableVibration && vibrationPattern != null) {
                enableVibration(true)
                this.vibrationPattern = vibrationPattern
            }
        }
        
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * 更新通知渠道设置
     */
    fun updateChannelSettings(
        channelId: String,
        name: String? = null,
        description: String? = null,
        importance: Int? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId) ?: return
            
            name?.let { channel.name = it }
            description?.let { channel.description = it }
            importance?.let { channel.importance = it }
            
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 获取所有通知渠道
     */
    fun getAllChannels(): List<NotificationChannel> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.notificationChannels
        } else {
            emptyList()
        }
    }
    
    /**
     * 检查通知权限是否已授予
     */
    fun areNotificationsEnabled(): Boolean {
        return notificationManagerCompat.areNotificationsEnabled()
    }
    
    /**
     * 检查指定渠道是否启用
     */
    fun isChannelEnabled(channelId: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId)
            channel?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
    }
    
    /**
     * 显示任务开始通知
     */
    fun showTaskStartNotification(
        taskName: String,
        taskDescription: String,
        style: NotificationStyle = NotificationStyle.DEFAULT,
        onClickIntent: Intent? = null
    ) {
        showNotification(
            title = "任务开始执行",
            content = taskDescription,
            notificationId = NOTIFICATION_ID_TASK_START,
            channelId = getChannelId(style),
            iconRes = android.R.drawable.ic_dialog_info,
            onClickIntent = onClickIntent,
            style = style
        )
    }
    
    /**
     * 显示任务完成通知
     */
    fun showTaskCompleteNotification(
        taskName: String,
        style: NotificationStyle = NotificationStyle.DEFAULT,
        onClickIntent: Intent? = null,
        largeIcon: Bitmap? = null
    ) {
        showNotification(
            title = "任务完成",
            content = "$taskName 执行成功",
            notificationId = NOTIFICATION_ID_TASK_COMPLETE,
            channelId = getChannelId(style),
            iconRes = android.R.drawable.ic_dialog_info,
            onClickIntent = onClickIntent,
            style = style,
            largeIcon = largeIcon
        )
    }
    
    /**
     * 显示任务失败通知
     */
    fun showTaskErrorNotification(
        errorMessage: String,
        taskName: String? = null,
        style: NotificationStyle = NotificationStyle.IMPORTANT,
        onClickIntent: Intent? = null
    ) {
        val title = taskName?.let { "任务失败: $it" } ?: "任务失败"
        showNotification(
            title = title,
            content = errorMessage,
            notificationId = NOTIFICATION_ID_TASK_ERROR,
            channelId = getChannelId(style),
            iconRes = android.R.drawable.ic_dialog_info,
            onClickIntent = onClickIntent,
            style = style,
            isError = true
        )
    }
    
    /**
     * 显示任务已调度通知
     */
    fun showTaskScheduledNotification(
        taskName: String,
        triggerTime: String,
        style: NotificationStyle = NotificationStyle.DEFAULT,
        onClickIntent: Intent? = null
    ) {
        showNotification(
            title = "任务已调度",
            content = "$taskName 将于 $triggerTime 执行",
            notificationId = NOTIFICATION_ID_TASK_SCHEDULED,
            channelId = getChannelId(style),
            iconRes = android.R.drawable.ic_dialog_info,
            onClickIntent = onClickIntent,
            style = style
        )
    }
    
    /**
     * 显示自定义通知
     */
    fun showCustomNotification(
        title: String,
        content: String,
        notificationId: Int,
        channelId: String = CHANNEL_ID,
        @DrawableRes iconRes: Int = android.R.drawable.ic_dialog_info,
        onClickIntent: Intent? = null,
        largeIcon: Bitmap? = null,
        progress: Int = -1,
        maxProgress: Int = 100,
        isError: Boolean = false
    ) {
        showNotification(
            title = title,
            content = content,
            notificationId = notificationId,
            channelId = channelId,
            iconRes = iconRes,
            onClickIntent = onClickIntent,
            largeIcon = largeIcon,
            progress = progress,
            maxProgress = maxProgress,
            isError = isError
        )
    }
    
    /**
     * 取消通知
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
    
    /**
     * 取消所有通知
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
    
    /**
     * 更新通知进度
     */
    fun updateNotificationProgress(
        notificationId: Int,
        progress: Int,
        maxProgress: Int = 100,
        content: String? = null
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setProgress(maxProgress, progress, false)
        
        content?.let { builder.setContentText(it) }
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    /**
     * 获取通知样式对应的渠道ID
     */
    private fun getChannelId(style: NotificationStyle): String {
        return when (style) {
            NotificationStyle.IMPORTANT -> CHANNEL_ID_IMPORTANT
            NotificationStyle.SILENT -> CHANNEL_ID_SILENT
            else -> CHANNEL_ID
        }
    }
    
    /**
     * 显示通知（内部方法）
     */
    private fun showNotification(
        title: String,
        content: String,
        notificationId: Int,
        channelId: String,
        @DrawableRes iconRes: Int,
        onClickIntent: Intent? = null,
        style: NotificationStyle = NotificationStyle.DEFAULT,
        largeIcon: Bitmap? = null,
        progress: Int = -1,
        maxProgress: Int = 100,
        isError: Boolean = false
    ) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(getNotificationPriority(style))
            .setAutoCancel(true)
        
        // 设置大图标
        largeIcon?.let { builder.setLargeIcon(it) }
        
        // 设置点击意图
        onClickIntent?.let {
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
        }
        
        // 设置进度条
        if (progress >= 0) {
            builder.setProgress(maxProgress, progress, false)
        }
        
        // 设置错误样式
        if (isError) {
            builder.setColor(Color.RED)
        }
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    /**
     * 根据样式获取通知优先级
     */
    private fun getNotificationPriority(style: NotificationStyle): Int {
        return when (style) {
            NotificationStyle.IMPORTANT -> NotificationCompat.PRIORITY_HIGH
            NotificationStyle.SILENT -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
    }
    
    /**
     * 通知样式枚举
     */
    enum class NotificationStyle {
        DEFAULT,
        IMPORTANT,
        SILENT
    }
}