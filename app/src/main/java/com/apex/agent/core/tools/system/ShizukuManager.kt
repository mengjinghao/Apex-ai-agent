package com.apex.agent.core.tools.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Shizuku 管理�?- 提供统一�?Shizuku 管理接口
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SUI_PACKAGE = "rikka.sui"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 状态流
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isShizukuInstalled = MutableStateFlow(false)
    val isShizukuInstalled: StateFlow<Boolean> = _isShizukuInstalled.asStateFlow()

    private val _isServiceAvailable = MutableStateFlow(false)
    val isServiceAvailable: StateFlow<Boolean> = _isServiceAvailable.asStateFlow()

    private val _isPermissionGranted = MutableStateFlow(false)
    val isPermissionGranted: StateFlow<Boolean> = _isPermissionGranted.asStateFlow()

    private val _isSuiBackend = MutableStateFlow(false)
    val isSuiBackend: StateFlow<Boolean> = _isSuiBackend.asStateFlow()

    private val _currentUid = MutableStateFlow(-1)
    val currentUid: StateFlow<Int> = _currentUid.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // 监听器标�?
    private var binderReceivedListenerRegistered = false
    private var permissionRequestListenerRegistered = false

    // 状态监听器
    private val stateChangeListeners = mutableSetOf<() -> Unit>()

    /**
     * 初始�?Shizuku 管理�?
     */
    fun initialize() {
        if (_isInitialized.value) return

        scope.launch {
            try {
                AppLogger.d(TAG, "初始�?Shizuku 管理�?..")

                // 注册监听�?
                registerListeners()

                // 初始检�?
                checkStatus()

                _isInitialized.value = true
                AppLogger.d(TAG, "Shizuku 管理器初始化完成")
            } catch (e: Exception) {
                AppLogger.e(TAG, "初始�?Shizuku 管理器失�?, e)
                _lastError.value = e.message
            }
        }
    }

    private fun registerListeners() {
        if (binderReceivedListenerRegistered) return

        try {
            Shizuku.addBinderReceivedListener {
                AppLogger.d(TAG, "Shizuku binder 已接�?)
                _isServiceAvailable.value = true
                checkStatus()
                notifyStateChange()
            }

            Shizuku.addBinderDeadListener {
                AppLogger.d(TAG, "Shizuku binder 已失�?)
                _isServiceAvailable.value = false
                _isPermissionGranted.value = false
                notifyStateChange()
            }

            binderReceivedListenerRegistered = true
            AppLogger.d(TAG, "Shizuku 监听器已注册")
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册 Shizuku 监听器失�?, e)
            _lastError.value = "注册监听器失�? ${e.message}"
        }
    }

    /**
     * 检�?Shizuku 状�?
     */
    fun checkStatus() {
        scope.launch {
            try {
                AppLogger.d(TAG, "检�?Shizuku 状�?..")

                val installed = isShizukuOrSuiInstalled()
                _isShizukuInstalled.value = installed

                val serviceAvailable = checkServiceAvailable()
                _isServiceAvailable.value = serviceAvailable

                if (serviceAvailable) {
                    val permissionGranted = checkPermissionGranted()
                    _isPermissionGranted.value = permissionGranted

                    val isSui = checkIsSuiBackend()
                    _isSuiBackend.value = isSui

                    val uid = try {
                        Shizuku.getUid()
                    } catch (e: Exception) {
                        -1
                    }
                    _currentUid.value = uid
                }

                AppLogger.d(TAG, "Shizuku 状�?- 已安�? ${installed}, 服务可用: ${serviceAvailable}, 已授�? ${_isPermissionGranted.value}")
                notifyStateChange()
            } catch (e: Exception) {
                AppLogger.e(TAG, "检�?Shizuku 状态失�?, e)
                _lastError.value = e.message
            }
        }
    }

    private fun isShizukuOrSuiInstalled(context: Context? = null): Boolean {
        val pm = try {
            context?.packageManager
        } catch (e: Exception) {
            null
        }

        // 检�?SUI 后端
        if (checkIsSuiBackend()) {
            return true
        }

        // 检�?Shizuku �?
        return try {
            pm?.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "检�?Shizuku 安装状态失�?, e)
            false
        }
    }

    /**
     * 检�?Shizuku 是否已安装（兼容版本�?
     */
    fun isShizukuInstalled(context: Context): Boolean {
        return isShizukuOrSuiInstalled(context)
    }

    private fun checkIsSuiBackend(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                return true
            }
            val binder = Shizuku.getBinder()
            binder != null && binder.isBinderAlive
        } catch (e: Exception) {
            false
        }
    }

    private fun checkServiceAvailable(): Boolean {
        return try {
            val pingResult = Shizuku.pingBinder()
            if (pingResult) {
                return true
            }

            val binder = Shizuku.getBinder()
            binder != null && binder.isBinderAlive
        } catch (e: Exception) {
            AppLogger.w(TAG, "检�?Shizuku 服务失败", e)
            false
        }
    }

    /**
     * 检�?Shizuku 服务是否正在运行
     */
    fun isShizukuServiceRunning(): Boolean {
        return _isServiceAvailable.value || checkServiceAvailable()
    }

    private fun checkPermissionGranted(): Boolean {
        return try {
            when {
                !checkServiceAvailable() -> {
                    _lastError.value = "Shizuku 服务未运�?
                    false
                }
                else -> {
                    val result = Shizuku.checkSelfPermission()
                    val granted = result == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        _lastError.value = "Shizuku 权限未授�?
                    }
                    granted
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "检�?Shizuku 权限失败", e)
            _lastError.value = "检查权限失�? ${e.message}"
            false
        }
    }

    /**
     * 检查应用是否有 Shizuku 权限
     */
    fun hasShizukuPermission(): Boolean {
        return _isPermissionGranted.value || checkPermissionGranted()
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                AppLogger.d(TAG, "请求 Shizuku 权限...")

                if (!isShizukuServiceRunning()) {
                    AppLogger.w(TAG, "Shizuku 服务未运行，无法请求权限")
                    _lastError.value = "Shizuku 服务未运�?
                    onResult(false)
                    return@launch
                }

                if (hasShizukuPermission()) {
                    AppLogger.d(TAG, "已拥�?Shizuku 权限")
                    onResult(true)
                    return@launch
                }

                val requestCode = 1000

                Shizuku.addRequestPermissionResultListener { code, grantResult ->
                    if (code == requestCode) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        _isPermissionGranted.value = granted
                        if (!granted) {
                            _lastError.value = "权限请求被拒�?
                        }

                        AppLogger.d(TAG, "Shizuku 权限请求结果: ${granted}")
                        notifyStateChange()
                        onResult(granted)

                        try {
                            Shizuku.removeRequestPermissionResultListener { _, _ -> }
                            permissionRequestListenerRegistered = false
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "移除权限请求监听器失�?, e)
                        }
                    }
                }
                permissionRequestListenerRegistered = true

                Shizuku.requestPermission(requestCode)
            } catch (e: Exception) {
                AppLogger.e(TAG, "请求 Shizuku 权限失败", e)
                _lastError.value = "请求权限失败: ${e.message}"
                onResult(false)
            }
        }
    }

    /**
     * 获取 Shizuku 启动说明
     */
    fun getShizukuStartupInstructions(context: Context): String {
        return """
            请按以下步骤启动 Shizuku�?
            
            1. 确保已安�?Shizuku 应用
            2. 打开 Shizuku 应用
            3. 选择启动方式�?
               �?Root 设备：直接通过 Root 启动
               �?�?Root 设备：使�?ADB 启动
            4. 启动成功后返回本应用
        """.trimIndent()
    }

    /**
     * 获取 Shizuku 启动 ADB 命令
     */
    fun getAdbStartupCommand(): String {
        return """
            # 通过 ADB 启动 Shizuku
            adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh
            
            # 或者使用最新版本的方式
            adb shell "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
        """.trimIndent()
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
        mainHandler.post {
            stateChangeListeners.forEach { it() }
        }
    }

    /**
     * 清除错误状�?
     */
    fun clearError() {
        _lastError.value = null
    }

    /**
     * 获取综合状�?
     */
    fun getStatus(): ShizukuDetectionResult {
        return ShizukuDetectionResult(
            isAvailable = _isServiceAvailable.value,
            isGranted = _isPermissionGranted.value,
            isSuiBackend = _isSuiBackend.value,
            isShizukuInstalled = _isShizukuInstalled.value,
            uid = _currentUid.value,
            errorMessage = _lastError.value
        )
    }
}
