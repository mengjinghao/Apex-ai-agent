package com.apex.agent.integration.category.mcp

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketRegistry
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * MCP 集成模块。
 *
 * 管理 MCP（Model Context Protocol）服务器市场的发现、安装、配置。
 */
class McpModule(
    private val installedManager: InstalledManager
) {

    val category: IntegrationCategory = IntegrationCategory.MCP

    fun getMarkets(): List<IntegrationMarket> =
        MarketRegistry.getByCategory(IntegrationCategory.MCP)

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

    fun install(item: MarketItem, installPath: String? = null): Boolean {
        require(item.category == IntegrationCategory.MCP) {
            "Item category must be MCP, got ${item.category}"
        }
        return installedManager.install(item, installPath)
    }

    fun uninstall(itemId: String): Boolean = installedManager.uninstall(itemId)

    fun getInstalled(): List<InstalledItem> =
        installedManager.getByCategory(IntegrationCategory.MCP)

    fun getUpdatable(): List<InstalledItem> = getInstalled().filter { it.hasUpdate }

    fun setEnabled(itemId: String, enabled: Boolean): Boolean =
        installedManager.setEnabled(itemId, enabled)

    /**
     * 获取 MCP 服务器的连接配置。
     *
     * MCP 服务器安装后通常需要连接配置（端口、命令、参数等），
     * 此方法从已安装项的 metadata 中提取配置。
     */
    fun getConnectionConfig(itemId: String): Map<String, String>? {
        val item = installedManager.get(itemId) ?: return null
        return item.metadata
    }
}
