package com.apex.agent.integration.category.plugins

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketRegistry
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 插件集成模块。
 *
 * 管理应用插件市场（文件处理、网络工具、系统工具等插件）。
 */
class PluginModule(
    private val installedManager: InstalledManager
) {

    val category: IntegrationCategory = IntegrationCategory.PLUGINS

    fun getMarkets(): List<IntegrationMarket> =
        MarketRegistry.getByCategory(IntegrationCategory.PLUGINS)

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
        require(item.category == IntegrationCategory.PLUGINS) {
            "Item category must be PLUGINS, got ${item.category}"
        }
        return installedManager.install(item, installPath)
    }

    fun uninstall(itemId: String): Boolean = installedManager.uninstall(itemId)

    fun getInstalled(): List<InstalledItem> =
        installedManager.getByCategory(IntegrationCategory.PLUGINS)

    fun getUpdatable(): List<InstalledItem> = getInstalled().filter { it.hasUpdate }

    fun setEnabled(itemId: String, enabled: Boolean): Boolean =
        installedManager.setEnabled(itemId, enabled)

    /**
     * 获取插件的入口点信息。
     *
     * 插件安装后需要知道其入口类/方法，此方法从 metadata 中提取。
     */
    fun getEntryPoint(itemId: String): String? {
        val item = installedManager.get(itemId) ?: return null
        return item.metadata["entryPoint"]
    }
}
