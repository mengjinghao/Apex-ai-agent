package com.apex.core.kernel

import java.util.concurrent.ConcurrentHashMap

/**
 * 插件注册中心 — 管理插件生命周期和依赖关系。
 */
class PluginRegistry(
    private val locator: ServiceLocator,
    private val bus: EventBus
) {
    private val plugins = ConcurrentHashMap<String, ApexPlugin>()

    fun register(plugin: ApexPlugin) {
        plugins[plugin.metadata.id] = plugin
    }

    fun unregister(id: String) {
        plugins.remove(id)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ApexPlugin> get(id: String): T? {
        return plugins[id] as? T
    }

    fun list(): List<ApexPlugin> = plugins.values.toList()

    fun uninstallAll() {
        plugins.clear()
    }
}

/** 插件接口 */
interface ApexPlugin {
    val metadata: PluginMetadata
    val dependencies: Set<String> get() = emptySet()
    suspend fun onInstall() {}
    suspend fun onUninstall() {}
}

/** 插件元信息 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = ""
)
