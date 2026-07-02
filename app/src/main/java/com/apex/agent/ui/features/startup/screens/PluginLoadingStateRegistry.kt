package com.apex.agent.ui.features.startup.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * 插件加载状态注册表 — Stub。
 * 原用于在启动页显示插件加载进度，UI 移除后保留 getState/getScope API。
 */
object PluginLoadingStateRegistry {

    private val scope = MainScope()

    @JvmStatic
    fun getState(): PluginLoadingState = PluginLoadingState.IDLE

    @JvmStatic
    fun getScope(): CoroutineScope = scope

    @JvmStatic
    fun getPlugins(): List<PluginInfo> = emptyList()
}

enum class PluginLoadingState { IDLE, LOADING, COMPLETED, FAILED }

data class PluginInfo(
    val name: String,
    val status: PluginStatus,
    val message: String? = null
)

enum class PluginStatus { PENDING, LOADING, SUCCESS, FAILED }
