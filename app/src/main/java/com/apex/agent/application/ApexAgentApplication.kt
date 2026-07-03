
package com.apex.agent.application

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.StrictMode
import android.system.Os
import com.apex.agent.util.AppLogger
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration as WorkConfiguration
import androidx.work.WorkManager
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.core.chat.AIMessageManager
import com.apex.agent.api.chat.AIForegroundService
import com.apex.agent.plugins.PluginRegistry
import com.apex.agent.plugins.lifecycle.AppLifecycleEvent
import com.apex.agent.plugins.lifecycle.AppLifecycleHookParams
import com.apex.agent.plugins.lifecycle.AppLifecycleHookPluginRegistry
import com.apex.agent.core.config.SystemPromptConfig
import com.apex.agent.core.tools.AIToolHandler
import com.apex.agent.core.tools.system.AndroidShellExecutor
import com.apex.agent.core.tools.system.Terminal
import com.apex.agent.core.workflow.WorkflowSchedulerInitializer
import com.apex.agent.data.backup.RoomDatabaseBackupPreferences
import com.apex.agent.data.backup.RoomDatabaseBackupScheduler
import com.apex.agent.data.db.AppDatabase
import com.apex.data.db.ObjectBoxManager
import com.apex.agent.data.preferences.ExternalHttpApiPreferences
import com.apex.agent.data.preferences.UserPreferencesManager
import com.apex.agent.data.preferences.WakeWordPreferences
import com.apex.agent.data.preferences.initAndroidPermissionPreferences
import com.apex.agent.data.preferences.initUserPreferencesManager
import com.apex.agent.data.preferences.preferencesManager
import com.apex.agent.core.tools.system.PermissionModeIntegration
import com.apex.agent.data.repository.CustomEmojiRepository
import com.apex.agent.ui.features.chat.webview.LocalWebServer
import com.apex.agent.ui.features.chat.webview.workspace.editor.language.LanguageFactory
import com.apex.agent.util.GlobalExceptionHandler
import com.apex.agent.util.ImagePoolManager
import com.apex.agent.util.LocaleUtils
import com.apex.agent.util.MediaPoolManager
import com.apex.agent.util.AppIconManager
import com.apex.agent.util.CrashRecoveryState
import com.apex.agent.util.ApexPaths
import com.apex.agent.util.SkillRepoZipPoolManager
import com.apex.agent.util.SerializationSetup
import com.apex.agent.util.TextSegmenter
import com.apex.agent.util.ThemeManager
import com.apex.agent.util.WaifuMessageProcessor
import com.apex.agent.core.tools.agent.ShowerController
import com.apex.agent.ui.common.displays.VirtualDisplayOverlay
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.apex.agent.core.tools.system.shower.ApexShowerShellRunner
import com.ai.assistance.showerclient.ShowerEnvironment
import com.ai.assistance.showerclient.ShowerLogSink
import com.apex.agent.core.hooks.HookRegistry
import com.apex.agent.core.hooks.SessionStartHook
import com.apex.agent.core.hooks.PreCompactHook
import com.apex.agent.core.hooks.SessionEndHook
import com.apex.agent.kernel.burst.BurstKernel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.apex.agent.application.AppState
import com.apex.agent.application.GlobalLifecycleManager
import dagger.hilt.android.HiltAndroidApp

/** Application class for apex */
@HiltAndroidApp
class ApexAgentApplication : Application(), ImageLoaderFactory, WorkConfiguration.Provider {

    companion object {
        /** Global JSON instance with custom serializers */
        @Volatile
        lateinit var json: Json
            private set

        @Volatile
        var appStartupTimeMs: Long = 0L
            private set

        // 全局应用实例
        @Volatile
        lateinit var instance: ApexAgentApplication
            private set

        // 全局ImageLoader实例，用于高效缓存图片
        @Volatile
        lateinit var globalImageLoader: ImageLoader
            private set

        private const val TAG = "ApexAgentApplication"
        private const val IMAGE_DISK_CACHE_MAX_MB = 50L
        private const val IMAGE_MEMORY_CACHE_MAX_PERCENT = 0.15
    }

    // 应用级协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 懒加载数据库实例
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    // 应用初始化器
    private lateinit var appInitializer: AppInitializer

    private fun configureOpenMpEnvironment() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Os.setenv("KMP_AFFINITY", "disabled", true)
                Os.setenv("OMP_PROC_BIND", "false", true)
            }
        } catch (_: Throwable) {
        }
    }

    // ============================================================
    // [架构优化] 冷启动关键路径缩减
    // 原代码: onCreate 中同步执行30+项初始化，含多处 runBlocking 和文件IO
    // 新代码: 主线程仅保留8项关键UI初始化，其余全移到后台IO协程并行执行
    // 预期收益: 主线程阻塞时间 70%~85% 缩减
    // ============================================================
    override fun onCreate() {
        super.onCreate()
        val startTime = System.currentTimeMillis()
        appStartupTimeMs = startTime
        instance = this
        AppLogger.init(this)
        reportPreviousCrashLog()
        AppState.initialize(this)
        GlobalLifecycleManager.register(this)

        // ============================================================
        // 诊断服务 — 已合并到主 APK（原 :apk:diagnostics 独立模块）
        // 注册到 TypedServiceRegistry 让其他 APK 跨进程调用
        // ============================================================
        val diagnosticsFacade = com.apex.agent.diagnostics.DiagnosticsServiceFacade(this)
        com.apex.sdk.bridge.TypedServiceRegistry.register<com.apex.agent.diagnostics.DiagnosticsServiceFacade>(diagnosticsFacade)
        // 接管全局未捕获异常 → 写入崩溃报告
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                diagnosticsFacade.reportCrash(thread, throwable)
            } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
        // 启动日志采集
        try { diagnosticsFacade.startLogCapture() } catch (_: Throwable) {}

        // 注册主 APK 的 BridgeImpl（让其他 APK 可通过 Bridge 调用 diagnostics/* 等方法）
        com.apex.sdk.bridge.BridgeConnection.registerService(
            "main",
            com.apex.sdk.bridge.ApkBridgeStubAdapter(MainApkBridgeImpl())
        )

        // [优化1] 健康检查：启动关键路径测量
        val health = ArchitectureHealthCheck.getInstance(this)
        health.beginColdStart()

        // ============================================================
        // [Critical Path] 仅保留必须在主线程同步完成的初始化
        // 所有IO密集/非关键组件 => 移到后台IO协程，避免阻塞冷启动
        // ============================================================

        // Initialize theme manager (critical for UI)
        ThemeManager.init(this)
        AppLogger.d(TAG, "【启动计时】主题管理器初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        enableStrictMode()
        createNotificationChannels()

        configureOpenMpEnvironment()
        AppIconManager.ensureComponentState(this)

        // Reset previous log on each cold start to prevent infinite log growth
        val isCrashReportRecoveryStartup = CrashRecoveryState.consumePendingCrashReportLaunch(this)
        if (!isCrashReportRecoveryStartup) {
            AppLogger.resetLogFile()
        }

        ensureWorkManagerInitialized()

        if (isCrashReportRecoveryStartup) {
            AppLogger.w(TAG, "检测到崩溃报告启动，保留上一轮日志供崩溃页面导出")
        }

        AppLogger.d(TAG, "【启动计时】应用启动开始)")
        AppLogger.d(TAG, "【启动计时】实例初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Set global exception handler before any other initializations
        setupUncaughtExceptionHandler()
        AppLogger.d(TAG, "【启动计时】全局异常处理器设置完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize the JSON serializer (needed for many operations)
        json = Json {
            serializersModule = SerializationSetup.module
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
            encodeDefaults = true
        }
        AppLogger.d(TAG, "【启动计时】JSON序列化器初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize and apply language settings (must be before getting string resources)
        initializeAppLanguage()
        AppLogger.d(TAG, "【启动计时】语言设置初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize ObjectBox database (lightweight, runs on main thread)
        initObjectBox()
        AppLogger.d(TAG, "【启动计时】ObjectBox 初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // ============================================================
        // [非关键路径] 所有剩余初始化移到后台协程，多子任务并行执行
        // 不阻塞主线程/第一次界面绘制
        // ============================================================
        applicationScope.launch(Dispatchers.IO) {
            val bgStart = System.currentTimeMillis()

            // 子阶段A：偏好设置/权限/Shell（IO密集，可并行）
            val prefsDeferred = async(start = CoroutineStart.DEFAULT) {
                val defaultProfileName = applicationContext.getString(R.string.default_profile)
                initUserPreferencesManager(applicationContext, defaultProfileName)
                initAndroidPermissionPreferences(applicationContext)
                PermissionModeIntegration.initialize(applicationContext)
                AndroidShellExecutor.setContext(applicationContext)
                AppLogger.d(TAG, "【后台初始化】偏好/权限+Shell - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段B：UI生命周期管理（轻量，独立并行）
            val uiLifeDeferred = async(start = CoroutineStart.DEFAULT) {
                ActivityLifecycleManager.initialize(this@ApexAgentApplication)
                AppLogger.d(TAG, "【后台初始化】ActivityLifecycleManager - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段C：聊天核心（依赖prefsDeferred的完成）
            val chatDeferred = async(start = CoroutineStart.DEFAULT) {
                prefsDeferred.await()
                AIMessageManager.initialize(this@ApexAgentApplication)
                PluginRegistry.initializeBuiltins()
                AppLifecycleHookPluginRegistry.dispatchAsync(
                    event = AppLifecycleEvent.APPLICATION_CREATE,
                    params = AppLifecycleHookParams(
                        context = applicationContext,
                        extras = mapOf("startupTimeMs" to startTime)
                    )
                )
                AppLogger.d(TAG, "【后台初始化】AIMessageManager - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段D：Shower Environment（较重的配置）
            val showerDeferred = async(start = CoroutineStart.DEFAULT) {
                configureShowerEnvironment()
                LanguageFactory.init()
                AppLogger.d(TAG, "【后台初始化】ShowerEnvironment+LanguageFactory - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段E：Waifu消息处理器
            val waifuDeferred = async(start = CoroutineStart.DEFAULT) {
                WaifuMessageProcessor.initialize(applicationContext)
                AppLogger.d(TAG, "【后台初始化】WaifuMessageProcessor - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段F：图片加载器（OkHttp + Coil，IO较重）
            val imageDeferred = async(Dispatchers.IO) {
                initGlobalImageLoader()
                AppLogger.d(TAG, "【后台初始化】全局图片加载器 - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段G：池管理器（文件IO）
            val poolDeferred = async(Dispatchers.IO) {
                ImagePoolManager.initialize(filesDir, preloadNow = false)
                MediaPoolManager.initialize(filesDir, preloadNow = false)
                SkillRepoZipPoolManager.initialize(filesDir)
                AppLogger.d(TAG, "【后台初始化】池管理器 - ${System.currentTimeMillis() - bgStart}ms")
            }

            // 子阶段H：会话生命周期钩子注册
            val hooksDeferred = async(Dispatchers.IO) {
                try {
                    HookRegistry.register(SessionStartHook())
                    HookRegistry.register(PreCompactHook())
                    HookRegistry.register(SessionEndHook())
                    AppLogger.d(TAG, "【后台初始化】会话生命周期钩子已注册 - ${System.currentTimeMillis() - bgStart}ms")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "【后台初始化】会话生命周期钩子注册失败", e)
                }
            }

            // 先确保UI生命周期就绪（保证Activity进入后能track）
            uiLifeDeferred.await()

            // 聊天核心就绪后启动分阶段初始化器
            chatDeferred.await()
            appInitializer = AppInitializer(applicationContext)
            appInitializer.startInitialization()

            // 其他后台子任务并发等待（不阻塞界面）
            showerDeferred.await()
            waifuDeferred.await()
            imageDeferred.await()
            poolDeferred.await()
            hooksDeferred.await()

            // === 最低优先级：第一次界面绘制完成后再执行 ===
            launchCleanOnExitCleanup()
            startGlobalAIForegroundServiceIfNeeded()
            com.apex.agent.data.supabase.SupabaseSyncManager.initialize(applicationContext)

            // 初始化 BurstKernel —— 通过 Hilt EntryPoint 取 adapter，激活 SWARM 协作
            initializeBurstKernel()

            val totalBg = System.currentTimeMillis() - bgStart
            AppLogger.d(TAG, "【后台初始化】全部完成 - 总耗时: ${totalBg}ms")

            // [优化1] 健康检查：后台初始化完成
            health.endBackgroundInit()

            // 延迟5秒后输出一次完整架构健康度报告 (仅调试开发环境）
            delay(5000)
            health.reportHealth()
        }

        val totalTime = System.currentTimeMillis() - startTime
        health.endCriticalPath()
        AppLogger.d(TAG, "【启动计时】关键路径(Critical Path)完成 - 主线程阻塞 ${totalTime}ms")
    }

    /**
     * 在调试模式下启用 StrictMode，用于检测主线程上的磁盘 I/O 和网络操作。
     */
    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
            )
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
            )
        }
    }

    /**
     * 创建所有通知渠道，确保在 Android 8.0+ 上通知正常显示。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // AI 前台服务通知渠道
            val aiServiceChannel = NotificationChannel(
                "AI_SERVICE_CHANNEL",
                getString(R.string.service_Apex_running),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_keep_background)
            }
            manager.createNotificationChannel(aiServiceChannel)

            // AI 回复完成通知渠道（无声无振动）
            val replySilentChannel = NotificationChannel(
                "AI_REPLY_COMPLETE_CHANNEL_silent",
                getString(R.string.service_chat_complete_reminder),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.service_notify_when_complete)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(replySilentChannel)
        }
    }

    /**
     * 初始化 AppLogger，读取并归档上次崩溃的日志。
     */
    private fun reportPreviousCrashLog() {
        try {
            val crashFile = java.io.File(filesDir, "crash_log.txt")
            if (crashFile.exists() && crashFile.length() > 0) {
                AppLogger.w(TAG, "存在未处理的崩溃日志，大小: ${crashFile.length()} bytes")
                crashFile.renameTo(java.io.File(filesDir, "crash_log_${System.currentTimeMillis()}.txt"))
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 设置全局未捕获异常处理器，将异常信息记录到文件并委托给 GlobalExceptionHandler。
     */
    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = GlobalExceptionHandler(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e(TAG, "未捕获异常，线程: ${thread.name}", throwable)
            try {
                val crashFile = java.io.File(filesDir, "crash_log.txt")
                crashFile.appendText(
                    "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
                    "Thread: ${thread.name}\n" +
                    "Exception: ${throwable}\n" +
                    android.util.Log.getStackTraceString(throwable) + "\n\n"
                )
            } catch (e: Exception) {
                // 忽略
            }
            defaultHandler.uncaughtException(thread, throwable)
        }
    }

    /** 初始化 ObjectBox 数据库 */
    private fun initObjectBox() {
        ObjectBoxManager.get(this, "default")
        AppLogger.d(TAG, "ObjectBox 默认数据库已预热")
    }

    private fun configureShowerEnvironment() {
        ShowerEnvironment.shellRunner = ApexShowerShellRunner
        ShowerEnvironment.logSink = ShowerLogSink { priority, tag, message, throwable ->
            when (priority) {
                AppLogger.VERBOSE ->
                    if (throwable != null) AppLogger.v(tag, message, throwable) else AppLogger.v(tag, message)
                AppLogger.DEBUG ->
                    if (throwable != null) AppLogger.d(tag, message, throwable) else AppLogger.d(tag, message)
                AppLogger.INFO ->
                    if (throwable != null) AppLogger.i(tag, message, throwable) else AppLogger.i(tag, message)
                AppLogger.WARN ->
                    if (throwable != null) AppLogger.w(tag, message, throwable) else AppLogger.w(tag, message)
                AppLogger.ERROR ->
                    if (throwable != null) AppLogger.e(tag, message, throwable) else AppLogger.e(tag, message)
                AppLogger.ASSERT ->
                    if (throwable != null) AppLogger.wtf(tag, message, throwable) else AppLogger.wtf(tag, message)
                else ->
                    if (throwable != null) {
                        AppLogger.println(priority, tag, "${message}\n${AppLogger.getStackTraceString(throwable)}")
                    } else {
                        AppLogger.println(priority, tag, message)
                    }
            }
        }
        ShowerEnvironment.emitToSystemLog = false
    }

    private fun initGlobalImageLoader() {
        val imageOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        globalImageLoader = ImageLoader.Builder(this@ApexAgentApplication)
            .okHttpClient(imageOkHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .respectCacheHeaders(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(filesDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_MB * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this@ApexAgentApplication).maxSizePercent(IMAGE_MEMORY_CACHE_MAX_PERCENT).build()
            }
            .build()
    }

    /**
     * 实现 ImageLoaderFactory 接口
     * 让Coil 使用我们配置的全局 ImageLoader（带有自定义超时设置）
     */
    override fun newImageLoader(): ImageLoader {
        return globalImageLoader
    }

    /**
     * 实现 WorkConfiguration.Provider 接口
     * 提供 WorkManager 的配置，确保 WorkManager 被正确初始化
     */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) AppLogger.DEBUG else AppLogger.INFO)
            .build()

    private fun ensureWorkManagerInitialized() {
        try {
            WorkManager.getInstance(applicationContext)
        } catch (_: IllegalStateException) {
            try {
                WorkManager.initialize(applicationContext, workManagerConfiguration)
            } catch (_: IllegalStateException) {

            }
        }
    }

    private fun launchCleanOnExitCleanup() {
        applicationScope.launch {
            val cleanupStartTime = System.currentTimeMillis()
            try {
                val deletedFiles =
                    cleanDirectory(File(ApexPaths.cleanOnExitPathSdcard()), preserveRootNoMedia = true) +
                        cleanDirectory(File(cacheDir, "apex/cleanOnExit"), preserveRootNoMedia = false)
                AppLogger.d(
                    TAG,
                    "cleanOnExit 清理完成，总计删除${deletedFiles}个文件，耗时${System.currentTimeMillis() - cleanupStartTime}ms"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "清理临时文件失败", e)
            }
        }
    }

    private fun cleanDirectory(tempDir: File, preserveRootNoMedia: Boolean): Int {
        if (!tempDir.exists() || !tempDir.isDirectory) {
            return 0
        }
        if (preserveRootNoMedia) {
            val noMediaFile = File(tempDir, ".nomedia")
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile()
            }
        }
        AppLogger.d(TAG, "开始清理临时文件目录 ${tempDir.absolutePath}")
        val totalDeleted =
            deleteRecursively(
                rootDir = tempDir,
                file = tempDir,
                preserveRootNoMedia = preserveRootNoMedia,
                isRoot = true
            )
        AppLogger.d(TAG, "已删除 ${totalDeleted}个临时文件 ${tempDir.absolutePath}")
        return totalDeleted
    }

    private fun deleteRecursively(
        rootDir: File,
        file: File,
        preserveRootNoMedia: Boolean,
        isRoot: Boolean = false
    ): Int {
        var deletedCount = 0
        if (file.isDirectory) {
            val children = file.listFiles()
            children?.forEach { child ->
                deletedCount += deleteRecursively(
                    rootDir = rootDir,
                    file = child,
                    preserveRootNoMedia = preserveRootNoMedia,
                    isRoot = false
                )
            }
            if (!isRoot && file.exists()) {
                file.delete()
            }
        } else if (file.isFile) {
            val isRootNoMedia =
                preserveRootNoMedia &&
                    file.parentFile?.absolutePath == rootDir.absolutePath &&
                    file.name == ".nomedia"
            if (!isRootNoMedia && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun startGlobalAIForegroundServiceIfNeeded() {
        try {
            applicationScope.launch(Dispatchers.IO) {
                val alwaysListeningEnabled = WakeWordPreferences(applicationContext).alwaysListeningEnabledFlow.first()
                val externalHttpEnabled = ExternalHttpApiPreferences.getInstance(applicationContext).enabledFlow.first()
                if ((!alwaysListeningEnabled && !externalHttpEnabled) || AIForegroundService.isRunning.get()) {
                    return@launch
                }
                val intent = Intent(this@ApexAgentApplication, AIForegroundService::class.java).apply {
                    putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_IDLE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动 AIForegroundService 失败: ${e.message}", e)
        }
    }

    /**
     * 初始化 BurstKernel 微内核。
     *
     * 通过 Hilt EntryPoint 取 [IBurstCollaborationFramework] adapter
     * （由 [com.apex.agent.application.di.BurstModule] 提供的 @Singleton），
     * 注入到 [BurstKernel.start] 的第 4 个参数，激活 SWARM 跨 agent 协作。
     *
     * 如果 BurstKernel 已经在运行（例如 BurstKernelService 先于此处启动），
     * 直接返回，避免覆盖已注入的 adapter。
     *
     * 失败处理：adapter 注入失败时不阻断应用启动，仅记录日志；
     * BurstKernel 仍可启动（不传 adapter，SWARM 回退到本地协程池）。
     */
    private fun initializeBurstKernel() {
        try {
            if (BurstKernel.getState() == com.apex.agent.domain.model.KernelState.RUNNING) {
                AppLogger.d(TAG, "BurstKernel 已运行，跳过重复初始化")
                return
            }

            // 通过 Hilt EntryPoint 取 adapter —— 不需要把整个 Application 改成 @AndroidEntryPoint
            val adapter: com.apex.agent.data.burstmode.swarm.IBurstCollaborationFramework? = try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    this,
                    BurstKernelInitializerEntryPoint::class.java
                )
                entryPoint.collaborationFramework
            } catch (e: Exception) {
                AppLogger.w(TAG, "BurstKernel: 通过 Hilt 取 adapter 失败，SWARM 将回退到本地协程池: ${e.message}")
                null
            }

            BurstKernel.start(
                app = this,
                collaborationFramework = adapter
            )
            AppLogger.i(TAG, "BurstKernel 已启动，SWARM 协作 ${if (adapter != null) "已激活" else "回退到本地"}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "BurstKernel 启动失败: ${e.message}", e)
        }
    }

    /**
     * Hilt EntryPoint：让非 Hilt 客户端（如 Application.onCreate）能拿到
     * [com.apex.agent.data.burstmode.swarm.IBurstCollaborationFramework] 单例。
     */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface BurstKernelInitializerEntryPoint {
        val collaborationFramework: com.apex.agent.data.burstmode.swarm.IBurstCollaborationFramework
    }

    /** 初始化应用语言设置 */
    private fun initializeAppLanguage() {
        try {
            // 同步获取已保存的语言设置
            val languageCode = runBlocking {
                try {
                    // 使用更安全的方式检查preferencesManager
                    val manager = runCatching { preferencesManager }.getOrNull()
                    if (manager != null) {
                        manager.appLanguage.first()
                    } else {
                        UserPreferencesManager.DEFAULT_LANGUAGE
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取语言设置失败", e)
                    UserPreferencesManager.DEFAULT_LANGUAGE
                }
            }

            AppLogger.d(TAG, "获取语言设置: ${languageCode}")

            // 立即应用语言设置
            val locale = LocaleUtils.getLocaleForLanguageCode(languageCode, this)
            // 设置默认语言
            Locale.setDefault(locale)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 使用AppCompatDelegate API
                val localeList = LocaleListCompat.create(locale)
                AppCompatDelegate.setApplicationLocales(localeList)
                AppLogger.d(TAG, "使用AppCompatDelegate设置语言: ${languageCode}")
            } else {
                // 较旧版本Android - 此处使用的部分更新将在attachBaseContext中完成更完整更新
                val config = Configuration()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val localeList = LocaleList(locale)
                    LocaleList.setDefault(localeList)
                    config.setLocales(localeList)
                } else {
                    config.locale = locale
                }

                resources.updateConfiguration(config, resources.displayMetrics)
                AppLogger.d(TAG, "使用Configuration设置语言: ${languageCode}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "初始化语言设置失败", e)
        }
    }

    override fun attachBaseContext(base: Context) {
        configureOpenMpEnvironment()
        // 在基础上下文附加前应用语言设置
        try {
            val code = LocaleUtils.getCurrentLanguage(base)
            val locale = LocaleUtils.getLocaleForLanguageCode(code, base)
            val config = Configuration(base.resources.configuration)

            // 设置语言配置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val localeList = LocaleList(locale)
                LocaleList.setDefault(localeList)
                config.setLocales(localeList)
            } else {
                config.locale = locale
                Locale.setDefault(locale)
            }

            // 使用createConfigurationContext创建新的上下文
            val context = base.createConfigurationContext(config)
            super.attachBaseContext(context)
            AppLogger.d(TAG, "成功应用基础上下文语言: ${code}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "应用基础上下文语言失败", e)
            super.attachBaseContext(base)
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        AppLifecycleHookPluginRegistry.dispatchAsync(
            event = AppLifecycleEvent.APPLICATION_TERMINATE,
            params = AppLifecycleHookParams(applicationContext)
        )
        
        try {
            if (AIForegroundService.isRunning.get()) {
                val intent = Intent(applicationContext, AIForegroundService::class.java)
                stopService(intent)
                AppLogger.d(TAG, "应用终止，已停止 AIForegroundService")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "终止时停止 AIForegroundService 失败: ${e.message}", e)
        }

        // 清理终端管理器和SSH连接
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Terminal.getInstance(applicationContext).destroy()
                AppLogger.d(TAG, "应用终止，已清理所有终端会话和SSH连接")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "清理终端管理器失败 ${e.message}", e)
        }
        
        // 在应用终止时关闭LocalWebServer服务
        try {
            val webServer = LocalWebServer.getInstance(applicationContext, LocalWebServer.ServerType.WORKSPACE)
            if (webServer.isRunning()) {
                webServer.stop()
                AppLogger.d(TAG, "应用终止，已关闭本地Web服务器")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "关闭本地Web服务器失败 ${e.message}", e)
        }

        // 在应用终止时，关闭虚拟显示器Overlay并断开Shower WebSocket连接
        try {
            VirtualDisplayOverlay.hideAll()
        } catch (e: Exception) {
            AppLogger.e(TAG, "终止时隐藏 VirtualDisplayOverlay 失败: ${e.message}", e)
        }
        try {
            ShowerController.shutdown()
        } catch (e: Exception) {
            AppLogger.e(TAG, "终止时关闭 ShowerController 失败: ${e.message}", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLifecycleHookPluginRegistry.dispatchAsync(
            event = AppLifecycleEvent.APPLICATION_LOW_MEMORY,
            params = AppLifecycleHookParams(applicationContext)
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLifecycleHookPluginRegistry.dispatchAsync(
            event = AppLifecycleEvent.APPLICATION_TRIM_MEMORY,
            params =
                AppLifecycleHookParams(
                    context = applicationContext,
                    extras = mapOf("level" to level)
                )
        )
    }
}
