package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Glama.ai MCP 索引库。
 *
 * - 规模：31000+ MCP 服务、22 万+ 独立工具技能
 * - 免费：浏览/本地安装/浏览器沙箱测试完全免费；远程 SSE 托管赠送基础免费调用额度
 * - 特色：统一 MCP 网关，多 Agent 共享一套技能池，支持 Streamable HTTP 远程接入
 * - 地址：https://glama.ai/
 */
class GlamaMcpMarket : IntegrationMarket {

    override val marketId = "glama"
    override val displayName = "Glama.ai"
    override val category = IntegrationCategory.MCP
    override val description = "31000+ MCP 服务，22 万+ 工具技能，统一 MCP 网关"
    override val iconUrl = "https://glama.ai/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("数据", "办公", "开发", "AI", "通信", "安全", "媒体")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "glama:notion",
            name = "Notion MCP",
            description = "Notion 文档管理，读写/搜索页面",
            version = "1.3.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.THIRD_PARTY_MARKET,
            downloadUrl = "https://glama.ai/mcp/notion",
            tags = listOf("办公", "notion", "文档"),
            rating = 4.7,
            downloadCount = 20000,
            verified = true,
            metadata = mapOf("transport" to "streamable-http")
        ),
        MarketItem(
            id = "glama:github",
            name = "GitHub MCP",
            description = "GitHub 仓库管理，Issue/PR/代码搜索",
            version = "2.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "https://glama.ai/mcp/github",
            tags = listOf("开发", "github", "代码"),
            rating = 4.8,
            downloadCount = 35000,
            verified = true,
            metadata = mapOf("transport" to "streamable-http")
        ),
        MarketItem(
            id = "glama:slack",
            name = "Slack MCP",
            description = "Slack 消息发送/读取，频道管理",
            version = "1.1.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "https://glama.ai/mcp/slack",
            tags = listOf("通信", "slack"),
            rating = 4.5,
            downloadCount = 12000,
            metadata = mapOf("transport" to "streamable-http")
        ),
        MarketItem(
            id = "glama:postgres",
            name = "PostgreSQL MCP",
            description = "PostgreSQL 数据库查询 MCP",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @glama/postgres-mcp",
            tags = listOf("数据", "postgres", "数据库"),
            rating = 4.6,
            downloadCount = 9000,
            verified = true,
            metadata = mapOf("transport" to "stdio")
        )
    )

    private fun matchFilter(item: MarketItem, filter: MarketSearchFilter): Boolean {
        if (filter.query.isNotBlank() && filter.query !in item.name && filter.query !in item.description) return false
        if (filter.tags.isNotEmpty() && filter.tags.intersect(item.tags.toSet()).isEmpty()) return false
        if (filter.verifiedOnly && !item.verified) return false
        if (item.rating < filter.minRating) return false
        return true
    }
}
