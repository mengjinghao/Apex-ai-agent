package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * mcp.so MCP 官方枢纽广场。
 *
 * - 资源：10000+ MCP Server
 * - 免费：社区 MCP 全部免费本地运行；少量云端托管服务有免费试用额度
 * - 特色：区分 STDIO 本地 / SSE 云端两种接入模式
 * - 地址：https://mcp.so/
 */
class McpSoMarket : IntegrationMarket {

    override val marketId = "mcp_so"
    override val displayName = "mcp.so"
    override val category = IntegrationCategory.MCP
    override val description = "MCP 官方枢纽广场，10000+ MCP Server，支持 STDIO/SSE 双模式"
    override val iconUrl = "https://mcp.so/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("网页搜索", "文件", "浏览器自动化", "地图", "代码检索", "数据库", "云服务")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "mcpso:web-search",
            name = "Free Web Search",
            description = "内置免费网页搜索，无需 API Key",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "npx @mcp/web-search",
            tags = listOf("网页搜索", "搜索", "免费"),
            rating = 4.5,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("transport" to "stdio")
        ),
        MarketItem(
            id = "mcpso:browser-auto",
            name = "Browser Automation",
            description = "浏览器自动化 MCP，支持表单填写、数据提取",
            version = "2.1.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcp/browser-automation",
            tags = listOf("浏览器自动化"),
            rating = 4.6,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("transport" to "stdio")
        ),
        MarketItem(
            id = "mcpso:map",
            name = "Maps MCP",
            description = "地图服务 MCP，地理位置查询",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcp/maps",
            tags = listOf("地图"),
            rating = 4.3,
            downloadCount = 8000,
            metadata = mapOf("transport" to "sse", "endpoint" to "https://mcp.so/sse/maps")
        ),
        MarketItem(
            id = "mcpso:code-search",
            name = "Code Search",
            description = "代码检索 MCP，支持语义搜索",
            version = "1.1.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcp/code-search",
            tags = listOf("代码检索"),
            rating = 4.7,
            downloadCount = 10000,
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
