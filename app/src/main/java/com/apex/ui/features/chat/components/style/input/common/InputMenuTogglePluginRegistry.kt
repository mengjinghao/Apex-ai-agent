package com.apex.ui.features.chat.components.style.input.common

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 输入菜单开关插件注册表 — Stub。
 */
object InputMenuTogglePluginRegistry {
    private val plugins = CopyOnWriteArrayList<InputMenuTogglePlugin>()

    fun register(plugin: InputMenuTogglePlugin) {
        plugins.add(plugin)
    }

    fun unregister(plugin: InputMenuTogglePlugin) {
        plugins.remove(plugin)
    }

    fun getPlugins(): List<InputMenuTogglePlugin> = plugins.toList()

    fun getAllDefinitions(): List<InputMenuToggleDefinition> =
        plugins.flatMap { it.getDefinitions() }

    /**
     * 通知所有注册的插件状态已变更，触发它们刷新定义。
     * 业务代码在插件配置变更后调用此方法。
     */
    fun notifyChanged() {
        plugins.forEach { plugin ->
            try {
                plugin.getDefinitions()
            } catch (_: Exception) {
                // 通知阶段忽略单个插件的异常
            }
        }
    }

    fun clear() {
        plugins.clear()
    }
}
