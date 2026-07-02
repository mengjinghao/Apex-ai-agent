package com.apex.agent.core.tools.system

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.R
import com.apex.core.tools.system.AndroidShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Shell命令管理�? 负责命令执行、历史记录管理等
 */
class ShellCommandManager(private val context: Context) {
    
    private val PREFS_NAME = "shell_executor_prefs"
    private val KEY_COMMAND_HISTORY = "command_history"
    private val MAX_HISTORY_SIZE = 100
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 获取预设命令列表
     */
    fun getPresetCommands(): List<PresetCommand> {
        return listOf(
            PresetCommand(
                name = context.getString(R.string.shell_cmd_test),
                command = context.getString(R.string.shell_cmd_test_command),
                description = context.getString(R.string.shell_cmd_test_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Check
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_sys_info),
                command = context.getString(R.string.shell_cmd_sys_info_cmd),
                description = context.getString(R.string.shell_cmd_sys_info_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Info
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_disk),
                command = context.getString(R.string.shell_cmd_disk_cmd),
                description = context.getString(R.string.shell_cmd_disk_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Storage
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_memory),
                command = context.getString(R.string.shell_cmd_memory_cmd),
                description = context.getString(R.string.shell_cmd_memory_desc),
                category = CommandCategory.HARDWARE,
                icon = Icons.Default.Memory
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_cpu),
                command = context.getString(R.string.shell_cmd_cpu_cmd),
                description = context.getString(R.string.shell_cmd_cpu_desc),
                category = CommandCategory.HARDWARE,
                icon = Icons.Default.SettingsApplications
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_network),
                command = context.getString(R.string.shell_cmd_network_cmd),
                description = context.getString(R.string.shell_cmd_network_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.Wifi
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_route),
                command = context.getString(R.string.shell_cmd_route_cmd),
                description = context.getString(R.string.shell_cmd_route_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.Router
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_connection),
                command = context.getString(R.string.shell_cmd_connection_cmd),
                description = context.getString(R.string.shell_cmd_connection_desc),
                category = CommandCategory.NETWORK,
                icon = Icons.Default.NetworkCheck
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_installed_apps),
                command = context.getString(R.string.shell_cmd_installed_apps_cmd),
                description = context.getString(R.string.shell_cmd_installed_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.Apps
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_system_apps),
                command = context.getString(R.string.shell_cmd_system_apps_cmd),
                description = context.getString(R.string.shell_cmd_system_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.Android
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_3rd_apps),
                command = context.getString(R.string.shell_cmd_3rd_apps_cmd),
                description = context.getString(R.string.shell_cmd_3rd_apps_desc),
                category = CommandCategory.PACKAGE,
                icon = Icons.Default.AppShortcut
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_current_dir),
                command = context.getString(R.string.shell_cmd_current_dir_cmd),
                description = context.getString(R.string.shell_cmd_current_dir_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.Folder
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_root_dir),
                command = context.getString(R.string.shell_cmd_root_dir_cmd),
                description = context.getString(R.string.shell_cmd_root_dir_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.FolderOpen
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_storage),
                command = context.getString(R.string.shell_cmd_storage_cmd),
                description = context.getString(R.string.shell_cmd_storage_desc),
                category = CommandCategory.FILE,
                icon = Icons.Default.SdCard
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_process),
                command = context.getString(R.string.shell_cmd_process_cmd),
                description = context.getString(R.string.shell_cmd_process_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.ViewList
            ),
            PresetCommand(
                name = context.getString(R.string.shell_cmd_properties),
                command = context.getString(R.string.shell_cmd_properties_cmd),
                description = context.getString(R.string.shell_cmd_properties_desc),
                category = CommandCategory.SYSTEM,
                icon = Icons.Default.Settings
            )
        )
    }
    
    /**
     * 添加自定义预设命�?    */
    suspend fun addCustomPresetCommand(command: PresetCommand) {
        // 实现保存自定义预设命令的逻辑
    }
    
    /**
     * 获取命令历史记录
     */
    fun getCommandHistory(): List<CommandRecord> {
        val historyJson = prefs.getString(KEY_COMMAND_HISTORY, null) ?: return emptyList()
        return try {
            // 在实际实现中，使用JSON解析库如Gson或Moshi来解析历史记�?           // 此处为简化，返回空列�?           emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 执行Shell命令
     */
    suspend fun executeCommand(command: String): CommandRecord {
        val result = withContext(Dispatchers.IO) {
            AndroidShellExecutor.executeShellCommand(command)
        }
        
        val record = CommandRecord(
            command = command,
            result = result,
            timestamp = System.currentTimeMillis()
        )
        
        // 保存到历史记�?       saveCommandToHistory(record)
        
        return record
    }
    
    /**
     * 保存命令到历史记�?    */
    private fun saveCommandToHistory(record: CommandRecord) {
        val history = getCommandHistory().toMutableList()
        
        // 如果已存在相同命令，移除旧记�?       history.removeAll { it.command == record.command }
        
        // 添加新记录到列表头部
        history.add(0, record)
        
        // 限制历史记录大小
        val trimmedHistory = history.take(MAX_HISTORY_SIZE)
        
        // 保存到SharedPreferences
        // 在实际实现中，使用JSON序列化库将历史记录转换为JSON字符�?       // prefs.edit().putString(KEY_COMMAND_HISTORY, jsonString).apply()
    }
    
    /**
     * 清除命令历史
     */
    fun clearCommandHistory() {
        prefs.edit().remove(KEY_COMMAND_HISTORY).apply()
    }
    
    /**
     * 从历史记录中移除指定命令
     */
    fun removeCommandFromHistory(command: String) {
        val history = getCommandHistory().toMutableList()
        history.removeAll { it.command == command }
        
        // 保存到SharedPreferences
        // 在实际实现中，使用JSON序列化库将历史记录转换为JSON字符�?       // prefs.edit().putString(KEY_COMMAND_HISTORY, jsonString).apply()
    }
    
    /**
     * 获取建议的命令列表（基于历史记录和输入的前缀�?    */
    fun getSuggestedCommands(prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        
        val history = getCommandHistory()
        return history
            .map { it.command }
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(5)
    }
} 