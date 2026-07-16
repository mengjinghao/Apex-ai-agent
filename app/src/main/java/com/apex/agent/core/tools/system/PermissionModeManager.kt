package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.data.preferences.androidPermissionPreferences
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 权限模式管理�?- 统一管理所有权限模式的检测、状态、切�?
 */
class PermissionModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PermissionModeManager"
        private const val DETECTION_CACHE_DURATION = 30000L // 30�?
        private const val AUTO_CHECK_INTERVAL = 60000L // 1分钟

        @Volatile
        private var instance: PermissionModeManager? = null

        fun getInstance(context: Context): PermissionModeManager =
            instance ?: synchronized(this) {
                instance ?: PermissionModeManager(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // 状态管�?
    private val _modeStates = MutableStateFlow<Map<PermissionMode, PermissionModeState>>(emptyMap())
    val modeStates: StateFlow<Map<PermissionMode, PermissionModeState>> = _modeStates.asStateFlow()

    private val _currentMode = MutableStateFlow<PermissionMode?>(null)
    val currentMode: StateFlow<PermissionMode?> = _currentMode.asStateFlow()

    private val _rootResult = MutableStateFlow<RootDetectionResult>(RootDetectionResult())
    val rootResult: StateFlow<RootDetectionResult> = _rootResult.asStateFlow()

    private val _shizukuResult = MutableStateFlow<ShizukuDetectionResult>(ShizukuDetectionResult())
    val shizukuResult: StateFlow<ShizukuDetectionResult> = _shizukuResult.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    // 缓存
    private val detectionCache = ConcurrentHashMap<PermissionMode, CachedResult>()

    // 状态监听器
    private val stateChangeListeners = mutableSetOf<(PermissionModeState) -> Unit>()
    private val modeChangeListeners = mutableSetOf<(PermissionMode) -> Unit>()

    private data class CachedResult(
        val state: PermissionModeState,
        val timestamp: Long
    )

    private fun initialize() {
        scope.launch {
            AppLogger.d(TAG, "初始化权限模式管理器...")

            try {
                // 初始化授权器
                RootAuthorizer.initialize(context)
                ShizukuAuthorizer.initialize()

                // 加载用户偏好
                loadPreferredMode()

                // 初始检�?
                checkAllModes(forceRefresh = true)

                _isInitialized.value = true
                AppLogger.d(TAG, "权限模式管理器初始化完成")

                // 启动自动检�?
                startAutoCheck()
            } catch (e: Exception) {
                AppLogger.e(TAG, "初始化权限模式管理器失败", e)
            }
        }
    }

    private fun loadPreferredMode() {
        try {
            val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
            _currentMode.value = preferredLevel?.let { mapPermissionLevelToMode(it) }
            AppLogger.d(TAG, "已加载用户偏好权限模�? ${_currentMode.value}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载用户偏好权限模式失败", e)
        }
    }

    private fun mapPermissionLevelToMode(level: AndroidPermissionLevel): PermissionMode =
        when (level) {
            AndroidPermissionLevel.STANDARD -> PermissionMode.STANDARD
            AndroidPermissionLevel.ACCESSIBILITY -> PermissionMode.ACCESSIBILITY
            AndroidPermissionLevel.DEBUGGER -> PermissionMode.DEBUGGER
            AndroidPermissionLevel.ADMIN -> PermissionMode.ADMIN
            AndroidPermissionLevel.ROOT -> PermissionMode.ROOT
        }

    private fun mapModeToPermissionLevel(mode: PermissionMode): AndroidPermissionLevel =
        when (mode) {
            PermissionMode.STANDARD -> AndroidPermissionLevel.STANDARD
            PermissionMode.ACCESSIBILITY -> AndroidPermissionLevel.ACCESSIBILITY
            PermissionMode.DEBUGGER -> AndroidPermissionLevel.DEBUGGER
            PermissionMode.ADMIN -> AndroidPermissionLevel.ADMIN
            PermissionMode.SHIZUKU -> AndroidPermissionLevel.ROOT // Shizuku 映射�?Root 级别
            PermissionMode.ROOT -> AndroidPermissionLevel.ROOT
        }

    /**
     * 检测所有权限模�?
     */
    suspend fun checkAllModes(forceRefresh: Boolean = false) = mutex.withLock {
        _isChecking.value = true
        val timestamp = System.currentTimeMillis()
        val newStates = mutableMapOf<PermissionMode, PermissionModeState>()

        try {
            AppLogger.d(TAG, "开始检测所有权限模式（强制刷新: ${forceRefresh}�?)

            // 优先检�?Root �?Shizuku（可能耗时较长�?
            checkRoot(forceRefresh)
            checkShizuku(forceRefresh)

            // 检测其他模�?
            for (mode in PermissionMode.values()) {
                if (mode == PermissionMode.ROOT || mode == PermissionMode.SHIZUKU) {
                    // Root �?Shizuku 已单独检�?
                    continue
                }

                val state = checkMode(mode, forceRefresh, timestamp)
                newStates[mode] = state
            }

            // �?Root �?Shizuku 结果构建状�?
            newStates[PermissionMode.ROOT] = buildRootState(timestamp)
            newStates[PermissionMode.SHIZUKU] = buildShizukuState(timestamp)

            _modeStates.update { newStates }
            notifyStateChanges(newStates.values)

            AppLogger.d(TAG, "所有权限模式检测完�?)
        } catch (e: Exception) {
            AppLogger.e(TAG, "检测权限模式失�?, e)
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * 检测指定权限模�?
     */
    suspend fun checkMode(mode: PermissionMode, forceRefresh: Boolean = false): PermissionModeState {
        val timestamp = System.currentTimeMillis()

        if (!forceRefresh) {
            detectionCache[mode]?.let { cached ->
                if (timestamp - cached.timestamp < DETECTION_CACHE_DURATION) {
                    AppLogger.v(TAG, "使用缓存�?${mode.displayName} 状�?)
                    return cached.state
                }
            }
        }

        return checkMode(mode, forceRefresh, timestamp)
    }

    private suspend fun checkMode(
        mode: PermissionMode,
        forceRefresh: Boolean,
        timestamp: Long
    ): PermissionModeState {
        AppLogger.d(TAG, "检�?${mode.displayName}...")

        val state = when (mode) {
            PermissionMode.STANDARD -> checkStandardMode(timestamp)
            PermissionMode.ACCESSIBILITY -> checkAccessibilityMode(timestamp)
            PermissionMode.DEBUGGER -> checkDebuggerMode(timestamp)
            PermissionMode.ADMIN -> checkAdminMode(timestamp)
            PermissionMode.SHIZUKU -> checkShizukuMode(timestamp)
            PermissionMode.ROOT -> checkRootMode(timestamp)
        }

        detectionCache[mode] = CachedResult(state, timestamp)
        _modeStates.update { current -> current.toMutableMap().apply { put(mode, state) } }
        notifyStateChanges(listOf(state))

        return state
    }

    private fun checkStandardMode(timestamp: Long): PermissionModeState =
        PermissionModeState(
            mode = PermissionMode.STANDARD,
            isAvailable = true,
            isGranted = true,
            isPreferred = _currentMode.value == PermissionMode.STANDARD,
            checkTimestamp = timestamp
        )

    private fun checkAccessibilityMode(timestamp: Long): PermissionModeState {
        val isAvailable = true
        val isGranted = checkAccessibilityServiceEnabled()

        return PermissionModeState(
            mode = PermissionMode.ACCESSIBILITY,
            isAvailable = isAvailable,
            isGranted = isGranted,
            isPreferred = _currentMode.value == PermissionMode.ACCESSIBILITY,
            checkTimestamp = timestamp,
            errorMessage = if (!isGranted) "需要启用无障碍服务" else null
        )
    }

    private fun checkAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceString = context.packageName + "/.accessibility.YourAccessibilityService"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabledServices?.contains(serviceString) == true
        } catch (e: Exception) {
            AppLogger.e(TAG, "检查无障碍服务失败", e)
            false
        }
    }

    private fun checkDebuggerMode(timestamp: Long): PermissionModeState {
        val isAvailable = true
        val isGranted = true // 调试模式总是可用

        return PermissionModeState(
            mode = PermissionMode.DEBUGGER,
            isAvailable = isAvailable,
            isGranted = isGranted,
            isPreferred = _currentMode.value == PermissionMode.DEBUGGER,
            checkTimestamp = timestamp
        )
    }

    private fun checkAdminMode(timestamp: Long): PermissionModeState {
        val isAvailable = true
        val isGranted = checkDeviceAdminEnabled()

        return PermissionModeState(
            mode = PermissionMode.ADMIN,
            isAvailable = isAvailable,
            isGranted = isGranted,
            isPreferred = _currentMode.value == PermissionMode.ADMIN,
            checkTimestamp = timestamp,
            errorMessage = if (!isGranted) "需要启用设备管理员" else null
        )
    }

    private fun checkDeviceAdminEnabled(): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(
                context,
                "com.apex.agent.admin.DeviceAdminReceiver"
            )
            devicePolicyManager?.isAdminActive(componentName) == true
        } catch (e: Exception) {
            AppLogger.e(TAG, "检查设备管理员失败", e)
            false
        }
    }

    private fun checkShizukuMode(timestamp: Long): PermissionModeState = buildShizukuState(timestamp)

    private fun checkRootMode(timestamp: Long): PermissionModeState = buildRootState(timestamp)

    private fun buildShizukuState(timestamp: Long): PermissionModeState {
        val result = _shizukuResult.value
        return PermissionModeState(
            mode = PermissionMode.SHIZUKU,
            isAvailable = result.isAvailable,
            isGranted = result.isGranted,
            isPreferred = _currentMode.value == PermissionMode.SHIZUKU,
            checkTimestamp = timestamp,
            errorMessage = result.errorMessage
        )
    }

    private fun buildRootState(timestamp: Long): PermissionModeState {
        val result = _rootResult.value
        return PermissionModeState(
            mode = PermissionMode.ROOT,
            isAvailable = result.isRooted,
            isGranted = result.hasRootAccess,
            isPreferred = _currentMode.value == PermissionMode.ROOT,
            checkTimestamp = timestamp,
            errorMessage = result.errorMessage
        )
    }

    /**
     * 检�?Root 状�?
     */
    suspend fun checkRoot(forceRefresh: Boolean = false): RootDetectionResult {
        val timestamp = System.currentTimeMillis()
        val cached = _rootResult.value

        if (!forceRefresh && cached.detectionTimestamp > 0 &&
            timestamp - cached.detectionTimestamp < DETECTION_CACHE_DURATION
        ) {
            AppLogger.v(TAG, "使用缓存�?Root 检测结�?)
            return cached
        }

        AppLogger.d(TAG, "检�?Root 状�?..")

        val result = try {
            // 使用 RootAuthorizer 检�?
            val isRooted = RootAuthorizer.isDeviceRooted()
            val hasRootAccess = RootAuthorizer.hasRootAccess()

            // 检�?Root 方案
            val rootScheme = detectRootScheme()

            // 检�?SELinux 状�?
            val seLinuxStatus = detectSELinuxStatus()

            RootDetectionResult(
                isRooted = isRooted,
                hasRootAccess = hasRootAccess,
                rootScheme = rootScheme,
                seLinuxStatus = seLinuxStatus,
                detectionTimestamp = timestamp
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "检�?Root 状态失�?, e)
            RootDetectionResult(
                isRooted = false,
                hasRootAccess = false,
                detectionTimestamp = timestamp,
                errorMessage = e.message
            )
        }

        _rootResult.value = result
        return result
    }

    private fun detectRootScheme(): RootScheme {
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

        return RootScheme.UNKNOWN
    }

    private fun detectSELinuxStatus(): SELinuxStatus {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            SELinuxStatus.fromString(output)
        } catch (e: Exception) {
            AppLogger.e(TAG, "检�?SELinux 状态失�?, e)
            SELinuxStatus.UNKNOWN
        }
    }

    /**
     * 检�?Shizuku 状�?
     */
    suspend fun checkShizuku(forceRefresh: Boolean = false): ShizukuDetectionResult {
        val timestamp = System.currentTimeMillis()
        val cached = _shizukuResult.value

        if (!forceRefresh && cached.detectionTimestamp > 0 &&
            timestamp - cached.detectionTimestamp < DETECTION_CACHE_DURATION
        ) {
            AppLogger.v(TAG, "使用缓存�?Shizuku 检测结�?)
            return cached
        }

        AppLogger.d(TAG, "检�?Shizuku 状�?..")

        val result = try {
            val isShizukuInstalled = ShizukuAuthorizer.isShizukuInstalled(context)
            val isServiceAvailable = ShizukuAuthorizer.isShizukuServiceRunning()
            val isGranted = ShizukuAuthorizer.hasShizukuPermission()
            val isSuiBackend = checkIsSuiBackend()

            ShizukuDetectionResult(
                isAvailable = isServiceAvailable,
                isGranted = isGranted,
                isSuiBackend = isSuiBackend,
                isShizukuInstalled = isShizukuInstalled,
                detectionTimestamp = timestamp
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "检�?Shizuku 状态失�?, e)
            ShizukuDetectionResult(
                isAvailable = false,
                isGranted = false,
                detectionTimestamp = timestamp,
                errorMessage = e.message
            )
        }

        _shizukuResult.value = result
        return result
    }

    private fun checkIsSuiBackend(): Boolean {
        return try {
            val pm = context.packageManager
            val suiPackage = "rikka.sui"
            try {
                pm.getPackageInfo(suiPackage, 0)
                true
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 切换到指定权限模�?
     */
    suspend fun switchToMode(mode: PermissionMode): Boolean = mutex.withLock {
        AppLogger.d(TAG, "切换�?${mode.displayName}...")

        val state = checkMode(mode, forceRefresh = true)
        if (!state.isUsable && mode != PermissionMode.STANDARD) {
            AppLogger.w(TAG, "无法切换�?${mode.displayName}: 模式不可�?)
            return false
        }

        try {
            androidPermissionPreferences.savePreferredPermissionLevel(mapModeToPermissionLevel(mode))
            _currentMode.value = mode

            // 更新所有模式的 isPreferred 状�?
            _modeStates.update { current ->
                current.mapValues { (m, s) ->
                    s.copy(isPreferred = m == mode)
                }
            }

            notifyModeChange(mode)
            AppLogger.d(TAG, "成功切换�?${mode.displayName}")
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "切换�?${mode.displayName} 失败", e)
            return false
        }
    }

    /**
     * 自动选择最佳可用模�?
     */
    suspend fun autoSelectBestMode(): PermissionMode? {
        checkAllModes(forceRefresh = true)

        val bestMode = PermissionMode.sortedByLevelDesc().firstOrNull { mode ->
            _modeStates.value[mode]?.isUsable == true
        }

        if (bestMode != null) {
            switchToMode(bestMode)
        }

        return bestMode
    }

    /**
     * 获取最佳可用模�?
     */
    fun getBestAvailableMode(): PermissionMode? {
        return PermissionMode.sortedByLevelDesc().firstOrNull { mode ->
            _modeStates.value[mode]?.isUsable == true
        } ?: PermissionMode.STANDARD
    }

    /**
     * 获取指定模式的状�?
     */
    fun getModeState(mode: PermissionMode): PermissionModeState? = _modeStates.value[mode]

    /**
     * 获取所有可用模�?
     */
    fun getAvailableModes(): List<PermissionModeState> =
        _modeStates.value.values.filter { it.isAvailable }

    /**
     * 获取所有可用且已授权的模式
     */
    fun getUsableModes(): List<PermissionModeState> =
        _modeStates.value.values.filter { it.isUsable }

    /**
     * 添加状态变更监听器
     */
    fun addStateChangeListener(listener: (PermissionModeState) -> Unit) {
        stateChangeListeners.add(listener)
    }

    /**
     * 移除状态变更监听器
     */
    fun removeStateChangeListener(listener: (PermissionModeState) -> Unit) {
        stateChangeListeners.remove(listener)
    }

    /**
     * 添加模式变更监听�?
     */
    fun addModeChangeListener(listener: (PermissionMode) -> Unit) {
        modeChangeListeners.add(listener)
    }

    /**
     * 移除模式变更监听�?
     */
    fun removeModeChangeListener(listener: (PermissionMode) -> Unit) {
        modeChangeListeners.remove(listener)
    }

    private fun notifyStateChanges(states: Collection<PermissionModeState>) {
        states.forEach { state ->
            stateChangeListeners.forEach { it(state) }
        }
    }

    private fun notifyModeChange(mode: PermissionMode) {
        modeChangeListeners.forEach { it(mode) }
    }

    private fun startAutoCheck() {
        scope.launch {
            while (true) {
                try {
                    delay(AUTO_CHECK_INTERVAL)
                    if (!_isChecking.value) {
                        checkAllModes(forceRefresh = false)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "自动检测失�?, e)
                }
            }
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        detectionCache.clear()
        _rootResult.value = RootDetectionResult()
        _shizukuResult.value = ShizukuDetectionResult()
        AppLogger.d(TAG, "已清除权限模式检测缓�?)
    }
}
