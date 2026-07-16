package com.apex.agent.core.tools.integration

import android.content.Context
import com.apex.agent.core.tools.integration.provider.LobeHubIntegration
import com.apex.agent.core.tools.integration.provider.McpSoIntegration
import com.apex.agent.core.tools.integration.provider.SkillRepoIntegration
import com.apex.util.AppLogger

/**
 * 统一集成管理�?—�所有市�集成源的注册和调度中�? */
object IntegrationManager {

    private const val TAG = "IntegrationManager"
    private val providers = mutableMapOf<String, IntegrationProvider>()

    fun initialize(context: Context) {
        register(McpSoIntegration(context))
        register(LobeHubIntegration(context))
        register(SkillRepoIntegration())
        AppLogger.d(TAG, "已注�?${providers.size} 个集成源: ${providers.keys}")
    }

    fun register(provider: IntegrationProvider) {
        providers[provider.getInfo().id] = provider
        AppLogger.d(TAG, "注册集成�? ${provider.getInfo().name}")
    }

    fun unregister(id: String) {
        providers.remove(id)
    }

    fun getProvider(id: String): IntegrationProvider? = providers[id]

    fun getAllProviders(): List<IntegrationProvider> = providers.values.toList()

    fun getAvailableProviders(): List<IntegrationProvider> = providers.values.filter { it.isAvailable() }

    fun getAllIntegrations(): List<IntegrationInfo> = providers.values.map { it.getInfo() }

    suspend fun searchAll(query: String, sourceFilter: String? = null): List<UnifiedItem> {
        val targets = if (sourceFilter != null) {
            providers.values.filter { it.getInfo().id == sourceFilter || sourceFilter == "all" }
        } else {
            providers.values.toList()
        }
        val results = mutableListOf<UnifiedItem>()
        for (p in targets) {
            if (!p.isAvailable()) continue
            p.search(query, emptyMap()).onSuccess { results.addAll(it) }
        }
        return results
    }

    suspend fun install(sourceId: String, itemId: String): Result<String> {
        val provider = providers[sourceId] ?: return Result.failure(Exception("未知集成�? $sourceId"))
        val detail = provider.getDetail(itemId).getOrElse {
            return Result.failure(Exception("未找到项�? $itemId"))
        }
        return provider.install(detail)
    }

    suspend fun uninstall(sourceId: String, installedId: String): Result<String> {
        val provider = providers[sourceId] ?: return Result.failure(Exception("未知集成�? $sourceId"))
        return provider.uninstall(installedId)
    }
}
