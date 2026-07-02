package com.apex.api.chat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import com.apex.util.AppLogger
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.drawable.IconCompat
import com.apex.agent.R
import com.apex.api.speech.PersonalWakeListener
import com.apex.api.speech.SpeechPrerollStore
import com.apex.api.speech.SpeechService
import com.apex.api.speech.SpeechServiceFactory
import com.apex.core.chat.AIMessageManager
import com.apex.core.application.ActivityLifecycleManager
import com.apex.core.application.ForegroundServiceCompat
import com.apex.data.preferences.ExternalHttpApiConfig
import com.apex.data.preferences.ExternalHttpApiPreferences
import com.apex.data.preferences.SpeechServicesPreferences
import com.apex.integrations.http.ExternalChatHttpServer
import com.apex.integrations.http.ExternalChatHttpState
import com.apex.services.FloatingChatService
import com.apex.services.UIDebuggerService
import com.apex.data.preferences.DisplayPreferencesManager
import com.apex.data.preferences.WakeWordPreferences
import com.apex.data.repository.WorkflowRepository
import com.apex.ui.main.MainActivity
import com.apex.util.WaifuMessageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.exitProcess
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
private fun AudioRecordingConfiguration.tryGetClientUid(): Int? {
    return try {
        val method =            java
class.methods.firstOrNull {
 it.name == "getClientUid" && it.parameterTypes.isEmpty()
}
        val value = method?.invoke(this)        value as? Int
}
 catch (_: Exception) {
        null
}

}
private fun AudioRecordingConfiguration.tryGetClientPackageName(): String? {
    return try {
        val method =            java
class.methods.firstOrNull {
 it.name == "getClientPackageName" && it.parameterTypes.isEmpty()
}
        val value = method?.invoke(this)        value as? String
}
 catch (_: Exception) {
        null
}

}/** 前台服务，用于在AI进行长时间处理时保持应用活跃，防止被系统杀死？该服务不执行实际工作，仅通过显示一个持久通知来提升应用的进程优先级？*/
class AIForegroundService : Service() {
    companion object {
        private const val TAG = "AIForegroundService"        private const val NOTIFICATION_ID = 1        private const val REPLY_NOTIFICATION_ID = 2001        private const val CHANNEL_ID = "AI_SERVICE_CHANNEL"        private const val REPLY_CHANNEL_ID_PREFIX = "AI_REPLY_COMPLETE_CHANNEL"        private val REPLY_VIBRATION_PATTERN = longArrayOf(0L, 250L, 150L, 250L)        private const val ACTION_CANCEL_CURRENT_OPERATION = "com.apex.action.CANCEL_CURRENT_OPERATION"        private const val REQUEST_CODE_CANCEL_CURRENT_OPERATION = 9002        private const val ACTION_EXIT_APP = "com.apex.action.EXIT_APP"        private const val REQUEST_CODE_EXIT_APP = 9003        private const val ACTION_TOGGLE_WAKE_LISTENING = "com.apex.action.TOGGLE_WAKE_LISTENING"        private const val REQUEST_CODE_TOGGLE_WAKE_LISTENING = 9006        private const val REPLY_NOTIFICATION_TAG_PREFIX = "ai_reply:"        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME =            "com.apex.action.SET_WAKE_LISTENING_SUSPENDED_FOR_IME"        private const val EXTRA_IME_VISIBLE = "extra_ime_visible"        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN =            "com.apex.action.SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN"        private const val EXTRA_FLOATING_FULLSCREEN_ACTIVE = "extra_floating_fullscreen_active"        const val ACTION_PREPARE_WAKE_HANDOFF =            "com.apex.action.PREPARE_WAKE_HANDOFF"        private const val ACTION_ENSURE_MICROPHONE_FOREGROUND =            "com.apex.action.ENSURE_MICROPHONE_FOREGROUND"        private const val ACTION_START_OR_REFRESH_EXTERNAL_HTTP =            "com.apex.action.START_OR_REFRESH_EXTERNAL_HTTP"        private const val ACTION_STOP_EXTERNAL_HTTP =            "com.apex.action.STOP_EXTERNAL_HTTP"        @Volatile        private var lastRequestedImeVisible: Boolean = false        // 静态标志，用于从外部检查服务是否正在运�?       val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)        private val activeReplyNotificationTags = ConcurrentHashMap.newKeySet<String>()        private val externalHttpStateFlow = MutableStateFlow(ExternalChatHttpState())        val externalHttpState = externalHttpStateFlow.asStateFlow()                // Intent extras keys        const val EXTRA_CHARACTER_NAME = "extra_character_name"        const val EXTRA_AVATAR_URI = "extra_avatar_uri"        const val EXTRA_STATE = "extra_state"        const val STATE_RUNNING = "running"        const val STATE_IDLE = "idle"        private fun buildReplyNotificationTag(chatId: String): String {
            val suffix = chatId?.ifBlank {
 "default"
}
 ?: "default"            return "�?{
REPLY_NOTIFICATION_TAG_PREFIX
}�?{
suffix
}"
}
        private fun createMainActivityPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::
class.java).apply {
                flags =                    Intent.FLAG_ACTIVITY_NEW_TASK or                        Intent.FLAG_ACTIVITY_CLEAR_TOP or                        Intent.FLAG_ACTIVITY_SINGLE_TOP
}
            return PendingIntent.getActivity(                context,                0,                intent,                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
 else {
                    PendingIntent.FLAG_UPDATE_CURRENT
}
            )
}
        private fun buildReplyNotificationChannelId(            enableSound: Boolean,            enableVibration: Boolean        ): String =            when {
                enableSound && enableVibration -> "${
REPLY_CHANNEL_ID_PREFIX
}_sound_vibration"                enableSound -> "${
REPLY_CHANNEL_ID_PREFIX
}_sound"                enableVibration -> "${
REPLY_CHANNEL_ID_PREFIX
}_vibration"                else -> "${
REPLY_CHANNEL_ID_PREFIX
}_silent"
}
        private fun getReplyNotificationChannelNameRes(            enableSound: Boolean,            enableVibration: Boolean        ): Int =            when {
                enableSound && enableVibration -> R.string.service_chat_complete_reminder_sound_vibration                enableSound -> R.string.service_chat_complete_reminder_sound                enableVibration -> R.string.service_chat_complete_reminder_vibration                else -> R.string.service_chat_complete_reminder
}
        private fun ensureReplyNotificationChannel(            context: Context,            enableSound: Boolean,            enableVibration: Boolean        ): String {
            val channelId = buildReplyNotificationChannelId(enableSound, enableVibration)            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return channelId
}
            val replyChannel =                NotificationChannel(                    channelId,                    context.getString(                        getReplyNotificationChannelNameRes(                            enableSound = enableSound,                            enableVibration = enableVibration                        )                    ),                    NotificationManager.IMPORTANCE_HIGH                ).apply {
                    description = context.getString(R.string.service_notify_when_complete)                    setSound(                        if (enableSound) Settings.System.DEFAULT_NOTIFICATION_URI else null,                        if (enableSound) {
                            AudioAttributes.Builder()                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)                                .build()
}
 else {
                            null
}
                    )                    enableVibration(enableVibration)                    vibrationPattern =                        if (enableVibration) {
                            REPLY_VIBRATION_PATTERN
}
 else {
                            longArrayOf(0L)
}

}
            val manager =                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager            manager.createNotificationChannel(replyChannel)            return channelId
}
        private fun loadBitmapFromUri(context: Context, uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)                val inputStream: InputStream? =                    when (uri.scheme) {
                        "file" -> {
                            val path = uri.path                            if (path != null && path.startsWith("/android_asset/")) {
                                context.assets.open(path.removePrefix("/android_asset/"))
}
 else if (!path.isNullOrEmpty()) {
                                FileInputStream(path)
}
 else {
                                null
}

}
                        null -> {
                            if (uriString.startsWith("/android_asset/")) {
                                context.assets.open(uriString.removePrefix("/android_asset/"))
}
 else {
                                try {
                                    FileInputStream(uriString)
}
 catch (_: Exception) {
                                    context.contentResolver.openInputStream(uri)
}

}

}
                        else -> context.contentResolver.openInputStream(uri)
}
                inputStream?.use {
 stream ->                    BitmapFactory.decodeStream(stream)
}

}
 catch (e: Exception) {
                AppLogger.e(TAG, "从URI加载Bitmap失败: ${
e.message
}", e)                null
}

}
        fun notifyReplyCompleted(            context: Context,            chatId: String?,            characterName: String?,            rawReplyContent: String?,            avatarUri: String?        ) {
            try {
                AppLogger.d(TAG, "检查是否需要发送会话完成通知: chatId=�?{
chatId
}")                if (ActivityLifecycleManager.getCurrentActivity() != null) {
                    AppLogger.d(TAG, "应用在前台，无需发送会话完成通知")                    return
}
                val appContext = context.applicationContext                val displayPreferences = DisplayPreferencesManager.getInstance(appContext)                val enableReplyNotification = runBlocking {
                    displayPreferences.enableReplyNotification.first()
}
                if (!enableReplyNotification) {
                    AppLogger.d(TAG, "回复通知已禁用，跳过发）                    return
}
                if (rawReplyContent.isNullOrBlank()) {
                    AppLogger.d(TAG, "回复内容为空，跳过发送回复通知: chatId=�?{
chatId
}")                    return
}
                val enableReplyNotificationSound = runBlocking {
                    displayPreferences.enableReplyNotificationSound.first()
}
                val enableReplyNotificationVibration = runBlocking {
                    displayPreferences.enableReplyNotificationVibration.first()
}
                val replyChannelId =                    ensureReplyNotificationChannel(                        context = appContext,                        enableSound = enableReplyNotificationSound,                        enableVibration = enableReplyNotificationVibration                    )                val cleanedReplyContent = WaifuMessageProcessor.cleanContentForWaifu(rawReplyContent)                var notificationDefaults = NotificationCompat.DEFAULT_LIGHTS                if (enableReplyNotificationSound) {
                    notificationDefaults = notificationDefaults or NotificationCompat.DEFAULT_SOUND
}
                if (enableReplyNotificationVibration) {
                    notificationDefaults = notificationDefaults or NotificationCompat.DEFAULT_VIBRATE
}
                val notificationBuilder =                    NotificationCompat.Builder(appContext, replyChannelId)                        .setSmallIcon(android.R.drawable.ic_dialog_info)                        .setContentTitle(                            characterName                                ?: appContext.getString(R.string.notification_ai_reply_title)                        )                        .setContentText(                            cleanedReplyContent                                .take(100)                                .ifEmpty {
                                    appContext.getString(R.string.notification_ai_reply_content)
}
                        )                        .setPriority(NotificationCompat.PRIORITY_HIGH)                        .setDefaults(notificationDefaults)                        .setCategory(NotificationCompat.CATEGORY_STATUS)                        .setContentIntent(createMainActivityPendingIntent(appContext))                        .setAutoCancel(true)                if (cleanedReplyContent.isNotEmpty()) {
                    notificationBuilder.setStyle(                        NotificationCompat.BigTextStyle()                            .bigText(cleanedReplyContent)                            .setBigContentTitle(                                characterName                                    ?: appContext.getString(R.string.notification_ai_reply_title)                            )                    )
}
                if (!avatarUri.isNullOrEmpty()) {
                    val bitmap = loadBitmapFromUri(appContext, avatarUri)                    if (bitmap != null) {
                        notificationBuilder.setLargeIcon(bitmap)
}

}
                val manager =                    appContext.getSystemService(Context.NOTIFICATION_SERVICE)                        as NotificationManager                val tag = buildReplyNotificationTag(chatId)                activeReplyNotificationTags.add(tag)                manager.notify(tag, REPLY_NOTIFICATION_ID, notificationBuilder.build())                AppLogger.d(TAG, "AI回复通知已发�?chatId=�?{
chatId
}, tag=�?{
tag
}")
}
 catch (e: Exception) {
                AppLogger.e(TAG, "发送AI回复通知失败: ${
e.message
}", e)
}

}
        fun setWakeListeningSuspendedForIme(context: Context, imeVisible: Boolean) {
            lastRequestedImeVisible = imeVisible            if (!isRunning.get()) return            val intent = Intent(context, AIForegroundService::
class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME                putExtra(EXTRA_IME_VISIBLE, imeVisible)
}
            try {
                context.startService(intent)
}
 catch (e: Exception) {
                AppLogger.e(TAG, "Failed to request IME wake listening suspend: ${
e.message
}", e)
}

}
        fun setWakeListeningSuspendedForFloatingFullscreen(context: Context, active: Boolean) {
            if (!isRunning.get()) return            val intent = Intent(context, AIForegroundService::
class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN                putExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, active)
}
            try {
                context.startService(intent)
}
 catch (e: Exception) {
                AppLogger.e(                    TAG,                    "Failed to request floating fullscreen wake listening suspend: ${
e.message
}",                    e                )
}

}
        fun ensureMicrophoneForeground(context: Context, forceStart: Boolean = false) {
            val appContext = context.applicationContext            if (!forceStart && !isRunning.get() && !hasPersistentForegroundResponsibilityConfigured(appContext)) {
                return
}
            val intent = Intent(appContext, AIForegroundService::
class.java).apply {
                action = ACTION_ENSURE_MICROPHONE_FOREGROUND
}
            try {
                if (isRunning.get()) {
                    appContext.startService(intent)
}
 else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(intent)
}
 else {
                        appContext.startService(intent)
}

}

}
 catch (e: Exception) {
                AppLogger.w(TAG, "Failed to request microphone foreground", e)
}

}
        fun ensureRunningForExternalHttp(context: Context) {
            startServiceForAction(context, ACTION_START_OR_REFRESH_EXTERNAL_HTTP)
}
        fun stopExternalHttp(context: Context) {
            externalHttpStateFlow.value =                externalHttpStateFlow.value.copy(isRunning = false, lastError = null)            if (!isRunning.get()) {
                return
}
            startServiceForAction(context, ACTION_STOP_EXTERNAL_HTTP)
}
        private fun startServiceForAction(context: Context, action: String) {
            val appContext = context.applicationContext            val intent = Intent(appContext, AIForegroundService::
class.java).apply {
                this.action = action
}
            try {
                if (isRunning.get()) {
                    appContext.startService(intent)
}
 else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
}
 else {
                    appContext.startService(intent)
}

}
 catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start action �?{
action
}: ${
e.message
}", e)
}

}
        private fun hasPersistentForegroundResponsibilityConfigured(context: Context): Boolean {
            val appContext = context.applicationContext            val alwaysListeningEnabled = runCatching {
                runBlocking {
                    WakeWordPreferences(appContext).alwaysListeningEnabledFlow.first()
}

}.getOrDefault(false)            val externalHttpEnabled = runCatching {
                ExternalHttpApiPreferences.getInstance(appContext).getConfigSync().let {
 config ->                    config.enabled && ExternalHttpApiPreferences.isValidPort(config.port)
}

}.getOrDefault(false)            return alwaysListeningEnabled || externalHttpEnabled
}

}
    private fun updateWakeListeningSuspendedForIme(imeVisible: Boolean) {
        if (wakeListeningSuspendedForIme == imeVisible) return        wakeListeningSuspendedForIme = imeVisible        AppLogger.d(TAG, "Wake listening suspended by IME: �?{
wakeListeningSuspendedForIme
}")        applyWakeListeningState()
}
    private fun updateWakeListeningSuspendedForExternalRecording(externalRecording: Boolean) {
        if (wakeListeningSuspendedForExternalRecording == externalRecording) return        wakeListeningSuspendedForExternalRecording = externalRecording        AppLogger.d(TAG, "Wake listening suspended by external recording: �?{
wakeListeningSuspendedForExternalRecording
}")        applyWakeListeningState()
}
    private fun updateWakeListeningSuspendedForFloatingFullscreen(active: Boolean) {
        if (wakeListeningSuspendedForFloatingFullscreen == active) return        wakeListeningSuspendedForFloatingFullscreen = active        AppLogger.d(TAG, "Wake listening suspended by floating fullscreen: �?{
wakeListeningSuspendedForFloatingFullscreen
}")        applyWakeListeningState()
}
        private fun applyWakeListeningState() {
        wakeStateApplyJob?.cancel()        wakeStateApplyJob =            serviceScope.launch {
                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
}

}

}
    private suspend fun applyWakeListeningStateLocked() {
        val shouldListen =            wakeListeningEnabled &&                !wakeListeningSuspendedForIme &&                !wakeListeningSuspendedForExternalRecording &&                !wakeListeningSuspendedForFloatingFullscreen        if (shouldListen) {
            startWakeListeningLocked()
}
 else {
            val shouldRelease = !wakeListeningEnabled || wakeListeningSuspendedForFloatingFullscreen            stopWakeListeningLocked(releaseProvider = shouldRelease)
}
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager        manager.notify(NOTIFICATION_ID, createNotification())
}
    private fun startRecordingStateMonitoring() {
        if (!wakeListeningEnabled) return        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return        if (audioRecordingCallback != null) return        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return        audioManager = am        val callback =            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val isWakeListeningRunning =                        wakeListeningMicActiveForRecordingDetection ||                            wakeListeningJob?.isActive == true ||                            personalWakeJob?.isActive == true                    val myUid = Process.myUid()                    val myPackageName = packageName                    fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                        val uid = cfg.tryGetClientUid()                        if (uid != null && uid > 0) {
                            if (uid == myUid) return false                            if (uid < Process.FIRST_APPLICATION_UID) {
                                return false
}
                            val pkg = cfg.tryGetClientPackageName()?.take
if {
 it.isNotBlank()
}
                            if (pkg != null) {
                                return pkg != myPackageName
}
                            return true
}
                        val pkg = cfg.tryGetClientPackageName()?.take
if {
 it.isNotBlank()
}
                        if (uid == null || uid <= 0) {
                            if (isWakeListeningRunning) return false                            if (pkg != null) return pkg != myPackageName                            return true
}
                        return false
}
                    val hasExternal = configs.any(::isExternalConfig)                    if (hasExternal != wakeListeningSuspendedForExternalRecording) {
                        val summary =                            configs.mapIndexed {
 idx, cfg ->                                val uid = cfg.tryGetClientUid()                                val pkg = cfg.tryGetClientPackageName()                                val hasUidMethod = cfg.java
class.methods.any {
 it.name == "getClientUid" && it.parameterTypes.isEmpty()
}
                                val hasPkgMethod = cfg.java
class.methods.any {
 it.name == "getClientPackageName" && it.parameterTypes.isEmpty()
}
                                val raw = try {
                                    cfg.toString().replace('\n', ' ').replace('\r', ' ')
}
 catch (_: Exception) {
                                    ""
}
                                val rawShort = if (raw.length > 220) raw.substring(0, 220) else raw                                "#�?{
idx
}
 uid=${
uid ?: "?"
},pkg=${
pkg ?: "?"
},mUid=�?{
hasUidMethod
},mPkg=�?{
hasPkgMethod
}
 cfg=�?{
rawShort
}"
}.joinToString(" | ")                        AppLogger.d(                            TAG,                            "Recording configs changed: wakeRunning=�?{
isWakeListeningRunning
}
 micFlag=�?{
wakeListeningMicActiveForRecordingDetection
}
 external=�?{
hasExternal
}
 count=${
configs.size
}
 configs=[�?{
summary
}]"                        )
}
                    updateWakeListeningSuspendedForExternalRecording(hasExternal)
}

}
        audioRecordingCallback = callback        try {
            am.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
}
 catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register recording callback: ${
e.message
}", e)            audioRecordingCallback = null            audioManager = null            return
}
        try {
            val configs = am.activeRecordingConfigurations            val isWakeListeningRunning =                wakeListeningMicActiveForRecordingDetection ||                    wakeListeningJob?.isActive == true ||                    personalWakeJob?.isActive == true            val myUid = Process.myUid()            val myPackageName = packageName            fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                val uid = cfg.tryGetClientUid()                if (uid != null && uid > 0) {
                    if (uid == myUid) return false                    if (uid < Process.FIRST_APPLICATION_UID) {
                        return false
}
                    val pkg = cfg.tryGetClientPackageName()?.take
if {
 it.isNotBlank()
}
                    if (pkg != null) {
                        return pkg != myPackageName
}
                    return true
}
                val pkg = cfg.tryGetClientPackageName()?.take
if {
 it.isNotBlank()
}
                if (uid == null || uid <= 0) {
                    if (isWakeListeningRunning) return false                    if (pkg != null) return pkg != myPackageName                    return true
}
                return false
}
            val hasExternal = configs.any(::isExternalConfig)            updateWakeListeningSuspendedForExternalRecording(hasExternal)
}
 catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read active recording configs: ${
e.message
}", e)
}

}
    private fun stopRecordingStateMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return        val am = audioManager        val callback = audioRecordingCallback        if (am != null && callback != null) {
            try {
                am.unregisterAudioRecordingCallback(callback)
}
 catch (e: Exception) {
                AppLogger.e(TAG, "Failed to unregister recording callback: ${
e.message
}", e)
}

}
        audioRecordingCallback = null        audioManager = null        wakeListeningSuspendedForExternalRecording = false
}
        // 存储通知信息    private var characterName: String? = null    private var avatarUri: String? = null    private var isAiBusy: Boolean = false    @Volatile    private var hideRuntimeTaskViewEnabled: Boolean = false    @Volatile    private var lastAppliedRuntimeTaskViewHidden: Boolean? = null    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)    private val chatRuntimeHolder by lazy {
 ChatRuntimeHolder.getInstance(applicationContext)
}
    private val wakePrefs by lazy {
 WakeWordPreferences(applicationContext)
}
    @Volatile    private var wakeSpeechProvider: SpeechService? = null    private val workflowRepository by lazy {
 WorkflowRepository(applicationContext)
}
    private val externalHttpPreferences by lazy {
 ExternalHttpApiPreferences.getInstance(applicationContext)
}
    private val mainHandler by lazy {
 Handler(Looper.getMainLooper())
}
    private var keepAliveOverlayView: View? = null    private var keepAliveOverlayPermissionLogged = false    private var wakeMonitorJob: Job? = null    private var externalHttpMonitorJob: Job? = null    private var wakeListeningJob: Job? = null    private var wakeResumeJob: Job? = null    private val wakeStateMutex = Mutex()    private var wakeStateApplyJob: Job? = null    private var wakeStateRetryJob: Job? = null    private var personalWakeJob: Job? = null    private var personalWakeListener: PersonalWakeListener? = null    @Volatile    private var currentWakePhrase: String = WakeWordPreferences.DEFAULT_WAKE_PHRASE    @Volatile    private var wakePhraseRegexEnabled: Boolean = WakeWordPreferences.DEFAULT_WAKE_PHRASE_REGEX_ENABLED    @Volatile    private var wakeRecognitionMode: WakeWordPreferences.WakeRecognitionMode = WakeWordPreferences.WakeRecognitionMode.STT    @Volatile    private var personalWakeTemplates: List<FloatArray> = emptyList()    @Volatile    private var wakeListeningEnabled: Boolean = false    @Volatile    private var wakeListeningMicActiveForRecordingDetection: Boolean = false    @Volatile    private var wakeListeningSuspendedForIme: Boolean = false    @Volatile    private var wakeListeningSuspendedForExternalRecording: Boolean = false    @Volatile    private var wakeListeningSuspendedForFloatingFullscreen: Boolean = false    private var audioManager: AudioManager? = null    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null    private var externalHttpServer: ExternalChatHttpServer? = null    private var externalHttpCurrentPort: Int? = null    private var lastWakeTriggerAtMs: Long = 0L    @Volatile    private var pendingWakeTriggeredAtMs: Long = 0L    @Volatile    private var wakeHandoffPending: Boolean = false    @Volatile    private var wakeStopInProgress: Boolean = false    private var lastSpeechWorkflowCheckAtMs: Long = 0L    private fun ensureWakeSpeechProvider(): SpeechService {
        val existing = wakeSpeechProvider        if (existing != null) return existing        return SpeechServiceFactory.createWakeSpeechService(applicationContext).also {
            wakeSpeechProvider = it
}

}
    private fun releaseWakeSpeechProvider() {
        val provider = wakeSpeechProvider ?: return        wakeSpeechProvider = null        try {
            provider.shutdown()
}
 catch (_: Exception) {

}

}
    private fun startOrRefreshExternalHttpServer(config: ExternalHttpApiConfig = externalHttpPreferences.getConfigSync()) {
        if (!config.enabled) {
            AppLogger.i(TAG, "External HTTP API disabled, stopping runtime")            stopExternalHttpServer(port
override = config.port, lastError = null)            stopSelfIfIdle(ignoreAppForeground = true)            return
}
        if (!ExternalHttpApiPreferences.isValidPort(config.port)) {
            val message = "Invalid port: ${
config.port
}"            AppLogger.w(TAG, message)            stopExternalHttpServer(port
override = config.port, lastError = message)            stopSelfIfIdle(ignoreAppForeground = true)            return
}
        if (externalHttpServer != null && externalHttpCurrentPort == config.port) {
            updateExternalHttpState(                ExternalChatHttpState(                    isRunning = true,                    port = config.port,                    lastError = null                )            )            return
}
        stopExternalHttpServer()        try {
            val newServer = ExternalChatHttpServer(applicationContext, externalHttpPreferences, serviceScope)            newServer.startServer()            externalHttpServer = newServer            externalHttpCurrentPort = config.port            updateExternalHttpState(                ExternalChatHttpState(                    isRunning = true,                    port = config.port,                    lastError = null                )            )
}
 catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start external HTTP server", e)            stopExternalHttpServer(                port
override = config.port,                lastError = e.message ?: "Failed to start server"            )            stopSelfIfIdle(ignoreAppForeground = true)
}

}
    private fun stopExternalHttpServer(port
override: Int? = null, lastError: String? = null) {
        runCatching {
            externalHttpServer?.stopServer()
}.onFailure {
 error ->            AppLogger.e(TAG, "Failed to stop external HTTP server", error)
}
        val stoppedPort = port
override ?: externalHttpCurrentPort ?: externalHttpStateFlow.value.port        externalHttpServer = null        externalHttpCurrentPort = null        updateExternalHttpState(            ExternalChatHttpState(                isRunning = false,                port = stoppedPort,                lastError = lastError            )        )
}
    private fun updateExternalHttpState(        newState: ExternalChatHttpState,        refreshNotification: Boolean = true    ) {
        externalHttpStateFlow.value = newState        if (refreshNotification) {
            refreshServiceNotification()
}

}
    private fun refreshServiceNotification() {
        if (!isRunning.get()) {
            return
}
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager        manager.notify(NOTIFICATION_ID, createNotification())
}
    private fun isExternalHttpEnabledNow(): Boolean {
        return runCatching {
            externalHttpPreferences.getConfigSync().let {
 config ->                config.enabled && ExternalHttpApiPreferences.isValidPort(config.port)
}

}.getOrDefault(false)
}
    private fun stopSelfIfIdle(ignoreAppForeground: Boolean = false) {
        val alwaysListeningEnabled = wakeListeningEnabled || isAlwaysListeningEnabledNow()        val externalHttpEnabled = externalHttpStateFlow.value.isRunning || isExternalHttpEnabledNow()        if (isAiBusy || alwaysListeningEnabled || externalHttpEnabled) {
            return
}
        if (!ignoreAppForeground && ActivityLifecycleManager.getCurrentActivity() != null) {
            return
}
        AppLogger.d(TAG, "No active foreground responsibilities, stopping AIForegroundService")        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")            stopForeground(Service.STOP_FOREGROUND_REMOVE)
}
 else {
            @Suppress("DEPRECATION")            stopForeground(true)
}
        stopSelf()
}
    override fun onCreate() {
        super.onCreate()        isRunning.set(true)        wakeListeningSuspendedForIme = lastRequestedImeVisible        AppLogger.d(TAG, "AI 前台服务创建�?       chatRuntimeHolder        createNotificationChannel()        val notification = createNotification()        ForegroundServiceCompat.startForeground(            service = this,            notificationId = NOTIFICATION_ID,            notification = notification,            types = ForegroundServiceCompat.buildTypes(                dataSync = true,                specialUse = runCatching {
 externalHttpPreferences.getEnabled()
}.getOrDefault(false)            )        )        observeRuntimeTaskViewPreference()        observeChatRuntimeStats()        startWakeMonitoring()        startExternalHttpMonitoring()        AppLogger.d(TAG, "AI 前台服务已启动）
}
    private fun observeRuntimeTaskViewPreference() {
        serviceScope.launch {
            try {
                DisplayPreferencesManager                    .getInstance(applicationContext)                    .hideRuntimeTaskView                    .collectLatest {
 enabled ->                        hideRuntimeTaskViewEnabled = enabled                        updateRuntimeTaskViewVisibility()
}

}
 catch (e: Exception) {
                AppLogger.e(TAG, "监听运行时任务视图隐藏设置失�?${
e.message
}", e)
}

}

}
    private fun updateAiBusyState(isBusy: Boolean) {
        isAiBusy = isBusy        updateRuntimeTaskViewVisibility()
}
    private fun updateRuntimeTaskViewVisibility() {
        val shouldHide = hideRuntimeTaskViewEnabled && isAiBusy        if (lastAppliedRuntimeTaskViewHidden == shouldHide) return        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager            val appTasks = activityManager?.appTasks.orEmpty()            if (appTasks.isEmpty()) {
                AppLogger.d(TAG, "更新运行时任务视图隐藏状态时未找到任�?hidden=�?{
shouldHide
}")                return
}
            appTasks.forEach {
 task ->                try {
                    task.setExcludeFromRecents(shouldHide)
}
 catch (e: Exception) {
                    AppLogger.e(TAG, "设置任务最近任务可见性失�?hidden=�?{
shouldHide
}", e)
}

}
            lastAppliedRuntimeTaskViewHidden = shouldHide            AppLogger.d(TAG, "运行时任务视图隐藏状态已更新: hidden=�?{
shouldHide
}, taskCount=${
appTasks.size
}")
}
 catch (e: Exception) {
            AppLogger.e(TAG, "更新运行时任务视图隐藏状态失�?hidden=�?{
shouldHide
}", e)
}

}
    private fun observeChatRuntimeStats() {
        serviceScope.launch {
            combine(                chatRuntimeHolder.activeConversationCount,                chatRuntimeHolder.currentSessionToolCount            ) {
 _, _ ->                Unit
}.collect {
                if (!isRunning.get()) return@collect                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager                manager.notify(NOTIFICATION_ID, createNotification())
}

}

}
    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==                PackageManager.PERMISSION_GRANTED
}
 else {
            true
}

}
    private fun isAlwaysListeningEnabledNow(): Boolean {
        return try {
            runBlocking {
 wakePrefs.alwaysListeningEnabledFlow.first()
}

}
 catch (_: Exception) {
            false
}

}
    private suspend fun tryPromoteToMicrophoneForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false        if (!hasRecordAudioPermission()) return false        var waitedMs = 0L        while (waitedMs < 3500L && ActivityLifecycleManager.getCurrentActivity() == null) {
            delay(150)            waitedMs += 150
}
        if (ActivityLifecycleManager.getCurrentActivity() == null) {
            AppLogger.w(TAG, "promote microphone foreground skipped: app not in foreground")            return false
}
        val types = ForegroundServiceCompat.buildTypes(dataSync = true, microphone = true)        return try {
            ForegroundServiceCompat.startForeground(                service = this,                notificationId = NOTIFICATION_ID,                notification = createNotification(),                types = types            )            true
}
 catch (e: SecurityException) {
            AppLogger.e(TAG, "promote microphone foreground failed: ${
e.message
}", e)            false
}

}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT_APP) {
            isRunning.set(false)            updateAiBusyState(false)            try {
                AIMessageManager.cancelCurrentOperation()
}
 catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消当前AI任务失败: ${
e.message
}", e)
}
            try {
                stopService(Intent(this, FloatingChatService::
class.java))
}
 catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 FloatingChatService 失败: ${
e.message
}", e)
}
            try {
                stopService(Intent(this, UIDebuggerService::
class.java))
}
 catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 UIDebuggerService 失败: ${
e.message
}", e)
}
            stopExternalHttpServer(lastError = null)            try {
                val activity = ActivityLifecycleManager.getCurrentActivity()                activity?.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        activity.finishAndRemoveTask()
}
 else {
                        activity.finish()
}

}

}
 catch (e: Exception) {
                AppLogger.e(TAG, "退出时关闭前台界面失败: ${
e.message
}", e)
}
            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager                manager.cancel(NOTIFICATION_ID)                activeReplyNotificationTags.forEach {
 tag ->                    manager.cancel(tag, REPLY_NOTIFICATION_ID)
}
                activeReplyNotificationTags.clear()                manager.cancel(REPLY_NOTIFICATION_ID)
}
 catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消通知失败: ${
e.message
}", e)
}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")                stopForeground(Service.STOP_FOREGROUND_REMOVE)
}
 else {
                @Suppress("DEPRECATION")                stopForeground(true)
}
            stopSelf()            Process.killProcess(Process.myPid())            exitProcess(0)            return START_NOT_STICKY
}
        if (intent?.action == ACTION_ENSURE_MICROPHONE_FOREGROUND) {
            serviceScope.launch {
                try {
                    tryPromoteToMicrophoneForeground()
}
 catch (e: Exception) {
                    AppLogger.w(TAG, "ensure microphone foreground failed", e)
}

}
            stopSelfIfIdle(ignoreAppForeground = true)            return START_NOT_STICKY
}
        if (intent?.action == ACTION_START_OR_REFRESH_EXTERNAL_HTTP || intent == null) {
            startOrRefreshExternalHttpServer()            return if (externalHttpStateFlow.value.isRunning) START_STICKY else START_NOT_STICKY
}
        if (intent?.action == ACTION_STOP_EXTERNAL_HTTP) {
            val configuredPort = runCatching {
 externalHttpPreferences.getPort()
}.getOrNull()            stopExternalHttpServer(port
override = configuredPort, lastError = null)            stopSelfIfIdle(ignoreAppForeground = true)            return START_NOT_STICKY
}
        if (intent?.action == ACTION_TOGGLE_WAKE_LISTENING) {
            AppLogger.d(TAG, "收到 ACTION_TOGGLE_WAKE_LISTENING")            serviceScope.launch {
                try {
                    val current = wakePrefs.alwaysListeningEnabledFlow.first()                    AppLogger.d(TAG, "切换唤醒监听: �?{
current
}
 -> ${
!current
}")                    wakePrefs.saveAlwaysListeningEnabled(!current)
}
 catch (e: Exception) {
                    AppLogger.e(TAG, "切换唤醒监听失败: ${
e.message
}", e)
}
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager                manager.notify(NOTIFICATION_ID, createNotification())
}
            return START_NOT_STICKY
}
        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME) {
            val imeVisible = intent.getBooleanExtra(EXTRA_IME_VISIBLE, false)            updateWakeListeningSuspendedForIme(imeVisible)            return START_NOT_STICKY
}
        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN) {
            val active = intent.getBooleanExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, false)            updateWakeListeningSuspendedForFloatingFullscreen(active)            return START_NOT_STICKY
}
        if (intent?.action == ACTION_PREPARE_WAKE_HANDOFF) {
            val now = System.currentTimeMillis()            val triggeredAt = pendingWakeTriggeredAtMs            if (triggeredAt > 0L && wakeHandoffPending) {
                val elapsedMs = (now - triggeredAt).coerceAtLeast(0L)                val dynamicMarginMs = (elapsedMs / 4L).coerceIn(150L, 650L)                val windowMs = (elapsedMs + dynamicMarginMs).coerceIn(200L, 2500L)                AppLogger.d(                    TAG,                    "Wake handoff prepare: elapsedMs=�?{
elapsedMs
}, marginMs=�?{
dynamicMarginMs
}, captureWindowMs=�?{
windowMs
}"                )                SpeechPrerollStore.capturePending(windowMs = windowMs.toInt())                SpeechPrerollStore.armPending()                if (!wakeStopInProgress) {
                    wakeStopInProgress = true                    serviceScope.launch {
                        try {
                            stopWakeListening(releaseProvider = true)
}
 finally {
                            wakeStopInProgress = false                            wakeHandoffPending = false                            pendingWakeTriggeredAtMs = 0L                            SpeechPrerollStore.clearPendingWakePhrase()
}

}

}

}
 else {
                AppLogger.d(TAG, "Wake handoff prepare ignored: pending=�?{
wakeHandoffPending
}, triggeredAt=�?{
triggeredAt
}")
}
            return START_NOT_STICKY
}
        if (intent?.action == ACTION_CANCEL_CURRENT_OPERATION) {
            try {
                AIMessageManager.cancelCurrentOperation()                // 立即刷新通知状态（真正的状态重置由 EnhancedAIService.cancelConversation/stopAiService 完成�?               updateAiBusyState(false)
}
 catch (e: Exception) {
                AppLogger.e(TAG, "取消当前AI任务失败: ${
e.message
}", e)
}
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager            manager.notify(NOTIFICATION_ID, createNotification())            return START_NOT_STICKY
}
        // 从Intent中提取通知信息        intent?.let {
            characterName = it.getStringExtra(EXTRA_CHARACTER_NAME)            avatarUri = it.getStringExtra(EXTRA_AVATAR_URI)            AppLogger.d(TAG, "收到通知数据 - 角色: �?{
characterName
}, 头像: �?{
avatarUri
}")            val state = it.getStringExtra(EXTRA_STATE)            if (state != null) {
                updateAiBusyState(state == STATE_RUNNING)                val alwaysListeningEnabled = isAlwaysListeningEnabledNow()                val externalHttpEnabled =                    externalHttpStateFlow.value.isRunning || isExternalHttpEnabledNow()                if (!isAiBusy &&                    !alwaysListeningEnabled &&                    !externalHttpEnabled                ) {
                    AppLogger.d(TAG, "服务进入空闲且无持久后台职责，停止前台服务并移除通知")                    stopSelfIfIdle(ignoreAppForeground = true)                    return START_NOT_STICKY
}
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager                manager.notify(NOTIFICATION_ID, createNotification())
}

}
                // ??External HTTP 处于启用状态时，使？START_STICKY 提高后台保活强度�?       // 其他场景仍由 EnhancedAIService 与前台交互精确控制生命周期？        return if (isExternalHttpEnabledNow()) START_STICKY else START_NOT_STICKY
}
    override fun onDestroy() {
        val stoppedPort = externalHttpCurrentPort ?: externalHttpStateFlow.value.port        runCatching {
            externalHttpServer?.stopServer()
}.onFailure {
 error ->            AppLogger.e(TAG, "Failed to stop external HTTP server", error)
}
        externalHttpServer = null        externalHttpCurrentPort = null        updateExternalHttpState(            ExternalChatHttpState(                isRunning = false,                port = stoppedPort,                lastError = null            ),            refreshNotification = false        )        super.onDestroy()        isRunning.set(false)        updateAiBusyState(false)        hideKeepAliveOverlay()        stopWakeMonitoring()        AppLogger.d(TAG, "AI 前台服务已销毁）
}
    override fun onBind(intent: Intent): IBinder? {
        // 该服务是启动服务，不提供绑定功能�?       return null
}
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.service_Apex_running)            val serviceChannel =                    NotificationChannel(                            CHANNEL_ID,                            channelName,                            NotificationManager.IMPORTANCE_LOW // 低重要性，避免打扰用户                    )                    .apply {
                        description = getString(R.string.service_keep_background)
}
            val manager = getSystemService(NotificationManager::
class.java)            manager.createNotificationChannel(serviceChannel)
}

}
    private fun startExternalHttpMonitoring() {
        if (externalHttpMonitorJob?.isActive == true) return        if (isExternalHttpEnabledNow()) {
            startOrRefreshExternalHttpServer()
}
 else {
            updateExternalHttpState(                externalHttpStateFlow.value.copy(                    isRunning = false,                    port = runCatching {
 externalHttpPreferences.getPort()
}.getOrNull(),                    lastError = null                )            )
}
        externalHttpMonitorJob =            serviceScope.launch {
                combine(                    externalHttpPreferences.enabledFlow,                    externalHttpPreferences.portFlow                ) {
 enabled, port ->                    enabled to port
}.collectLatest {
 (enabled, port) ->                    AppLogger.d(TAG, "External HTTP config updated: enabled=�?{
enabled
}, port=�?{
port
}")                    if (enabled) {
                        startOrRefreshExternalHttpServer(                            config = externalHttpPreferences.getConfigSync()                        )
}
 else {
                        stopExternalHttpServer(port
override = port, lastError = null)                        stopSelfIfIdle(ignoreAppForeground = true)
}

}

}

}
    private fun startWakeMonitoring() {
        if (wakeMonitorJob?.isActive == true) return        AppLogger.d(TAG, "startWakeMonitoring")        wakeMonitorJob =            serviceScope.launch {
                launch {
                    wakePrefs.wakePhraseFlow.collectLatest {
 phrase ->                        currentWakePhrase = phrase.ifBlank {
 WakeWordPreferences.DEFAULT_WAKE_PHRASE
}
                        AppLogger.d(TAG, "唤醒词更�?'�?{
currentWakePhrase
}'")
}

}
                launch {
                    wakePrefs.wakePhraseRegexEnabledFlow.collectLatest {
 enabled ->                        wakePhraseRegexEnabled = enabled                        AppLogger.d(TAG, "唤醒词正则开关更�?enabled=�?{
enabled
}")
}

}
                launch {
                    wakePrefs.wakeRecognitionModeFlow.collectLatest {
 mode ->                        wakeRecognitionMode = mode                        AppLogger.d(TAG, "唤醒识别模式更新: �?{
mode
}")                        applyWakeListeningState()
}

}
                launch {
                    wakePrefs.personalWakeTemplatesFlow.collectLatest {
 templates ->                        personalWakeTemplates = templates.mapNotNull {
 t ->                            val feats = t.features                            if (feats.isEmpty()) null else feats.toFloatArray()
}
                        AppLogger.d(TAG, "个人化唤醒模板更�?count=${
personalWakeTemplates.size
}")                        applyWakeListeningState()
}

}
                wakePrefs.alwaysListeningEnabledFlow.collectLatest {
 enabled ->                    wakeListeningEnabled = enabled                    AppLogger.d(TAG, "唤醒监听开关更�?enabled=�?{
enabled
}")                    if (enabled) {
                        showKeepAliveOverlayIfPossible()
}
 else {
                        hideKeepAliveOverlay()
}
                    if (enabled) {
                        startRecordingStateMonitoring()
}
 else {
                        stopRecordingStateMonitoring()
}
                    applyWakeListeningState()                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager                    manager.notify(NOTIFICATION_ID, createNotification())
}

}

}
    private fun stopWakeMonitoring() {
        externalHttpMonitorJob?.cancel()        externalHttpMonitorJob = null        wakeMonitorJob?.cancel()        wakeMonitorJob = null        wakeResumeJob?.cancel()        wakeResumeJob = null        wakeListeningJob?.cancel()        wakeListeningJob = null        personalWakeListener?.stop()        personalWakeListener = null        personalWakeJob?.cancel()        personalWakeJob = null        stopRecordingStateMonitoring()        hideKeepAliveOverlay()        try {
            serviceScope.cancel()
}
 catch (_: Exception) {

}
        releaseWakeSpeechProvider()
}
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
}
 else {
            mainHandler.post {
                try {
                    action()
}
 catch (e: Exception) {
                    AppLogger.e(TAG, "Error on main thread", e)
}

}

}

}
    private fun showKeepAliveOverlayIfPossible() {
        if (keepAliveOverlayView != null) return        if (!Settings.canDrawOverlays(this)) {
            if (!keepAliveOverlayPermissionLogged) {
                keepAliveOverlayPermissionLogged = true                AppLogger.w(TAG, "Keep-alive overlay skipped: missing overlay permission")
}
            return
}
        runOnMainThread {
            if (keepAliveOverlayView != null) return@runOnMainThread            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager                val view = View(this)                ViewCompat.setOnApplyWindowInsetsListener(view) {
 _, insets ->                    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())                    updateWakeListeningSuspendedForIme(imeVisible)                    insets
}
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
 else {
                    @Suppress("DEPRECATION")                    WindowManager.LayoutParams.TYPE_PHONE
}
                val params = WindowManager.LayoutParams(                    1,                    1,                    layoutType,                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,                    PixelFormat.TRANSLUCENT                ).apply {
                    gravity = Gravity.TOP or Gravity.START                    x = 0                    y = 0
}
                wm.addView(view, params)                keepAliveOverlayView = view                ViewCompat.requestApplyInsets(view)                AppLogger.d(TAG, "Keep-alive overlay shown")
}
 catch (e: Exception) {
                AppLogger.e(TAG, "Failed to show keep-alive overlay: ${
e.message
}", e)                keepAliveOverlayView = null
}

}

}
    private fun hideKeepAliveOverlay() {
        val view = keepAliveOverlayView ?: return        runOnMainThread {
            val current = keepAliveOverlayView ?: return@runOnMainThread            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager                wm.removeView(current)                AppLogger.d(TAG, "Keep-alive overlay hidden")
}
 catch (e: Exception) {
                AppLogger.e(TAG, "Failed to hide keep-alive overlay: ${
e.message
}", e)
}
 finally {
                if (keepAliveOverlayView === view) {
                    keepAliveOverlayView = null
}

}

}

}
    private suspend fun startWakeListening() {
        wakeStateMutex.withLock {
            startWakeListeningLocked()
}

}
    private suspend fun startWakeListeningLocked() {
        if (!wakeListeningEnabled) return        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.STT) {
            if (personalWakeJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping personal wake before starting STT")                stopWakeListeningLocked(releaseProvider = true)
}
            if (wakeListeningJob?.isActive == true) return
}
 else {
            if (wakeListeningJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping STT wake before starting personal")                stopWakeListeningLocked(releaseProvider = true)
}
            if (personalWakeJob?.isActive == true) return
}
        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.PERSONAL_TEMPLATE) {
            startPersonalWakeListening()            return
}
        AppLogger.d(TAG, "startWakeListening: phrase='�?{
currentWakePhrase
}'")        if (wakeHandoffPending && !wakeStopInProgress && FloatingChatService.getInstance() == null) {
            AppLogger.d(TAG, "Clearing stale wake handoff pending state before starting wake listening")            wakeHandoffPending = false            wakeStopInProgress = false            pendingWakeTriggeredAtMs = 0L            SpeechPrerollStore.clearPendingWakePhrase()
}
        val micGranted =            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==                PackageManager.PERMISSION_GRANTED        if (!micGranted) {
            AppLogger.e(TAG, "启动唤醒监听失败: 未授？RECORD_AUDIO（请在系统设置中允许麦克风权限）")            wakeListeningEnabled = false            try {
                wakePrefs.saveAlwaysListeningEnabled(false)
}
 catch (_: Exception) {

}
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager            manager.notify(NOTIFICATION_ID, createNotification())            return
}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            tryPromoteToMicrophoneForeground()
}
        wakeResumeJob?.cancel()        wakeResumeJob = null        try {
            val provider = ensureWakeSpeechProvider()            val initOk = provider.initialize()            AppLogger.d(TAG, "唤醒识别？initialize: ok=�?{
initOk
}")            wakeListeningMicActiveForRecordingDetection = true            val startOk = provider.startRecognition(                languageCode = "zh-CN",                continuousMode = true,                partialResults = true            )            AppLogger.d(TAG, "唤醒识别？startRecognition: ok=�?{
startOk
}")            if (!startOk) {
                val alreadyRunning =                    provider.isRecognizing ||                        provider.currentState == SpeechService.RecognitionState.PREPARING ||                        provider.currentState == SpeechService.RecognitionState.PROCESSING ||                        provider.currentState == SpeechService.RecognitionState.RECOGNIZING                if (!alreadyRunning) {
                    AppLogger.w(TAG, "唤醒识别？startRecognition failed (will re
try)")                    wakeListeningMicActiveForRecordingDetection = false                    wakeStateRetryJob?.cancel()                    wakeStateRetryJob =                        serviceScope.launch {
                            delay(650)                            wakeStateMutex.withLock {
                                applyWakeListeningStateLocked()
}

}
                    return
}

}

}
 catch (e: Exception) {
            wakeListeningMicActiveForRecordingDetection = false            AppLogger.e(TAG, "启动唤醒监听失败: ${
e.message
}", e)            return
}
        if (wakeListeningJob?.isActive == true) return        wakeListeningJob =            serviceScope.launch {
                var lastText = ""                var lastIsFinal = false                val provider = ensureWakeSpeechProvider()                provider.recognitionResultFlow.collectLatest {
 result ->                    val text = result.text                    if (text.isBlank()) return@collectLatest                    if (text == lastText && result.isFinal == lastIsFinal) return@collectLatest                    lastText = text                    lastIsFinal = result.isFinal                    AppLogger.d(                        TAG,                        "唤醒识别输出(${
if (result.isFinal) "final" else "partial"
}): '�?{
text
}'"                    )                    if (wakeHandoffPending) {
                        val floatingAlive = FloatingChatService.getInstance() != null                        if (!wakeStopInProgress && !floatingAlive) {
                            AppLogger.d(TAG, "Clearing stale wake handoff pending state (no floating instance)")                            wakeHandoffPending = false                            wakeStopInProgress = false                            pendingWakeTriggeredAtMs = 0L                            SpeechPrerollStore.clearPendingWakePhrase()
}
 else {
                            return@collectLatest
}

}
                    try {
                        val now = System.currentTimeMillis()                        val shouldCheckWorkflows = result.isFinal || now - lastSpeechWorkflowCheckAtMs >= 350L                        if (shouldCheckWorkflows) {
                            lastSpeechWorkflowCheckAtMs = now                            workflowRepository.triggerWorkflowsBySpeechEvent(text = text, isFinal = result.isFinal)
}

}
 catch (e: Exception) {
                        AppLogger.e(TAG, "Speech trigger processing failed: ${
e.message
}", e)
}
                    if (matchWakePhrase(text, currentWakePhrase, wakePhraseRegexEnabled)) {
                        val now = System.currentTimeMillis()                        if (now - lastWakeTriggerAtMs < 3000L) return@collectLatest                        lastWakeTriggerAtMs = now                        pendingWakeTriggeredAtMs = now                        wakeHandoffPending = true                        wakeStopInProgress = false                        AppLogger.d(TAG, "命中唤醒�?'�?{
currentWakePhrase
}' in '�?{
text
}'")                        SpeechPrerollStore.setPendingWakePhrase(                            phrase = currentWakePhrase,                            regexEnabled = wakePhraseRegexEnabled,                        )                        triggerWakeLaunch()                        scheduleWakeResume()
}

}

}

}
    private suspend fun startPersonalWakeListening() {
        if (!wakeListeningEnabled) return        if (personalWakeJob?.isActive == true) return        AppLogger.d(TAG, "startPersonalWakeListening: templates=${
personalWakeTemplates.size
}")        if (personalWakeTemplates.isEmpty()) {
            AppLogger.w(TAG, "Personal wake listening skipped: no templates")            return
}
        wakeListeningMicActiveForRecordingDetection = true        val listener =            PersonalWakeListener(                context = applicationContext,                templatesProvider = {
 personalWakeTemplates
},                onTriggered = onTriggered@{
 similarity ->                    val now = System.currentTimeMillis()                    if (now - lastWakeTriggerAtMs < 3000L) return@onTriggered                    lastWakeTriggerAtMs = now                    pendingWakeTriggeredAtMs = now                    wakeHandoffPending = true                    wakeStopInProgress = false                    AppLogger.d(TAG, "命中个人化唤�?similarity=�?{
similarity
}")                    SpeechPrerollStore.setPendingWakePhrase(                        phrase = currentWakePhrase,                        regexEnabled = wakePhraseRegexEnabled,                    )                    triggerWakeLaunch()                    scheduleWakeResume()
}
            )        personalWakeListener = listener        personalWakeJob =            serviceScope.launch {
                try {
                    listener.runLoop()
}
 catch (e: Exception) {
                    AppLogger.e(TAG, "Personal wake loop failed: ${
e.message
}", e)
}
 finally {
                    wakeListeningMicActiveForRecordingDetection = false
}

}

}
    private suspend fun stopWakeListening(releaseProvider: Boolean = false) {
        wakeStateMutex.withLock {
            stopWakeListeningLocked(releaseProvider = releaseProvider)
}

}
    private suspend fun stopWakeListeningLocked(releaseProvider: Boolean = false) {
        AppLogger.d(TAG, "stopWakeListening")        wakeListeningMicActiveForRecordingDetection = false        wakeResumeJob?.cancel()        wakeResumeJob = null        wakeStateRetryJob?.cancel()        wakeStateRetryJob = null        wakeListeningJob?.cancel()        wakeListeningJob = null        personalWakeListener?.stop()        personalWakeListener = null        personalWakeJob?.cancel()        personalWakeJob = null        try {
            wakeSpeechProvider?.cancelRecognition()
}
 catch (_: Exception) {

}
        if (releaseProvider) {
            AppLogger.d(TAG, "Releasing wake speech provider")            releaseWakeSpeechProvider()
}

}
    private fun scheduleWakeResume() {
        AppLogger.d(TAG, "scheduleWakeResume")        wakeResumeJob?.cancel()        wakeResumeJob =            serviceScope.launch {
                var waitedMs = 0L                while (isActive && waitedMs < 5000L) {
                    if (!wakeListeningEnabled) return@launch                    if (FloatingChatService.getInstance() != null) break                    delay(250)                    waitedMs += 250
}
                AppLogger.d(TAG, "等待悬浮窗启�?waitedMs=�?{
waitedMs
}, instance=${
FloatingChatService.getInstance() != null
}")                while (isActive) {
                    if (!wakeListeningEnabled) return@launch                    if (FloatingChatService.getInstance() == null) break                    delay(500)
}
                AppLogger.d(TAG, "检测到悬浮窗已关闭，准备恢复唤醒监�?               if (wakeHandoffPending) {
                    AppLogger.d(TAG, "Wake handoff aborted, clearing pending state")                    wakeHandoffPending = false                    wakeStopInProgress = false                    pendingWakeTriggeredAtMs = 0L                    SpeechPrerollStore.clearPendingWakePhrase()
}
                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
}

}

}
    private fun triggerWakeLaunch() {
        AppLogger.d(TAG, "triggerWakeLaunch: 打开全屏悬浮窗并进入语音")        try {
            val floatingIntent = Intent(this, FloatingChatService::
class.java).apply {
                putExtra("INITIAL_MODE", com.apex.ui.floating.FloatingMode.FULLSCREEN.name)                putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)                putExtra(FloatingChatService.EXTRA_WAKE_LAUNCHED, true)
}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
}
 else {
                startService(floatingIntent)
}

}
 catch (e: Exception) {
            AppLogger.e(TAG, "唤醒打开悬浮窗失�?${
e.message
}", e)
}

}
    private fun matchWakePhrase(recognized: String, phrase: String, regexEnabled: Boolean): Boolean {
        if (regexEnabled) {
            if (phrase.isBlank()) return false            return try {
                Regex(phrase).containsMatchIn(recognized)
}
 catch (e: Exception) {
                AppLogger.w(TAG, "Invalid wake phrase regex: '�?{
phrase
}' (${
e.message
})")                false
}

}
        val target = normalizeWakeText(phrase)        if (target.isBlank()) return false        val text = normalizeWakeText(recognized)        return text.contains(target)
}
    private fun normalizeWakeText(text: String): String {
        val cleaned =            text                .lowercase()                .replace(                    Regex("[\\s\\p{
Punct
}，。！？；：、“”‘’【】（?)\\[\\]{
}<>《》]+"),                    ""                )        return cleaned
}
    private fun createNotification(): Notification {
        // 为了简单起见，使用一个安卓内置图标？        // 在实际项目中，应替换为应用的自定义图标？        val wakeListeningEnabledSnapshot = wakeListeningEnabled        val wakeListeningSuspendedSnapshot = wakeListeningSuspendedForIme || wakeListeningSuspendedForExternalRecording || wakeListeningSuspendedForFloatingFullscreen        val externalHttpSnapshot = externalHttpStateFlow.value        val title =            if (isAiBusy) {
                characterName ?: getString(R.string.service_Apex_running)
}
 else {
                if (wakeListeningEnabledSnapshot) {
                    if (wakeListeningSuspendedSnapshot) {
                        getString(R.string.service_running_wake_pause)
}
 else {
                        getString(R.string.service_running_wake_listening)
}

}
 else {
                    getString(R.string.service_Apex_running)
}

}
        val activeConversationCount = chatRuntimeHolder.activeConversationCount.value        val currentSessionToolCount = chatRuntimeHolder.currentSessionToolCount.value        val contentText =            if (isAiBusy && activeConversationCount > 0) {
                val statsText = getString(                    R.string.service_running_stats,                    activeConversationCount,                    currentSessionToolCount                )                if (externalHttpSnapshot.isRunning && externalHttpSnapshot.port != null) {
                    getString(                        R.string.service_running_with_http,                        statsText,                        externalHttpSnapshot.port                    )
}
 else {
                    statsText
}

}
 else if (externalHttpSnapshot.isRunning && externalHttpSnapshot.port != null) {
                getString(                    R.string.service_running_http_listening,                    externalHttpSnapshot.port                )
}
 else {
                getString(R.string.service_Apex_running)
}
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)            .setContentTitle(title)            .setContentText(contentText)            .setSmallIcon(android.R.drawable.ic_dialog_info)            .setPriority(NotificationCompat.PRIORITY_LOW)            .setOngoing(true) // 使通知不可被用户清�?       val contentIntent = Intent(this, MainActivity::
class.java).apply {
            flags =                Intent.FLAG_ACTIVITY_NEW_TASK or                    Intent.FLAG_ACTIVITY_CLEAR_TOP or                    Intent.FLAG_ACTIVITY_SINGLE_TOP
}
        val contentPendingIntent = PendingIntent.getActivity(            this,            0,            contentIntent,            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
 else {
                PendingIntent.FLAG_UPDATE_CURRENT
}
        )        builder.setContentIntent(contentPendingIntent)        val floatingIntent = Intent(this, FloatingChatService::
class.java).apply {
            putExtra("INITIAL_MODE", com.apex.ui.floating.FloatingMode.FULLSCREEN.name)            putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
}
        val floatingPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(                this,                9005,                floatingIntent,                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT            )
}
 else {
            PendingIntent.getService(                this,                9005,                floatingIntent,                PendingIntent.FLAG_UPDATE_CURRENT            )
}
        builder.addAction(            android.R.drawable.ic_btn_speak_now,            getString(R.string.service_voice_floating_window),            floatingPendingIntent        )        val toggleWakeIntent = Intent(this, AIForegroundService::
class.java).apply {
            action = ACTION_TOGGLE_WAKE_LISTENING
}
        val toggleWakePendingIntent = PendingIntent.getService(            this,            REQUEST_CODE_TOGGLE_WAKE_LISTENING,            toggleWakeIntent,            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
 else {
                PendingIntent.FLAG_UPDATE_CURRENT
}
        )        builder.addAction(            android.R.drawable.ic_lock_silent_mode_off,            if (wakeListeningEnabledSnapshot) {
                getString(R.string.service_turn_off_wake)
}
 else {
                getString(R.string.service_turn_on_wake)
},            toggleWakePendingIntent        )        val exitIntent = Intent(this, AIForegroundService::
class.java).apply {
            action = ACTION_EXIT_APP
}
        val exitPendingIntent = PendingIntent.getService(            this,            REQUEST_CODE_EXIT_APP,            exitIntent,            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
 else {
                PendingIntent.FLAG_UPDATE_CURRENT
}
        )        builder.addAction(            android.R.drawable.ic_menu_close_clear_cancel,            getString(R.string.service_exit),            exitPendingIntent        )        if (isAiBusy) {
            val cancelIntent = Intent(this, AIForegroundService::
class.java).apply {
                action = ACTION_CANCEL_CURRENT_OPERATION
}
            val pendingIntent = PendingIntent.getService(                this,                REQUEST_CODE_CANCEL_CURRENT_OPERATION,                cancelIntent,                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
 else {
                    PendingIntent.FLAG_UPDATE_CURRENT
}
            )            builder.addAction(                android.R.drawable.ic_menu_close_clear_cancel,                getString(R.string.service_stop),                pendingIntent            )
}
        return builder.build()
}

}