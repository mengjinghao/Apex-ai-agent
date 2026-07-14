
package com.apex.agent.core.application

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.system.Os
import com.apex.util.AppLogger
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
import com.apex.agent.data.preferences.ExternalHttpApiPreferences
import com.apex.agent.data.preferences.UserPreferencesManager
// import com.apex.agent.data.preferences.WakeWordPreferences
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
import com.apex.agent.util.LogistraPaths
import com.apex.agent.util.SkillRepoZipPoolManager
import com.apex.agent.util.SerializationSetup
import com.apex.agent.util.TextSegmenter
import com.apex.agent.util.ThemeManager
import com.apex.agent.util.WaifuMessageProcessor
import com.apex.agent.core.tools.agent.ShowerController
import com.apex.agent.ui.common.displays.VirtualDisplayOverlay
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.apex.agent.core.tools.system.shower.LogistraShowerShellRunner
import com.ai.assistance.showerclient.ShowerEnvironment
import com.ai.assistance.showerclient.ShowerLogSink
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.apex.core.application.ActivityLifecycleManager
import com.apex.core.tools.javascript.not

/** Application class for apex */
class LogistraAgentApplication : Application(), ImageLoaderFactory, WorkConfiguration.Provider {

    companion object {
        /** Global JSON instance with custom serializers */
        lateinit var json: Json
            private set

        @Volatile
        var appStartupTimeMs: Long = 0L
            private set

        // 全局应用实例
        lateinit var instance: LogistraAgentApplication
            private set

        // 全局ImageLoader实例，用于高效缓存图�?
        lateinit var globalImageLoader: ImageLoader
            private set

        private const val TAG = "LogistraAgentApplication"
    }

    // 应用级协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 懒加载数据库实例
    private val database by lazy { AppDatabase.getDatabase(this) }
    
    // 应用初始化器
    private lateinit var appInitializer: AppInitializer

    private fun configureOpenMpEnvironment() {
        try {
            if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.LOLLIPOP) {
                Os.setenv("KMP_AFFINITY", "disabled", true)
                Os.setenv("OMP_PROC_BIND", "false", true)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        val startTime = System.currentTimeMillis()
        appStartupTimeMs = startTime
        instance = this

        // Initialize theme manager (critical for UI)
        ThemeManager.init(this)
        AppLogger.d(TAG, "【启动计时】主题管理器初始化完�?- ${System.currentTimeMillis() - startTime}ms")

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

        AppLogger.d(TAG, "【启动计时】应用启动开�?)
        AppLogger.d(TAG, "【启动计时】实例初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize ActivityLifecycleManager (needed for UI tracking)
        ActivityLifecycleManager.initialize(this)
        AppLogger.d(TAG, "【启动计时】ActivityLifecycleManager初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize AIMessageManager (core chat functionality)
        AIMessageManager.initialize(this)
        PluginRegistry.initializeBuiltins()
        AppLifecycleHookPluginRegistry.dispatchAsync(
            event = AppLifecycleEvent.APPLICATION_CREATE,
            params =
                AppLifecycleHookParams(
                    context = applicationContext,
                    extras =
                        mapOf(
                            "startupTimeMs" to startTime
                        )
                )
        )
        AppLogger.d(TAG, "【启动计时】AIMessageManager初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Set global exception handler before any other initializations
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(this))
        AppLogger.d(TAG, "【启动计时】全局异常处理器设置完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize the JSON serializer (needed for many operations)
        json = Json {
            serializersModule = SerializationSetup.module
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
            encodeDefaults = true
        }
        AppLogger.d(TAG, "【启动计时】JSON序列化器初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize and apply language settings (must be before getting string resources)
        initializeAppLanguage()
        AppLogger.d(TAG, "【启动计时】语言设置初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize user preferences manager
    val defaultProfileName = applicationContext.getString(R.string.default_profile)
        initUserPreferencesManager(applicationContext, defaultProfileName)
        AppLogger.d(TAG, "【启动计时】用户偏好管理器初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize Android permission preferences manager
        initAndroidPermissionPreferences(applicationContext)
        AppLogger.d(TAG, "【启动计时】Android权限偏好管理器初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize Permission Mode Integration (new Root/Shizuku management system)
        PermissionModeIntegration.initialize(applicationContext)
        AppLogger.d(TAG, "【启动计时】权限模式集成系统初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize AndroidShellExecutor context
        AndroidShellExecutor.setContext(applicationContext)
        AppLogger.d(TAG, "【启动计时】AndroidShellExecutor初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize Shower virtual screen client and ShellRunner environment
        ShowerEnvironment.shellRunner = LogistraShowerShellRunner
        ShowerEnvironment.logSink =
            ShowerLogSink { priority, tag, message, throwable -&gt;
                when (priority) {
                    AppLogger.VERBOSE -&gt;
                        if (throwable != null) AppLogger.v(tag, message, throwable) else AppLogger.v(tag, message)
                    AppLogger.DEBUG -&gt;
                        if (throwable != null) AppLogger.d(tag, message, throwable) else AppLogger.d(tag, message)
                    AppLogger.INFO -&gt;
                        if (throwable != null) AppLogger.i(tag, message, throwable) else AppLogger.i(tag, message)
                    AppLogger.WARN -&gt;
                        if (throwable != null) AppLogger.w(tag, message, throwable) else AppLogger.w(tag, message)
                    AppLogger.ERROR -&gt;
                        if (throwable != null) AppLogger.e(tag, message, throwable) else AppLogger.e(tag, message)
                    AppLogger.ASSERT -&gt;
                        if (throwable != null) AppLogger.wtf(tag, message, throwable) else AppLogger.wtf(tag, message)
                    else -&gt;
                        if (throwable != null) {
                            AppLogger.println(priority, tag, "${message}\n${AppLogger.getStackTraceString(throwable)}")
                        } else {
                            AppLogger.println(priority, tag, message)
                        }
                }
            }
        ShowerEnvironment.emitToSystemLog = false
        AppLogger.d(TAG, "【启动计时】ShowerEnvironment配置完成 - ${System.currentTimeMillis() - startTime}ms")

        // Initialize language support
        LanguageFactory.init()
        AppLogger.d(TAG, "【启动计时】语言工厂初始化完�?- ${System.currentTimeMillis() - startTime}ms")
        
        // Initialize WaifuMessageProcessor
        WaifuMessageProcessor.initialize(applicationContext)
        AppLogger.d(TAG, "【启动计时】WaifuMessageProcessor初始化完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize global image loader (needed for UI)
    val imageOkHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        
        globalImageLoader =
                ImageLoader.Builder(this)
                        .okHttpClient(imageOkHttpClient)
                        .components {
                            if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.P) {
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
                                    .maxSizeBytes(50 * 1024 * 1024)
                                    .build()
                        }
                        .memoryCache {
                            coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build()
                        }
                        .build()
        AppLogger.d(TAG, "【启动计时】全局图片加载器初始化完成 - ${System.currentTimeMillis() - startTime}ms")
        
        // Initialize pool managers (without preloading)
        ImagePoolManager.initialize(filesDir, preloadNow = false)
        MediaPoolManager.initialize(filesDir, preloadNow = false)
        SkillRepoZipPoolManager.initialize(filesDir)
        AppLogger.d(TAG, "【启动计时】池管理器初始化完成 - ${System.currentTimeMillis() - startTime}ms")

        // Cleanup tasks can be deferred
        launchCleanOnExitCleanup()
        
        // Start foreground service if needed (can be slightly deferred but important)
        startGlobalAIForegroundServiceIfNeeded()
        AppLogger.d(TAG, "【启动计时】AIForegroundService检查完�?- ${System.currentTimeMillis() - startTime}ms")

        // Initialize AppInitializer and start phased initialization
        appInitializer = AppInitializer(applicationContext)
        appInitializer.startInitialization()
        
        val totalTime = System.currentTimeMillis() - startTime
        AppLogger.d(TAG, "【启动计时】应用启动关键路径完�?- 总耗时: ${totalTime}ms")
    }

    /**
     * 实现 ImageLoaderFactory 接口
     * �?Coil 使用我们配置的全局 ImageLoader（带有自定义超时设置�?
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
                    cleanDirectory(File(LogistraPaths.cleanOnExitPathSdcard()), preserveRootNoMedia = true) +
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
        AppLogger.d(TAG, "开始清理临时文件目�? ${tempDir.absolutePath}")
        val totalDeleted =
            deleteRecursively(
                rootDir = tempDir,
                file = tempDir,
                preserveRootNoMedia = preserveRootNoMedia,
                isRoot = true
            )
        AppLogger.d(TAG, "${totalDeleted}个临时文�? ${tempDir.absolutePath}")
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
            children?.forEach { child -&gt;
                deletedCount += deleteRecursively(
                    rootDir = rootDir,
                    file = child,
                    preserveRootNoMedia = preserveRootNoMedia,
                    isRoot = false
                )
            }
            if (!isRoot &amp;&amp; file.exists()) {
                file.delete()
            }
        } else if (file.isFile) {
            val isRootNoMedia =
                preserveRootNoMedia &amp;&amp;
                    file.parentFile?.absolutePath == rootDir.absolutePath &amp;&amp;
                    file.name == ".nomedia"
            if (!isRootNoMedia &amp;&amp; file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun startGlobalAIForegroundServiceIfNeeded() {
        try {
            // val alwaysListeningEnabled = runBlocking {
            //     WakeWordPreferences(applicationContext).alwaysListeningEnabledFlow.first()
            // }
    val externalHttpEnabled = runBlocking {
                ExternalHttpApiPreferences.getInstance(applicationContext).enabledFlow.first()
            }
            if ((/* !alwaysListeningEnabled &amp;&amp; */ !externalHttpEnabled) || AIForegroundService.isRunning.get()) {
                return
            }
            val intent = Intent(this, AIForegroundService::class.java).apply {
                putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_IDLE)
            }
            if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "按持久后台职责状态启�?AIForegroundService 失败: ${e.message}", e)
        }
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

            if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 使用AppCompatDelegate API
    val localeList = LocaleListCompat.create(locale)
                AppCompatDelegate.setApplicationLocales(localeList)
                AppLogger.d(TAG, "使用AppCompatDelegate设置语言: ${languageCode}")
            } else {
                // 较旧版本Android - 此处使用的部分更新将在attachBaseContext中完成更完整更新
    val config = Configuration()
                if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.N) {
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
    if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.N) {
                val localeList = LocaleList(locale)
                LocaleList.setDefault(localeList)
                config.setLocales(localeList)
            } else {
                config.locale = locale
                Locale.setDefault(locale)
            }

            // 使用createConfigurationContext创建新的上下�?
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
            AppLogger.e(TAG, "终止时停�?AIForegroundService 失败: ${e.message}", e)
        }

        // 清理终端管理器和SSH连接
    try {
            if (Build.VERSION.SDK_INT &gt;= Build.VERSION_CODES.O) {
                Terminal.getInstance(applicationContext).destroy()
                AppLogger.d(TAG, "应用终止，已清理所有终端会话和SSH连接")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "清理终端管理器失�? ${e.message}", e)
        }
        
        // 在应用终止时关闭LocalWebServer服务�?
    try {
            val webServer = LocalWebServer.getInstance(applicationContext, LocalWebServer.ServerType.WORKSPACE)
            if (webServer.isRunning()) {
                webServer.stop()
                AppLogger.d(TAG, "应用终止，已关闭本地Web服务�?)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "关闭本地Web服务器失�? ${e.message}", e)
        }

        // 在应用终止时，关闭虚拟显示器Overlay并断开Shower WebSocket连接
    try {
            VirtualDisplayOverlay.hideAll()
        } catch (e: Exception) {
            AppLogger.e(TAG, "终止时隐�?VirtualDisplayOverlay 失败: ${e.message}", e)
        }
        try {
            ShowerController.shutdown()
        } catch (e: Exception) {
            AppLogger.e(TAG, "终止时关�?ShowerController 失败: ${e.message}", e)
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
                    extras =
                        mapOf(
                            "level" to level
                        )
                )
        )
    }
}

