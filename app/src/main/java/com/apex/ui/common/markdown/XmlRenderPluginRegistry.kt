package com.apex.ui.common.markdown

import java.util.concurrent.CopyOnWriteArrayList

/**
 * XML 渲染插件注册表 — Stub。
 */
object XmlRenderPluginRegistry {
    private val plugins = CopyOnWriteArrayList<XmlRenderPlugin>()

    fun register(plugin: XmlRenderPlugin) {
        plugins.add(plugin)
    }

    fun unregister(plugin: XmlRenderPlugin) {
        plugins.remove(plugin)
    }

    fun getPlugins(): List<XmlRenderPlugin> = plugins.toList()

    fun findHandler(tag: String): XmlRenderPlugin? = plugins.firstOrNull { it.canHandle(tag) }

    /**
     * 通知所有注册的插件状态已变更。
     * 业务代码在插件配置变更后调用此方法。
     */
    fun notifyChanged() {
        // 触发插件列表的重新扫描
        plugins.forEach { _ -> /* no-op per plugin */ }
    }

    fun clear() {
        plugins.clear()
    }
}
