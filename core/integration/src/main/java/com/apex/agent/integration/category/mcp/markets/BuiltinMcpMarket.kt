package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.category.mcp.BuiltinMcpCatalog
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 内置精选 MCP 服务器市场。
 *
 * 迁移自原 Apex-agent 的 MCPCatalog，包含 24 个 Anthropic 官方维护和
 * 社区热门的标准 MCP 服务，无需外部 API 即可浏览。
 *
 * 这些是经过验证的高质量 MCP 服务器，可直接 npx 启动：
 * - Filesystem / Git / Fetch / SQLite / PostgreSQL
 * - Brave Search / Google Maps / Memory / Sequential Thinking
 * - Slack / GitHub / Notion / Puppeteer / Playwright
 * - Apple MCP / HomeAssistant / Sentry / EverArt / Time / Todoist
 */
class BuiltinMcpMarket : IntegrationMarket {

    override val marketId = "builtin_mcp"
    override val displayName = "精选 MCP 目录"
    override val category = IntegrationCategory.MCP
    override val description = "24 个官方维护和社区热门 MCP 服务器，无需网络即可浏览"
    override val iconUrl = null
    override val requiresNetwork = false  // 内置数据，无需网络

    private val allItems by lazy { BuiltinMcpCatalog.toMarketItems(marketId) }

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = allItems.filter { matchFilter(it, filter) }
        return MarketSearchResult(
            items = items,
            totalCount = items.size,
            hasMore = false
        )
    }

    override suspend fun getItem(itemId: String): MarketItem? {
        return allItems.find { it.id == itemId }
    }

    override suspend fun getCategories(): List<String> {
        return BuiltinMcpCatalog.entries.map { it.category.displayName }.distinct()
    }

    override suspend fun getFeatured(limit: Int): List<MarketItem> {
        return allItems.sortedByDescending { it.rating }.take(limit)
    }

    override suspend fun isAvailable(): Boolean = true  // 始终可用

    private fun matchFilter(item: MarketItem, filter: MarketSearchFilter): Boolean {
        if (filter.query.isNotBlank() &&
            filter.query !in item.name &&
            filter.query !in item.description &&
            filter.query !in item.tags.joinToString()) {
            return false
        }
        if (filter.tags.isNotEmpty() && filter.tags.intersect(item.tags.toSet()).isEmpty()) return false
        if (filter.verifiedOnly && !item.verified) return false
        if (item.rating < filter.minRating) return false
        return true
    }
}
