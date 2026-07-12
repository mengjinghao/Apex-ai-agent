package com.apex.core.application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import com.apex.agent.R
import com.apex.api.chat.AIForegroundService
import com.apex.data.preferences.UserPreferencesManager
import com.apex.core.services.LinkServicesManager
import com.apex.ui.main.MainActivity
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
class BackgroundServiceManager private constructor(
private val context: Context) {
    companion object {
        private const val TAG = "BackgroundServiceManager"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "PERMANENT_BACKGROUND_CHANNEL"
        @Volatile
        private var INSTANCE: BackgroundServiceManager? = null
        fun getInstance(context: Context): BackgroundServiceManager {
            return INSTANCE ?: synchronized(this) {
                val instance = BackgroundServiceManager(context.applicationContext)
                INSTANCE = instance
                instance
}

}

}
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var isServiceRunning = false
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return
        monitoringJob = serviceScope.launch {
            val preferences = UserPreferencesManager.getInstance(context)
            preferences.permanentBackgroundEnabled.collectLatest { enabled ->
 AppLogger.d(TAG, "Permanent background enabled changed: ${enabled}")
 if (enabled) {
                    startPermanentBackgroundService()
    } else {
                    stopPermanentBackgroundService()
}

}

}
        serviceScope.launch {
            preferences.permanentBackgroundEnabled.collectLatest { enabled ->
 if (enabled) {
                    ensureServiceRunning()
}

}

}

}
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        stopPermanentBackgroundService()
}
    private fun startPermanentBackgroundService() {
        if (isServiceRunning) return
        val intent = Intent(context, PermanentBackgroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
    } else {
                context.startService(intent)
}
            isServiceRunning = true
            AppLogger.d(TAG, "Permanent background service started")
    } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start permanent background service", e)
}

}
    private fun stopPermanentBackgroundService() {
        if (!isServiceRunning) return
        try {
            context.stopService(Intent(context, PermanentBackgroundService::class.java))
            isServiceRunning = false
            AppLogger.d(TAG, "Permanent background service stopped")
    } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to stop permanent background service", e)
}

}
    private fun ensureServiceRunning() {
        if (!isServiceRunning) {
            startPermanentBackgroundService()
}

}
    private val preferences: UserPreferencesManager
    get() = UserPreferencesManager.getInstance(context)
}
class PermanentBackgroundService : Service() {
    companion object {
        private const val TAG = "PermanentBackgroundService"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "PERMANENT_BACKGROUND_CHANNEL"
        private const val ACTION_ENABLE_ALWAYS_ON = "com.apex.action.ENABLE_ALWAYS_ON"
        private const val ACTION_DISABLE_ALWAYS_ON = "com.apex.action.DISABLE_ALWAYS_ON"
        @Volatile
        var isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
}
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var isAlwaysOnEnabled = false
    private val linkServicesManager by lazy {
 LinkServicesManager.getInstance(applicationContext)
}
    private var linkServicesCallback: LinkServicesManager.LinkServiceCallback? = null
    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        AppLogger.d(TAG, "Permanent background service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        observeSettings()
}
    private fun observeSettings() {
        serviceScope.launch {
            val preferences = UserPreferencesManager.getInstance(applicationContext)
            launch {
                preferences.permanentBackgroundEnabled.collectLatest { enabled ->
 isAlwaysOnEnabled = enabled
 AppLogger.d(TAG, "Always on setting changed: ${enabled}")
 if (!enabled) {
                        stopSelfIfIdle()
    } else {
                        ensureKeepAlive()
                        startLinkServices()
}
                    updateNotification()
}

}
            launch {
                preferences.wechatClawbotEnabled.collectLatest { enabled ->
 AppLogger.d(TAG, "WeChat clawbot enabled: ${enabled}")
 if (enabled) {
                        startLinkServices()
}
                    updateNotification()
}

}
            launch {
                preferences.linkServicesEnabled.collectLatest { enabled ->
 AppLogger.d(TAG, "Link services enabled: ${enabled}")
 if (enabled) {
                        startLinkServices()
}
                    updateNotification()
}

}

}

}
    private fun startLinkServices() {
        if (linkServicesCallback == null) {
            linkServicesCallback = object : LinkServicesManager.LinkServiceCallback {
                override fun onStatusChanged(status: LinkServicesManager.LinkServiceStatus) {
                    AppLogger.d(TAG, "Link service status: ${status}")
                    updateNotification()
}
                override fun onMessageReceived(message: String) {
                    AppLogger.d(TAG, "Link service message: ${message}")
}
                override fun onCommandReceived(command: String) {
                    AppLogger.d(TAG, "Link service command: ${command}")
}

}
            linkServicesManager.setCallback(linkServicesCallback)
}
        linkServicesManager.startMonitoring()
}
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_permanent_background),
            NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_permanent_background_desc)
                setShowBadge(false)
}
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
}

}
    private fun createNotification(): android.app.Notification {
        val title = getString(R.string.service_permanent_background)
        val contentText = buildContentText()
        val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
},
PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)
return NotificationCompat.Builder(this, CHANNEL_ID)
.setContentTitle(title)
.setContentText(contentText)
.setSmallIcon(android.R.drawable.ic_dialog_info)
.setContentIntent(pendingIntent)
.setPriority(NotificationCompat.PRIORITY_LOW)
.setOngoing(true)
.setOnlyAlertOnce(true)
.build()
}
    private fun buildContentText(): String {
        val preferences = UserPreferencesManager.getInstance(applicationContext)
        val services = mutableListOf<String>()
        if (isAlwaysOnEnabled) {
            services.add(getString(R.string.service_always_on))
}
        if (linkServicesManager.isServiceConnected()) {
            services.add("链接服务已连接")
        }
        return if (services.isEmpty()) {
            getString(R.string.service_permanent_background_running)
        } else {
            services.joinToString(" | ")
        }
    }
    private fun updateNotification() {
        if (!isRunning.get()) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
}
    private fun ensureKeepAlive() {
        if (!isAlwaysOnEnabled) return
        keepAliveRunnable?.let {
 mainHandler.removeCallbacks(it)
}
        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get() || !isAlwaysOnEnabled) return
                AppLogger.v(TAG, "Keep-alive ping")
                ensureAIForegroundService()
                mainHandler.postDelayed(this, 60000)
}

}
        mainHandler.postDelayed(keepAliveRunnable!!, 60000)
}
    private fun ensureAIForegroundService() {
        try {
            AIForegroundService.ensureRunningForExternalHttp(applicationContext)
    } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to ensure AI foreground service", e)
}

}
    private fun stopSelfIfIdle() {
        if (!isAlwaysOnEnabled) {
            AppLogger.d(TAG, "Always on disabled, stopping service")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
}

}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_ALWAYS_ON -> {
                isAlwaysOnEnabled = true
                ensureKeepAlive()
}
            ACTION_DISABLE_ALWAYS_ON -> {
                isAlwaysOnEnabled = false
                stopSelfIfIdle()
}

}
        return START_STICKY
}
    override fun onDestroy() {
        isRunning.set(false)
        keepAliveRunnable?.let {
 mainHandler.removeCallbacks(it)
}
        keepAliveRunnable = null
        linkServicesManager.stopMonitoring()
        linkServicesManager.setCallback(null)
        linkServicesCallback = null
        super.onDestroy()
        AppLogger.d(TAG, "Permanent background service destroyed")
}
    override fun onBind(intent: Intent): IBinder? = null
}