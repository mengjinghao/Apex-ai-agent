package com.apex.plugins

import com.apex.agent.plugins.toolbox.ToolboxPlugin
import com.apex.agent.plugins.toolpkg.ToolPkgCommonBridgePlugin
import com.apex.agent.plugins.workflow.WorkflowLifecyclePlugin
import com.apex.util.AppLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件注册表 - 管理应用级插件的注册、安装与卸载
 *
 * 使用单例模式，通过 initializeBuiltins() 初始化内置插件。
 */
interface ApexPlugin {
    val id: String
    fun register()
}

/**
 * 扩展的插件接口，提供更完整的插件生命周期管理
 */
interface Plugin : ApexPlugin {
    val name: String
    val version: String
    fun unregister()
}

object PluginRegistry {
    private const val TAG = "PluginRegistry"
        private val plugins = CopyOnWriteArrayList<ApexPlugin>()
        private val installedPluginIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var builtinsInitialized = false

    @Synchronized
    fun register(plugin: ApexPlugin) {
        plugins.removeAll { it.id == plugin.id }
        plugins.add(plugin)
        AppLogger.d(TAG, "已注册插件: ${plugin.id}")
    }

    @Synchronized
    fun initializeBuiltins() {
        if (builtinsInitialized) return
        builtinsInitialized = true

        AppLogger.i(TAG, "正在初始化内置插件...")
        register(ToolboxPlugin)
        register(ToolPkgCommonBridgePlugin)
        register(WorkflowLifecyclePlugin)
        installAll()
    }

    @Synchronized
    fun installAll() {
        for (plugin in plugins) {
            if (installedPluginIds.add(plugin.id)) {
                try {
                    plugin.register()
                    AppLogger.d(TAG, "已安装插件: ${plugin.id}")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "插件安装失败: ${plugin.javaClass.simpleName} (${plugin.id})", e)
                }
            }
        }
    }

    @Synchronized
    fun install(plugin: ApexPlugin): Result<Boolean> = try {
        require(plugin.id.isNotBlank()) { "插件 ID 不能为空" }
        register(plugin)
        if (installedPluginIds.add(plugin.id)) {
            plugin.register()
            AppLogger.d(TAG, "已安装插件: ${plugin.id}")
        }
        Result.success(true)
    } catch (e: Exception) {
        AppLogger.e(TAG, "安装插件失败: ${plugin.javaClass.simpleName}", e)
        Result.failure(e)
    }

    @Synchronized
    fun uninstall(pluginId: String): Boolean {
        val plugin = plugins.find { it.id == pluginId } ?: return false
        installedPluginIds.remove(pluginId)
        plugins.removeAll { it.id == pluginId }
        if (plugin is Plugin) {
            plugin.unregister()
        }
        AppLogger.d(TAG, "已卸载插件: $pluginId")
        return true
    }
        fun isInstalled(pluginId: String): Boolean = installedPluginIds.contains(pluginId)
        fun pluginCount(): Int = installedPluginIds.size

    fun getAllPlugins(): List<ApexPlugin> = plugins.toList()
        fun getPlugin(id: String): ApexPlugin? = plugins.find { it.id == id }

    @Synchronized
    fun clear() {
        val ids = plugins.map { it.id }.toList()
        ids.forEach { uninstall(it) }
        plugins.clear()
        installedPluginIds.clear()
        builtinsInitialized = false
        AppLogger.d(TAG, "插件注册表已清空")
    }
}
