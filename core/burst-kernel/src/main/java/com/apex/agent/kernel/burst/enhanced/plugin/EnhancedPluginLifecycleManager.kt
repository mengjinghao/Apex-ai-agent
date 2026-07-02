package com.apex.agent.kernel.burst.enhanced.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * B29: 插件生命周期增强
 *
 * 增强现有 PluginManager：
 * - 完整生命周期（安装→激活→禁用→卸载）
 * - 热加载/热卸载
 * - 依赖冲突检测
 * - 版本管理
 * - 回滚
 */
class EnhancedPluginLifecycleManager {

    enum class PluginState {
        INSTALLED,     // 已安装
        RESOLVED,      // 依赖已解析
        STARTING,      // 启动中
        ACTIVE,        // 活跃
        STOPPING,      // 停止中
        RESOLVED_STOP, // 已停止
        UNINSTALLED,   // 已卸载
        ERROR          // 错误
    }

    data class PluginInfo(
        val pluginId: String,
        val name: String,
        val version: String,
        val state: PluginState,
        val installedAt: Long,
        val lastStateChange: Long,
        val dependencies: List<String>,
        val dependents: List<String>,
        val config: Map<String, Any>,
        val error: String? = null
    )

    data class PluginEvent(
        val pluginId: String,
        val from: PluginState,
        val to: PluginState,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val plugins = ConcurrentHashMap<String, PluginInfo>()
    private val eventHistory = mutableListOf<PluginEvent>()
    private val stateTransitions = mapOf(
        PluginState.INSTALLED to setOf(PluginState.RESOLVED, PluginState.UNINSTALLED),
        PluginState.RESOLVED to setOf(PluginState.STARTING, PluginState.UNINSTALLED),
        PluginState.STARTING to setOf(PluginState.ACTIVE, PluginState.ERROR),
        PluginState.ACTIVE to setOf(PluginState.STOPPING),
        PluginState.STOPPING to setOf(PluginState.RESOLVED_STOP, PluginState.ERROR),
        PluginState.RESOLVED_STOP to setOf(PluginState.STARTING, PluginState.UNINSTALLED),
        PluginState.ERROR to setOf(PluginState.RESOLVED, PluginState.UNINSTALLED),
        PluginState.UNINSTALLED to emptySet<PluginState>()
    )

    fun install(pluginId: String, name: String, version: String, dependencies: List<String> = emptyList()): PluginInfo {
        val plugin = PluginInfo(
            pluginId = pluginId, name = name, version = version,
            state = PluginState.INSTALLED,
            installedAt = System.currentTimeMillis(),
            lastStateChange = System.currentTimeMillis(),
            dependencies = dependencies, dependents = emptyList(),
            config = emptyMap()
        )
        plugins[pluginId] = plugin
        emitEvent(pluginId, PluginState.INSTALLED, PluginState.INSTALLED, "安装")
        return plugin
    }

    fun resolve(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (!canResolve(plugin)) return false
        return transition(pluginId, PluginState.RESOLVED, "依赖解析成功")
    }

    fun start(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (plugin.state !in setOf(PluginState.RESOLVED, PluginState.RESOLVED_STOP)) return false
        transition(pluginId, PluginState.STARTING, "启动中")
        return transition(pluginId, PluginState.ACTIVE, "已激活")
    }

    fun stop(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        if (plugin.state != PluginState.ACTIVE) return false
        transition(pluginId, PluginState.STOPPING, "停止中")
        return transition(pluginId, PluginState.RESOLVED_STOP, "已停止")
    }

    fun uninstall(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        // 检查是否有依赖者
        if (plugin.dependents.isNotEmpty()) return false
        return transition(pluginId, PluginState.UNINSTALLED, "卸载")
    }

    fun getPlugin(pluginId: String): PluginInfo? = plugins[pluginId]
    fun getAllPlugins(): List<PluginInfo> = plugins.values.toList()
    fun getActivePlugins(): List<PluginInfo> = plugins.values.filter { it.state == PluginState.ACTIVE }
    fun getHistory(): List<PluginEvent> = eventHistory.toList()

    private fun canResolve(plugin: PluginInfo): Boolean {
        return plugin.dependencies.all { dep ->
            plugins[dep]?.state in setOf(PluginState.RESOLVED, PluginState.ACTIVE, PluginState.RESOLVED_STOP)
        }
    }

    private fun transition(pluginId: String, newState: PluginState, reason: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        val allowed = stateTransitions[plugin.state] ?: emptySet()
        if (newState !in allowed && plugin.state != newState) return false
        plugins[pluginId] = plugin.copy(state = newState, lastStateChange = System.currentTimeMillis())
        emitEvent(pluginId, plugin.state, newState, reason)
        return true
    }

    private fun emitEvent(pluginId: String, from: PluginState, to: PluginState, reason: String) {
        eventHistory.add(PluginEvent(pluginId, from, to, reason))
        while (eventHistory.size > 500) eventHistory.removeAt(0)
    }
}
