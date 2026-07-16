package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.core.tools.system.shell.RootShellExecutor
import com.apex.agent.data.preferences.RootCommandExecutionMode
import com.apex.agent.data.preferences.androidPermissionPreferences
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root 管理器 - 提供统一的 Root 权限管理接口
 */
object RootManager {
    private const val TAG = "RootManager"
    private const val DETECTION_CACHE_DURATION = 30000L // 30秒

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Root 执行器
    private var rootShellExecutor: RootShellExecutor? = null

    // 状态流
    private val _isRooted = MutableStateFlow(false)
    val isRooted: StateFlow<Boolean> = _isRooted.asStateFlow()

    private val _hasRootAccess = MutableStateFlow(false)
    val hasRootAccess: StateFlow<Boolean> = _hasRootAccess.asStateFlow()

    private val _rootScheme = MutableStateFlow<RootScheme>(RootScheme.UNKNOWN)
    val rootScheme: StateFlow<RootScheme> = _rootScheme.asStateFlow()

    private val _seLinuxStatus = MutableStateFlow<SELinuxStatus>(SELinuxStatus.UNKNOWN)
    val seLinuxStatus: StateFlow<SELinuxStatus> = _seLinuxStatus.asStateFlow()

    private val _rootExecutionMode = MutableStateFlow<RootExecutionMode>(RootExecutionMode.AUTO)
    val rootExecutionMode: StateFlow<RootExecutionMode> = _rootExecutionMode.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    // 缓存
    private var cachedDetection: CachedDetection? = null
    private val stateChangeListeners = mutableSetOf<() -> Unit>()

    private data class CachedDetection(
        val isRooted: Boolean,
        val hasRootAccess: Boolean,
        val rootScheme: RootScheme,
        val seLinuxStatus: SELinuxStatus,
        val timestamp: Long
    )

    // 常见的 su 路径
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su"
    )

    /**
     * 初始化 Root 管理器
     */
    fun initialize(context: Context) {
        if (_isInitialized.value) return

        scope.launch {
            try {
                AppLogger.d(TAG, "初始化 Root 管理器...")

                // 初始化 libsu
                initializeLibsu()

                // 初始化执行器
                if (rootShellExecutor == null) {
                    rootShellExecutor = RootShellExecutor(context)
                }
                rootShellExecutor?.initialize()

                // 加载执行模式偏好
                loadExecutionModePreference()

                // 初始化执行器
                checkRootStatus(context, forceRefresh = true)

                _isInitialized.value = true
                AppLogger.d(TAG, "Root 管理器初始化完成")
            } catch (e: Exception) {
                AppLogger.e(TAG, "初始化 Root 管理器失败", e)
            }
        }
    }

    private fun initializeLibsu() {
        try {
            Shell.enableVerboseLogging = true
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
            )
            AppLogger.d(TAG, "libsu 配置完成")
        } catch (e: Exception) {
            AppLogger.e(TAG, "libsu 检查失败", e)
        }
    }

    private fun loadExecutionModePreference() {
        try {
            val mode = androidPermissionPreferences.getRootExecutionMode()
            _rootExecutionMode.value = RootExecutionMode.fromLegacyMode(mode)
            AppLogger.d(TAG, "已加载 Root 执行模式: ${_rootExecutionMode.value}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载 Root 执行模式失败", e)
        }
    }

    /**
     * 初始化 Root 管理器
     */
    suspend fun checkRootStatus(
        context: Context,
        forceRefresh: Boolean = false
    ): RootDetectionResult = mutex.withLock {
        val now = System.currentTimeMillis()

        // 检查缓存
        if (!forceRefresh) {
            cachedDetection?.let { cached ->
                if (now - cached.timestamp < DETECTION_CACHE_DURATION) {
                    AppLogger.v(TAG, "使用缓存的 Root 检测结果")
                    return RootDetectionResult(
                        isRooted = cached.isRooted,
                        hasRootAccess = cached.hasRootAccess,
                        rootScheme = cached.rootScheme,
                        seLinuxStatus = cached.seLinuxStatus,
                        detectionTimestamp = cached.timestamp
                    )
                }
            }
        }

        _isChecking.value = true
        AppLogger.d(TAG, "开始检测 Root 状态...")

        val result = try {
            val mode = _rootExecutionMode.value
            applyExecutionMode(mode)

            val isRooted = detectIsRooted(context)
            val hasRootAccess = checkRootAccess(context)
            val rootScheme = detectRootScheme(context)
            val seLinuxStatus = detectSELinuxStatus()

            // 更新状态
            updateState(isRooted, hasRootAccess, rootScheme, seLinuxStatus)

            // 缓存结果
            cachedDetection = CachedDetection(
                isRooted = isRooted,
                hasRootAccess = hasRootAccess,
                rootScheme = rootScheme,
                seLinuxStatus = seLinuxStatus,
                timestamp = now
            )

            RootDetectionResult(
                isRooted = isRooted,
                hasRootAccess = hasRootAccess,
                rootScheme = rootScheme,
                seLinuxStatus = seLinuxStatus,
                detectionTimestamp = now
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载 Root 执行模式失败", e)
            RootDetectionResult(
                isRooted = false,
                hasRootAccess = false,
                detectionTimestamp = now,
                errorMessage = e.message
            )
        } finally {
            _isChecking.value = false
        }

        return result
    }

    private fun applyExecutionMode(mode: RootExecutionMode) {
        when (mode) {
            RootExecutionMode.AUTO -> {
                rootShellExecutor?.setUseExecMode(false)
            }
            RootExecutionMode.FORCE_LIBSU, RootExecutionMode.FORCE_MAGISK -> {
                rootShellExecutor?.setUseExecMode(false)
            }
            RootExecutionMode.FORCE_EXEC, RootExecutionMode.FORCE_KERNELSU -> {
                rootShellExecutor?.setUseExecMode(true)
            }
        }
        AppLogger.d(TAG, "已应用 Root 执行模式: $mode")
    }

    private fun detectIsRooted(context: Context): Boolean {
        AppLogger.d(TAG, "检测设备是否已 Root...")

        // 方法 1: 检查 su 文件
        val suExists = checkSuFiles()
        if (suExists) {
            AppLogger.d(TAG, "检测到 su 文件")
            return true
        }

        // 方法 2: 使用 libsu 检测
        try {
            val libsuRooted = Shell.isAppGrantedRoot() ?: false
            if (libsuRooted) {
                AppLogger.d(TAG, "libsu 检测到 Root")
                return true
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "libsu 检测失败", e)
        }

        // 方法 3: 检查 known Root 应用
        val rootAppExists = checkRootApplications(context)
        if (rootAppExists) {
            AppLogger.d(TAG, "检测到 Root 管理应用")
            return true
        }

        // 方法 4: 尝试执行 which su
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                AppLogger.d(TAG, "which su 成功")
                return true
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "which su 检测失败", e)
        }

        return false
    }

    private fun checkSuFiles(): Boolean {
        return SU_PATHS.any { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkRootApplications(context: Context): Boolean {
        val rootPackages = listOf(
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "eu.chainfire.supersu",
            "com.kingroot.kinguser"
        )

        val pm = context.packageManager
        return rootPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkRootAccess(context: Context): Boolean {
        AppLogger.d(TAG, "开始检测 Root 状态...")

        val mode = _rootExecutionMode.value

        return when (mode) {
            RootExecutionMode.AUTO -> {
                // 先尝试 libsu，失败则尝试 exec
                checkLibsuAccess() || checkExecSuAccess()
            }
            RootExecutionMode.FORCE_LIBSU, RootExecutionMode.FORCE_MAGISK -> {
                checkLibsuAccess()
            }
            RootExecutionMode.FORCE_EXEC, RootExecutionMode.FORCE_KERNELSU -> {
                checkExecSuAccess()
            }
        }
    }

    private fun checkLibsuAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            val hasAccess = shell.isRoot
            AppLogger.d(TAG, "libsu 检查结果: $hasAccess")
            hasAccess
        } catch (e: Exception) {
            AppLogger.e(TAG, "libsu 检查失败", e)
            false
        }
    }

    private fun checkExecSuAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(buildSuExecCommand("echo test"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line)
            }

            val exitCode = process.waitFor()
            val hasAccess = exitCode == 0 && output.toString().contains("test")

            AppLogger.d(TAG, "exec su 检查结果: $hasAccess (exitCode: $exitCode)")
            hasAccess
        } catch (e: Exception) {
            AppLogger.e(TAG, "exec su 检查失败", e)
            false
        }
    }

    private fun detectRootScheme(context: Context): RootScheme {
        val pm = context.packageManager

        for (scheme in RootScheme.values()) {
            if (scheme.packageName != null) {
                try {
                    pm.getPackageInfo(scheme.packageName, 0)
                    AppLogger.d(TAG, "检测到 Root 方案: ${scheme.displayName}")
                    return scheme
                } catch (e: Exception) {
                    continue
                }
            }
        }

        // 尝试通过 su --version 检测
        try {
            val process = Runtime.getRuntime().exec(buildSuVersionCommand())
            val output = process.inputStream.bufferedReader().readText().lowercase()
            process.waitFor()

            when {
                output.contains("kernelsu") -> return RootScheme.KERNELSU
                output.contains("apatch") -> return RootScheme.APATCH
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "通过 su 版本检测 Root 方案失败", e)
        }

        return RootScheme.UNKNOWN
    }

    private fun detectSELinuxStatus(): SELinuxStatus {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val output = process.inputStream.bufferedReader().readText().trim().lowercase()
            process.waitFor()

            val status = SELinuxStatus.fromString(output)
            AppLogger.d(TAG, "SELinux 状态: ${status.displayName}")
            status
        } catch (e: Exception) {
            AppLogger.w(TAG, "检测 SELinux 状态失败", e)
            SELinuxStatus.UNKNOWN
        }
    }

    private fun updateState(
        isRooted: Boolean,
        hasRootAccess: Boolean,
        rootScheme: RootScheme,
        seLinuxStatus: SELinuxStatus
    ) {
        _isRooted.value = isRooted
        _hasRootAccess.value = hasRootAccess
        _rootScheme.value = rootScheme
        _seLinuxStatus.value = seLinuxStatus

        notifyStateChange()
    }

    /**
     * 请求 Root 权限
     */
    fun requestRootPermission(onResult: (Boolean) -> Unit) {
        scope.launch {
            AppLogger.d(TAG, "请求 Root 权限...")

            try {
                val mode = _rootExecutionMode.value

                val granted = when (mode) {
                    RootExecutionMode.AUTO, RootExecutionMode.FORCE_LIBSU, RootExecutionMode.FORCE_MAGISK -> {
                        requestRootViaLibsu()
                    }
                    RootExecutionMode.FORCE_EXEC, RootExecutionMode.FORCE_KERNELSU -> {
                        requestRootViaExec()
                    }
                }

                if (granted) {
                    _hasRootAccess.value = true
                    notifyStateChange()
                }

                AppLogger.d(TAG, "Root 权限请求结果: ${granted}")
                onResult(granted)
            } catch (e: Exception) {
                AppLogger.e(TAG, "请求 Root 权限失败", e)
                onResult(false)
            }
        }
    }

    private suspend fun requestRootViaLibsu(): Boolean {
        return try {
            Shell.getShell { shell ->
                val granted = shell.isRoot
                if (granted) {
                    _hasRootAccess.value = true
                }
            }
            _hasRootAccess.value
        } catch (e: Exception) {
            AppLogger.e(TAG, "通过 libsu 请求 Root 失败", e)
            false
        }
    }

    private fun requestRootViaExec(): Boolean {
        return checkExecSuAccess()
    }

    /**
     * 设置 Root 执行模式
     */
    suspend fun setExecutionMode(mode: RootExecutionMode) {
        _rootExecutionMode.value = mode

        // 检查缓存
        try {
            androidPermissionPreferences.saveRootExecutionMode(mode.toLegacyMode())
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存 Root 执行模式失败", e)
        }

        applyExecutionMode(mode)

        // 检查缓存
        if (_isInitialized.value) {
            // å·²åå§åæ¶ï¼éæ°æ£æµ Root ç¶æä»¥åºç¨æ°æ¨¡å¼
            try {
                checkRootAccess()
            } catch (e: Exception) {
                AppLogger.w(TAG, "åæ¢æ§è¡æ¨¡å¼åéæ°æ£æµ Root å¤±è´¥: ${e.message}")
            }
        }
    }

    /**
     * 设置自定义 su 命令
     */
    suspend fun setCustomSuCommand(command: String) {
        try {
            androidPermissionPreferences.saveCustomSuCommand(command)
            rootShellExecutor?.setExecSuCommand(command)
            AppLogger.d(TAG, "已设置自定义 su 命令: ${command}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置自定义 su 命令失败", e)
        }
    }

    /**
     * 执行 Root 命令
     */
    suspend fun executeRootCommand(
        command: String,
        context: Context,
        identity: ShellIdentity = ShellIdentity.ROOT
    ): Pair<Boolean, String> {
        try {
            val executor = rootShellExecutor ?: run {
                RootShellExecutor(context).also {
                    rootShellExecutor = it
                    it.initialize()
                }
            }

            val result = executor.executeCommand(command, identity)
            return Pair(result.success, if (result.success) result.stdout else result.stderr)
        } catch (e: Exception) {
            AppLogger.e(TAG, "执行 Root 命令失败", e)
            return Pair(false, "执行失败: ${e.message}")
        }
    }

    /**
     * 添加状态变更监听器
     */
    fun addStateChangeListener(listener: () -> Unit) {
        stateChangeListeners.add(listener)
    }

    /**
     * 移除状态变更监听器
     */
    fun removeStateChangeListener(listener: () -> Unit) {
        stateChangeListeners.remove(listener)
    }

    private fun notifyStateChange() {
        stateChangeListeners.forEach { it() }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedDetection = null
        AppLogger.d(TAG, "已清除 Root 检测缓存")
    }

    // 辅助方法
    private fun normalizeSuCommand(command: String): String {
        val normalized = command?.trim().orEmpty()
        return normalized.ifEmpty { "su" }
    }

    private fun parseSuCommandTokens(command: String): List<String> {
        return normalizeSuCommand(command).split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun getConfiguredSuCommand(): String {
        return try {
            normalizeSuCommand(androidPermissionPreferences.getCustomSuCommand())
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取自定义 su 命令失败，回退默认 su", e)
            "su"
        }
    }

    private fun buildSuExecCommand(command: String): Array<String> {
        val tokens = parseSuCommandTokens(getConfiguredSuCommand())
        return (tokens + listOf("-c", command)).toTypedArray()
    }

    private fun buildSuVersionCommand(): Array<String> {
        val tokens = parseSuCommandTokens(getConfiguredSuCommand())
        return (tokens + "--version").toTypedArray()
    }
}
