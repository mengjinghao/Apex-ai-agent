package com.apex.agent.core.tools.skill

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class UpdateNotification private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateNotification"
        private const val CHANNEL_ID_UPDATES = "skill_updates"
        private const val CHANNEL_ID_DOWNLOAD = "skill_download"
        private const val NOTIFICATION_ID_BASE = 10000
        private const val NOTIFICATION_ID_UPDATE_AVAILABLE = NOTIFICATION_ID_BASE + 1
        private const val NOTIFICATION_ID_DOWNLOAD_PROGRESS = NOTIFICATION_ID_BASE + 2
        private const val NOTIFICATION_ID_UPDATE_COMPLETE = NOTIFICATION_ID_BASE + 3
        private const val NOTIFICATION_ID_UPDATE_FAILED = NOTIFICATION_ID_BASE + 4

        @Volatile private var INSTANCE: UpdateNotification? = null

        fun getInstance(context: Context): UpdateNotification {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateNotification(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val activeNotifications = ConcurrentHashMap<String, Int>()
    private val _notificationState = MutableStateFlow<Map<String, NotificationState>>(emptyMap())
    val notificationState: StateFlow<Map<String, NotificationState>> = _notificationState.asStateFlow()

    data class NotificationState(
        val skillId: String,
        val type: Type,
        val title: String,
        val message: String,
        val progress: Float = 0f,
        val isOngoing: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    enum class Type {
        UPDATE_AVAILABLE,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_COMPLETE,
        UPDATE_COMPLETE,
        UPDATE_FAILED
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val updateChannel = NotificationChannel(
                CHANNEL_ID_UPDATES,
                "Skill Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for skill updates"
                enableVibration(true)
            }

            val downloadChannel = NotificationChannel(
                CHANNEL_ID_DOWNLOAD,
                "Skill Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for skill download progress"
                enableVibration(false)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(listOf(updateChannel, downloadChannel))
        }
    }

    fun showUpdateAvailable(
        skillId: String,
        skillName: String,
        currentVersion: String,
        newVersion: String,
        updateSize: Long,
        changelog: String
    ) {
        try {
            val title = "Update available for ${skillName}"
            val message = "Update from ${currentVersion} to ${newVersion} (${formatFileSize(updateSize)})"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPDATES)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "${message}\n\n${changelog}"
                ))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(createPendingIntent(skillId, ACTION_VIEW_UPDATE))
                .addAction(
                    android.R.drawable.ic_menu_send,
                    "Update",
                    createPendingIntent(skillId, ACTION_UPDATE)
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Later",
                    createPendingIntent(skillId, ACTION_DISMISS)
                )
                .build()

            val notificationId = NOTIFICATION_ID_UPDATE_AVAILABLE + skillId.hashCode()
            showNotification(notificationId, notification)

            activeNotifications[skillId] = notificationId
            updateNotificationState(skillId, NotificationState(
                skillId = skillId,
                type = Type.UPDATE_AVAILABLE,
                title = title,
                message = message
            ))

            AppLogger.i(TAG, "Update available notification shown for ${skillId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show update available notification", e)
        }
    }

    fun showDownloadProgress(
        skillId: String,
        skillName: String,
        version: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        try {
            val notificationId = NOTIFICATION_ID_DOWNLOAD_PROGRESS + skillId.hashCode()

            val title = "Downloading ${skillName}"
            val message = "Version ${version}: ${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOAD)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(title)
                .setContentText(message)
                .setProgress(100, (progress * 100).toInt(), false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(createPendingIntent(skillId, ACTION_VIEW_DOWNLOAD))
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    createPendingIntent(skillId, ACTION_CANCEL_DOWNLOAD)
                )
                .build()

            showNotification(notificationId, notification)

            activeNotifications[skillId] = notificationId
            updateNotificationState(skillId, NotificationState(
                skillId = skillId,
                type = Type.DOWNLOAD_PROGRESS,
                title = title,
                message = message,
                progress = progress,
                isOngoing = true
            ))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show download progress notification", e)
        }
    }

    fun showDownloadComplete(skillId: String, skillName: String, version: String) {
        try {
            val title = "Download complete for ${skillName}"
            val message = "Version ${version} is ready to install"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOAD)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(createPendingIntent(skillId, ACTION_VIEW_UPDATE))
                .addAction(
                    android.R.drawable.ic_menu_send,
                    "Install",
                    createPendingIntent(skillId, ACTION_INSTALL)
                )
                .build()

            val notificationId = NOTIFICATION_ID_DOWNLOAD_COMPLETE + skillId.hashCode()
            showNotification(notificationId, notification)

            activeNotifications[skillId] = notificationId
            updateNotificationState(skillId, NotificationState(
                skillId = skillId,
                type = Type.DOWNLOAD_COMPLETE,
                title = title,
                message = message
            ))

            AppLogger.i(TAG, "Download complete notification shown for ${skillId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show download complete notification", e)
        }
    }

    fun showUpdateComplete(skillId: String, skillName: String, newVersion: String) {
        try {
            val title = "Update complete for ${skillName}"
            val message = "Successfully updated to version ${newVersion}"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPDATES)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(createPendingIntent(skillId, ACTION_VIEW_SKILL))
                .build()

            val notificationId = NOTIFICATION_ID_UPDATE_COMPLETE + skillId.hashCode()
            showNotification(notificationId, notification)

            activeNotifications[skillId] = notificationId
            updateNotificationState(skillId, NotificationState(
                skillId = skillId,
                type = Type.UPDATE_COMPLETE,
                title = title,
                message = message
            ))

            AppLogger.i(TAG, "Update complete notification shown for ${skillId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show update complete notification", e)
        }
    }

    fun showUpdateFailed(skillId: String, skillName: String, errorMessage: String) {
        try {
            val title = "Update failed for ${skillName}"
            val message = "Error: ${errorMessage}"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPDATES)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(createPendingIntent(skillId, ACTION_VIEW_UPDATE))
                .addAction(
                    android.R.drawable.ic_menu_rotate,
                    "Retry",
                    createPendingIntent(skillId, ACTION_RETRY)
                )
                .build()

            val notificationId = NOTIFICATION_ID_UPDATE_FAILED + skillId.hashCode()
            showNotification(notificationId, notification)

            activeNotifications[skillId] = notificationId
            updateNotificationState(skillId, NotificationState(
                skillId = skillId,
                type = Type.UPDATE_FAILED,
                title = title,
                message = message
            ))

            AppLogger.i(TAG, "Update failed notification shown for ${skillId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show update failed notification", e)
        }
    }

    fun cancelNotification(skillId: String) {
        try {
            val notificationId = activeNotifications[skillId]
            if (notificationId != null) {
                notificationManager.cancel(notificationId)
                activeNotifications.remove(skillId)
                removeNotificationState(skillId)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cancel notification for ${skillId}", e)
        }
    }

    fun cancelAllNotifications() {
        try {
            activeNotifications.values.forEach { notificationId ->
                notificationManager.cancel(notificationId)
            }
            activeNotifications.clear()
            _notificationState.value = emptyMap()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cancel all notifications", e)
        }
    }

    fun getNotificationState(skillId: String): NotificationState? {
        return _notificationState.value[skillId]
    }

    fun hasActiveNotification(skillId: String): Boolean {
        return activeNotifications.containsKey(skillId)
    }

    private fun showNotification(notificationId: Int, notification: android.app.Notification) {
        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                notificationManager.notify(notificationId, notification)
            } else {
                AppLogger.w(TAG, "Notifications are disabled")
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Notification permission not granted", e)
        }
    }

    private fun createPendingIntent(skillId: String, action: String): PendingIntent {
        val intent = Intent(context, UpdateNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SKILL_ID, skillId)
        }

        val flags = when (action) {
            ACTION_VIEW_UPDATE, ACTION_VIEW_SKILL, ACTION_VIEW_DOWNLOAD -> PendingIntent.FLAG_UPDATE_CURRENT
            else -> PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getBroadcast(
            context,
            "${skillId}_${action}".hashCode(),
            intent,
            flags
        )
    }

    private fun updateNotificationState(skillId: String, state: NotificationState) {
        _notificationState.value = _notificationState.value.toMutableMap().apply {
            put(skillId, state)
        }
    }

    private fun removeNotificationState(skillId: String) {
        _notificationState.value = _notificationState.value.toMutableMap().apply {
            remove(skillId)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    object Actions {
        const val ACTION_VIEW_UPDATE = "com.apex.agent.VIEW_UPDATE"
        const val ACTION_UPDATE = "com.apex.agent.UPDATE"
        const val ACTION_DISMISS = "com.apex.agent.DISMISS"
        const val ACTION_VIEW_DOWNLOAD = "com.apex.agent.VIEW_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.apex.agent.CANCEL_DOWNLOAD"
        const val ACTION_INSTALL = "com.apex.agent.INSTALL"
        const val ACTION_VIEW_SKILL = "com.apex.agent.VIEW_SKILL"
        const val ACTION_RETRY = "com.apex.agent.RETRY"
    }

    companion object {
        const val EXTRA_SKILL_ID = "skill_id"
        const val EXTRA_UPDATE_INFO = "update_info"
    }
}

class UpdateNotificationReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val skillId = intent.getStringExtra(UpdateNotification.EXTRA_SKILL_ID) ?: return

        when (intent.action) {
            UpdateNotification.Actions.ACTION_UPDATE,
            UpdateNotification.Actions.ACTION_INSTALL -> {
                AppLogger.d("UpdateNotificationReceiver", "Install update for ${skillId}")
            }
            UpdateNotification.Actions.ACTION_DISMISS -> {
                UpdateNotification.getInstance(context).cancelNotification(skillId)
            }
            UpdateNotification.Actions.ACTION_CANCEL_DOWNLOAD -> {
                AppLogger.d("UpdateNotificationReceiver", "Cancel download for ${skillId}")
            }
            UpdateNotification.Actions.ACTION_RETRY -> {
                AppLogger.d("UpdateNotificationReceiver", "Retry update for ${skillId}")
            }
            UpdateNotification.Actions.ACTION_VIEW_UPDATE,
            UpdateNotification.Actions.ACTION_VIEW_DOWNLOAD,
            UpdateNotification.Actions.ACTION_VIEW_SKILL -> {
                AppLogger.d("UpdateNotificationReceiver", "View update details for ${skillId}")
            }
        }
    }
}