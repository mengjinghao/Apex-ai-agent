package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.data.preferences.PermissionConfig
import com.apex.agent.data.preferences.enhancedPermissionPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 权限模式配置备份恢复
 */
class PermissionConfigBackupManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "PermissionConfigBackup"
        private const val BACKUP_FILE_NAME = "permission_config.json"
        private const val BACKUP_DIR = "permission_backups"
    }
        private val _isBackingUp = MutableStateFlow(false)
        val isBackingUp: Flow<Boolean> = _isBackingUp.asStateFlow()
        private val _isRestoring = MutableStateFlow(false)
        val isRestoring: Flow<Boolean> = _isRestoring.asStateFlow()
        private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 备份配置
     */
    suspend fun backupConfig(): BackupResult {
        _isBackingUp.value = true
        return try {
            val config = getCurrentConfig()
        val backupDir = getBackupDir()
        val backupFile = File(backupDir, BACKUP_FILE_NAME)

            // 创建目录
    if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            // 写入备份文件
    val jsonString = json.encodeToString(config)
            backupFile.writeText(jsonString)

            AppLogger.d(TAG, "配置备份成功: ${backupFile.absolutePath}")

            BackupResult.Success(backupFile.absolutePath)
        } catch (e: Exception) {
            AppLogger.e(TAG, "备份配置失败", e)
            BackupResult.Error(e.message ?: "未知错误")
        } finally {
            _isBackingUp.value = false
        }
    }

    /**
     * 恢复配置
     */
    suspend fun restoreConfig(): RestoreResult {
        _isRestoring.value = true
        return try {
            val backupFile = getBackupFile()
        if (!backupFile.exists()) {
                return RestoreResult.Error("备份文件不存�?)
            }
        val jsonString = backupFile.readText()
        val config = json.decodeFromString<PermissionConfig>(jsonString)

            // 恢复配置
            restoreConfigFromData(config)

            AppLogger.d(TAG, "配置恢复成功")
            RestoreResult.Success
        } catch (e: Exception) {
            AppLogger.e(TAG, "恢复配置失败", e)
            RestoreResult.Error(e.message ?: "未知错误")
        } finally {
            _isRestoring.value = false
        }
    }

    /**
     * �?JSON 字符串恢复配�?
     */
    suspend fun restoreConfigFromJson(jsonString: String): RestoreResult {
        return try {
            val config = json.decodeFromString<PermissionConfig>(jsonString)
            restoreConfigFromData(config)
            RestoreResult.Success
        } catch (e: Exception) {
            AppLogger.e(TAG, "�?JSON 恢复配置失败", e)
            RestoreResult.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 获取当前配置
     */
    private fun getCurrentConfig(): PermissionConfig {
        return PermissionConfig(
            preferredMode = enhancedPermissionPreferences.getPreferredMode()?.id,
            rootExecutionMode = enhancedPermissionPreferences.getRootExecutionMode().id,
            customSuCommand = enhancedPermissionPreferences.getCustomSuCommand(),
            autoSwitchEnabled = enhancedPermissionPreferences.isAutoSwitchEnabled(),
            rememberLastMode = enhancedPermissionPreferences.shouldRememberLastMode(),
            lastUsedMode = enhancedPermissionPreferences.getLastUsedMode()?.id,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 恢复配置
     */
    private suspend fun restoreConfigFromData(config: PermissionConfig) {
        config.preferredMode?.let {
            enhancedPermissionPreferences.savePreferredMode(PermissionMode.fromId(it))
        }

        enhancedPermissionPreferences.saveRootExecutionMode(
            RootExecutionMode.fromId(config.rootExecutionMode)
        )

        enhancedPermissionPreferences.saveCustomSuCommand(config.customSuCommand)
        enhancedPermissionPreferences.saveAutoSwitchEnabled(config.autoSwitchEnabled)
        enhancedPermissionPreferences.saveRememberLastMode(config.rememberLastMode)
    }

    /**
     * 获取备份目录
     */
    private fun getBackupDir(): File {
        val dir = File(context.getExternalFilesDir(null), BACKUP_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取备份文件
     */
    fun getBackupFile(): File {
        return File(getBackupDir(), BACKUP_FILE_NAME)
    }

    /**
     * 检查是否存在备�?
     */
    fun hasBackup(): Boolean {
        return getBackupFile().exists()
    }

    /**
     * 删除备份
     */
    fun deleteBackup(): Boolean {
        return try {
            val file = getBackupFile()
        if (file.exists()) {
                val deleted = file.delete()
        if (deleted) {
                    AppLogger.d(TAG, "备份已删�?)
                }
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "删除备份失败", e)
            false
        }
    }

    /**
     * 导出配置�?JSON
     */
    fun exportConfigToJson(): String {
        val config = getCurrentConfig()
        return json.encodeToString(config)
    }
}

/**
 * 备份结果
 */
sealed class BackupResult {
    data class Success(val filePath: String) : BackupResult()
    data class Error(val message: String) : BackupResult()
}

/**
 * 恢复结果
 */
sealed class RestoreResult {
    object Success : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

/**
 * 智能模式切换�?
 */
class SmartModeSwitcher(
    private val modeManager: PermissionModeManager
) {
    companion object {
        private const val TAG = "SmartModeSwitcher"
    }
        private val _autoSwitchEnabled = MutableStateFlow(false)
        val autoSwitchEnabled: Flow<Boolean> = _autoSwitchEnabled.asStateFlow()
        private val _switchHistory = MutableStateFlow<List<SwitchHistoryItem>>(emptyList())
        val switchHistory: Flow<List<SwitchHistoryItem>> = _switchHistory.asStateFlow()

    /**
     * 启用自动切换
     */
    fun enableAutoSwitch() {
        _autoSwitchEnabled.value = true
        AppLogger.d(TAG, "自动切换已启�?)
    }

    /**
     * 禁用自动切换
     */
    fun disableAutoSwitch() {
        _autoSwitchEnabled.value = false
        AppLogger.d(TAG, "自动切换已禁�?)
    }

    /**
     * 智能选择最佳模�?
     */
    suspend fun smartSwitchToBestMode(): Boolean {
        if (!_autoSwitchEnabled.value) {
            AppLogger.d(TAG, "自动切换未启�?)
        return false
        }
        val bestMode = modeManager.autoSelectBestMode()
        if (bestMode != null) {
            recordSwitch(bestMode)
        return true
        }
        return false
    }

    /**
     * 根据场景选择模式
     */
    fun selectModeForScenario(scenario: UsageScenario): PermissionMode {
        return when (scenario) {
            UsageScenario.STANDARD -> PermissionMode.STANDARD
            UsageScenario.AUTOMATION -> PermissionMode.ACCESSIBILITY
            UsageScenario.DEBUG -> PermissionMode.DEBUGGER
            UsageScenario.SYSTEM_ADMIN -> {
                if (modeManager.currentMode.value?.let {
                    it == PermissionMode.SHIZUKU || it == PermissionMode.ROOT } == true) {
                    modeManager.currentMode.value!!
                } else {
                    val states.values.find { it.isUsable }?.mode ?: PermissionMode.STANDARD
                }
            }
        }
    }

    /**
     * 记录切换历史
     */
    private fun recordSwitch(mode: PermissionMode) {
        val item = SwitchHistoryItem(
            mode = mode,
            timestamp = System.currentTimeMillis()
        )

        _switchHistory.update { history ->
            val newHistory = (listOf(item) + history).take(20) // 保留最�?0�?
            newHistory
        }

        AppLogger.d(TAG, "记录模式切换: ${mode.displayName}")
    }

    /**
     * 清除历史
     */
    fun clearHistory() {
        _switchHistory.update { emptyList() }
        AppLogger.d(TAG, "切换历史已清�?)
    }
}

/**
 * 使用场景
 */
enum class UsageScenario {
    STANDARD, // 标准使用
    AUTOMATION, // 自动�?
    DEBUG, // 调试
    SYSTEM_ADMIN // 系统管理
}

/**
 * 切换历史�?
 */
data class SwitchHistoryItem(
    val mode: PermissionMode,
    val timestamp: Long,
    val reason: String? = null
) {
    val formattedTime: String
        get() {
            // 格式化时间戳
    val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return format.format(date)
        }
}

/**
 * 权限模式建议�?
 */
class PermissionModeAdvisor(
    private val modeManager: PermissionModeManager
) {
    companion object {
        private const val TAG = "PermissionModeAdvisor"
    }

    /**
     * 获取推荐的模�?
     */
    fun getRecommendedMode(): PermissionMode {
        val states = modeManager.modeStates.value
        return states.values.filter { it.isUsable }
            .maxByOrNull { it.mode.level }
            ?.mode
            ?: PermissionMode.STANDARD
    }

    /**
     * 获取模式建议
     */
    fun getModeSuggestions(): List<ModeSuggestion> {
        val states = modeManager.modeStates.value
        val suggestions = mutableListOf<ModeSuggestion>()

        // 添加推荐模式
    val recommended = getRecommendedMode()
        if (recommended != PermissionMode.STANDARD) {
            suggestions.add(
                ModeSuggestion(
                    mode = recommended,
                    type = SuggestionType.RECOMMENDED,
                    reason = "提供最佳功能和权限"
                )
            )
        }

        // 添加当前模式
        modeManager.currentMode.value?.let { current ->
            if (current != recommended) {
                suggestions.add(
                    ModeSuggestion(
                        mode = current,
                        type = SuggestionType.CURRENT,
                        reason = "当前正在使用"
                    )
                )
            }
        }

        // 添加可用模式
        states.values.filter { it.isUsable }
            .filterNot { it.mode == recommended || it.mode == modeManager.currentMode.value }
            .forEach { state ->
                suggestions.add(
                    ModeSuggestion(
                        mode = state.mode,
                        type = SuggestionType.AVAILABLE,
                        reason = "可用"
                    )
                )
            }
        return suggestions
    }

    /**
     * 获取模式详细信息
     */
    fun getModeDetails(mode: PermissionMode): ModeDetails {
        val state = modeManager.getModeState(mode)
        return ModeDetails(
            mode = mode,
            state = state,
            features = getModeFeatures(mode),
            limitations = getModeLimitations(mode)
        )
    }
        private fun getModeFeatures(mode: PermissionMode): List<String> {
        return when (mode) {
            PermissionMode.STANDARD -> listOf("安全稳定", "无需额外权限", "适合日常使用")
            PermissionMode.ACCESSIBILITY -> listOf("UI自动�?, "无障碍服�?, "无需Root")
            PermissionMode.DEBUGGER -> listOf("ADB调试", "开发测�?, "系统访问")
            PermissionMode.ADMIN -> listOf("设备管理", "安全策略", "系统控制")
            PermissionMode.SHIZUKU -> listOf("系统级权�?, "无需Root", "安全可控")
            PermissionMode.ROOT -> listOf("完全Root权限", "系统完全控制", "强大功能")
        }
    }
        private fun getModeLimitations(mode: PermissionMode): List<String> {
        return when (mode) {
            PermissionMode.STANDARD -> listOf("功能受限", "无法访问系统文件")
            PermissionMode.ACCESSIBILITY -> listOf("需要用户授�?, "部分功能受限")
            PermissionMode.DEBUGGER -> listOf("需要ADB连接", "权限有限")
            PermissionMode.ADMIN -> listOf("需要设备管理权�?, "设置复杂")
            PermissionMode.SHIZUKU -> listOf("需要Shizuku服务", "初次设置复杂")
            PermissionMode.ROOT -> listOf("安全风险", "可能导致保修失效")
        }
    }
}

/**
 * 模式建议
 */
data class ModeSuggestion(
    val mode: PermissionMode,
    val type: SuggestionType,
    val reason: String
)

/**
 * 建议类型
 */
enum class SuggestionType {
    RECOMMENDED,
    CURRENT,
    AVAILABLE
}

/**
 * 模式详细信息
 */
data class ModeDetails(
    val mode: PermissionMode,
    val state: PermissionModeState?,
    val features: List<String>,
    val limitations: List<String>
)
