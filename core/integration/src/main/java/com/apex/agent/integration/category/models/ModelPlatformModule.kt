package com.apex.agent.integration.category.models

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketRegistry
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 模型平台集成模块。
 *
 * 管理 LLM 模型平台市场（DeepSeek、Claude、OpenAI、本地模型等）。
 *
 * 与其他模块的区别：
 * - 模型平台的"安装"通常是配置 API Key 和端点，而非下载文件
 * - 一个平台下可能有多个模型（如 OpenAI 有 GPT-4、GPT-3.5 等）
 */
class ModelPlatformModule(
    private val installedManager: InstalledManager
) {

    val category: IntegrationCategory = IntegrationCategory.MODEL_PLATFORMS

    fun getMarkets(): List<IntegrationMarket> =
        MarketRegistry.getByCategory(IntegrationCategory.MODEL_PLATFORMS)

    suspend fun searchAcrossMarkets(filter: MarketSearchFilter = MarketSearchFilter()): MarketSearchResult {
        val markets = getMarkets()
        if (markets.isEmpty()) return MarketSearchResult.EMPTY

        val allItems = mutableListOf<MarketItem>()
        for (market in markets) {
            try {
                if (market.isAvailable()) {
                    allItems.addAll(market.search(filter).items)
                }
            } catch (_: Exception) {}
        }

        return MarketSearchResult(
            items = allItems,
            totalCount = allItems.size,
            hasMore = false
        )
    }

    suspend fun searchInMarket(marketId: String, filter: MarketSearchFilter = MarketSearchFilter()): MarketSearchResult {
        return MarketRegistry.get(marketId)?.search(filter) ?: MarketSearchResult.EMPTY
    }

    /**
     * 安装（配置）模型平台。
     *
     * 模型平台的安装通常是配置 API Key 和端点信息。
     *
     * @param item 市场项
     * @param apiKey API Key
     * @param endpoint 自定义端点（可选）
     */
    fun install(item: MarketItem, apiKey: String, endpoint: String? = null): Boolean {
        require(item.category == IntegrationCategory.MODEL_PLATFORMS) {
            "Item category must be MODEL_PLATFORMS, got ${item.category}"
        }
        val itemWithConfig = item.copy(
            metadata = item.metadata + mapOf(
                "apiKey" to apiKey,
                "endpoint" to (endpoint ?: "")
            )
        )
        return installedManager.install(itemWithConfig)
    }

    fun uninstall(itemId: String): Boolean = installedManager.uninstall(itemId)

    fun getInstalled(): List<InstalledItem> =
        installedManager.getByCategory(IntegrationCategory.MODEL_PLATFORMS)

    fun getUpdatable(): List<InstalledItem> = getInstalled().filter { it.hasUpdate }

    fun setEnabled(itemId: String, enabled: Boolean): Boolean =
        installedManager.setEnabled(itemId, enabled)

    /**
     * 获取已配置的 API Key。
     */
    fun getApiKey(itemId: String): String? {
        val item = installedManager.get(itemId) ?: return null
        return item.metadata["apiKey"]
    }

    /**
     * 获取自定义端点。
     */
    fun getEndpoint(itemId: String): String? {
        val item = installedManager.get(itemId) ?: return null
        return item.metadata["endpoint"]?.takeIf { it.isNotBlank() }
    }

    /**
     * 更新 API Key。
     */
    fun updateApiKey(itemId: String, apiKey: String): Boolean {
        val item = installedManager.get(itemId) ?: return false
        val newMetadata = item.metadata.toMutableMap()
        newMetadata["apiKey"] = apiKey
        // 通过卸载重装方式更新（简化实现）
        installedManager.uninstall(itemId)
        return installedManager.install(
            MarketItem(
                id = item.id,
                name = item.name,
                description = "",
                version = item.installedVersion,
                category = IntegrationCategory.MODEL_PLATFORMS,
                marketId = item.marketId,
                downloadUrl = "",
                metadata = newMetadata
            )
        )
    }
}
