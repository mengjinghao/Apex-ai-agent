package com.apex.agent.integration.category.skills

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketRegistry
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Skills 集成模块。
 *
 * 技能市场模块，管理技能类集成的发现、安装、启用。
 *
 * 该模块聚合了 [IntegrationCategory.SKILLS] 分类下的所有市场，
 * 提供统一的搜索和安装入口。
 */
class SkillModule(
    private val installedManager: InstalledManager
) {

    /** 此模块的分类。 */
    val category: IntegrationCategory = IntegrationCategory.SKILLS

    /**
     * 获取所有技能市场。
     */
    fun getMarkets(): List<IntegrationMarket> {
        return MarketRegistry.getByCategory(IntegrationCategory.SKILLS)
    }

    /**
     * 跨市场搜索技能。
     *
     * 在所有技能市场中搜索，合并结果。
     *
     * @param filter 搜索过滤
     * @return 合并后的搜索结果
     */
    suspend fun searchAcrossMarkets(filter: MarketSearchFilter = MarketSearchFilter()): MarketSearchResult {
        val markets = getMarkets()
        if (markets.isEmpty()) return MarketSearchResult.EMPTY

        val allItems = mutableListOf<MarketItem>()
        for (market in markets) {
            try {
                if (market.isAvailable()) {
                    val result = market.search(filter)
                    allItems.addAll(result.items)
                }
            } catch (_: Exception) {
                // 单个市场搜索失败不影响其他
            }
        }

        // 排序
        val sorted = when (filter.sortBy) {
            com.apex.agent.integration.market.SortBy.POPULARITY ->
                allItems.sortedByDescending { it.downloadCount }
            com.apex.agent.integration.market.SortBy.RATING ->
                allItems.sortedByDescending { it.rating }
            com.apex.agent.integration.market.SortBy.NEWEST ->
                allItems.sortedByDescending { it.metadata["createdAt"]?.toLongOrNull() ?: 0L }
            com.apex.agent.integration.market.SortBy.NAME ->
                allItems.sortedBy { it.name }
            com.apex.agent.integration.market.SortBy.DOWNLOAD_SIZE ->
                allItems.sortedBy { it.downloadSizeBytes }
        }

        // 分页
        val startIndex = (filter.page - 1) * filter.pageSize
        val pageItems = if (startIndex < sorted.size) {
            sorted.subList(startIndex, minOf(startIndex + filter.pageSize, sorted.size))
        } else {
            emptyList()
        }

        return MarketSearchResult(
            items = pageItems,
            totalCount = sorted.size,
            page = filter.page,
            pageSize = filter.pageSize,
            hasMore = startIndex + filter.pageSize < sorted.size
        )
    }

    /**
     * 在指定市场搜索。
     */
    suspend fun searchInMarket(marketId: String, filter: MarketSearchFilter = MarketSearchFilter()): MarketSearchResult {
        val market = MarketRegistry.get(marketId)
            ?: return MarketSearchResult.EMPTY
        return market.search(filter)
    }

    /**
     * 安装技能。
     */
    fun install(item: MarketItem, installPath: String? = null): Boolean {
        require(item.category == IntegrationCategory.SKILLS) {
            "Item category must be SKILLS, got ${item.category}"
        }
        return installedManager.install(item, installPath)
    }

    /**
     * 卸载技能。
     */
    fun uninstall(itemId: String): Boolean {
        return installedManager.uninstall(itemId)
    }

    /**
     * 获取已安装的技能。
     */
    fun getInstalled(): List<com.apex.agent.integration.installed.InstalledItem> {
        return installedManager.getByCategory(IntegrationCategory.SKILLS)
    }

    /**
     * 获取可更新的技能。
     */
    fun getUpdatable(): List<com.apex.agent.integration.installed.InstalledItem> {
        return installedManager.getByCategory(IntegrationCategory.SKILLS).filter { it.hasUpdate }
    }

    /**
     * 启用/禁用技能。
     */
    fun setEnabled(itemId: String, enabled: Boolean): Boolean {
        return installedManager.setEnabled(itemId, enabled)
    }
}
