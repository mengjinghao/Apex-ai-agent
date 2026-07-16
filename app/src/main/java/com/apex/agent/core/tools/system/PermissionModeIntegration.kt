package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.core.tools.system.shell.PermissionModeExecutorFactory
import com.apex.agent.data.preferences.initEnhancedPermissionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.apex.agent.core.tools.system.RootManager
import com.apex.agent.core.tools.system.ShizukuManager

/**
 * 统一的权限模式集成器
 * 负责初始化和管理所有权限模式相关的组件
 */
object PermissionModeIntegration {
    private const val TAG = "PermissionModeIntegration"

    private val integrationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false

    // 管理器实?
    private lateinit var modeManager: PermissionModeManager
    private lateinit var backupManager: PermissionConfigBackupManager
    private lateinit var smartSwitcher: SmartModeSwitcher
    private lateinit var advisor: PermissionModeAdvisor

    // 状态流
    private val _isReady = androidx.compose.runtime.mutableStateOf(false)
    val isReady: androidx.compose.runtime.State<Boolean> = _isReady

    /**
     * 初始化权限模式系?
     * 应该?Application.onCreate() 中调?
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            AppLogger.d(TAG, "权限模式系统已初始化，跳?)
            return
        }

        try {
            AppLogger.d(TAG, "开始初始化权限模式系统...")

            // 1. 初始化增强的偏好管理
            initEnhancedPermissionPreferences(context)
            AppLogger.d(TAG, "增强偏好管理初始化完?)

            // 2. 初始?RootManager
            RootManager.initialize(context)
            AppLogger.d(TAG, "?RootManager 初始化完?)

            // 3. 初始?ShizukuManager
            ShizukuManager.initialize()
            AppLogger.d(TAG, "?ShizukuManager 初始化完?)

            // 4. 初始?PermissionModeManager
            modeManager = PermissionModeManager.getInstance(context)
            AppLogger.d(TAG, "?PermissionModeManager 初始化完?)

            // 5. 初始化备份管理器
            backupManager = PermissionConfigBackupManager(context)
            AppLogger.d(TAG, "?PermissionConfigBackupManager 初始化完?)

            // 6. 初始化智能切换器
            smartSwitcher = SmartModeSwitcher(modeManager)
            AppLogger.d(TAG, "?SmartModeSwitcher 初始化完?)

            // 7. 初始化建议器
            advisor = PermissionModeAdvisor(modeManager)
            AppLogger.d(TAG, "?PermissionModeAdvisor 初始化完?)

            // 8. 预加载执行器（不阻塞主线程）
            integrationScope.launch {
                try {
                    val factory = PermissionModeExecutorFactory.getInstance(context)
                    factory.preloadExecutors()
                    AppLogger.d(TAG, "执行器预加载完成")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "执行器预加载失败", e)
                }
            }

            // 9. 初始检?
            integrationScope.launch {
                try {
                    modeManager.checkAllModes(forceRefresh = false)
                    _isReady.value = true
                    AppLogger.d(TAG, "初始检测完成，系统已就?)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "初始检测失?, e)
                }
            }

            isInitialized = true
            AppLogger.d(TAG, "🎉 权限模式系统初始化成?)
        } catch (e: Exception) {
            AppLogger.e(TAG, "权限模式系统初始化失?, e)
        }
    }

    /**
     * 获取 PermissionModeManager 实例
     */
    fun getModeManager(): PermissionModeManager = modeManager

    /**
     * 获取 PermissionConfigBackupManager 实例
     */
    fun getBackupManager(): PermissionConfigBackupManager = backupManager

    /**
     * 获取 SmartModeSwitcher 实例
     */
    fun getSmartSwitcher(): SmartModeSwitcher = smartSwitcher

    /**
     * 获取 PermissionModeAdvisor 实例
     */
    fun getAdvisor(): PermissionModeAdvisor = advisor

    /**
     * 获取最佳可用的权限模式
     */
    fun getBestAvailableMode(): PermissionMode = modeManager.getBestAvailableMode()

    /**
     * 智能选择并切换到最佳模?
     */
    suspend fun autoSwitchToBestMode(): Boolean = smartSwitcher.smartSwitchToBestMode()

    /**
     * 检查是否已完全初始?
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * 执行完整的系统检?
     */
    suspend fun performFullSystemCheck(context: Context): SystemCheckResult {
        val start = System.currentTimeMillis()

        modeManager.checkAllModes(forceRefresh = true)
        val rootCheck = RootManager.checkRootStatus(context, true)
        val shizukuCheck = ShizukuManager.getStatus()

        val duration = System.currentTimeMillis() - start

        return SystemCheckResult(
            duration = duration,
            modeStates = modeManager.modeStates.value,
            rootResult = rootCheck,
            shizukuResult = shizukuCheck
        )
    }
}

/**
 * 系统检测结?
 */
data class SystemCheckResult(
    val duration: Long,
    val modeStates: Map<PermissionMode, PermissionModeState>,
    val rootResult: RootDetectionResult,
    val shizukuResult: ShizukuDetectionResult
)
