package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * AISkillStore.io 远程 MCP 技能市场。
 *
 * - 特色：提供远程 MCP 公共端点，无需本地启动进程，直接填入 Agent 配置即可
 * - 免费：每日基础调用额度，标准 Streamable HTTP 传输
 * - 地址：https://aiskillstore.io/
 */
class AiSkillStoreMcpMarket : IntegrationMarket {

    override val marketId = "aiskillstore"
    override val displayName = "AISkillStore.io"
    override val category = IntegrationCategory.MCP
    override val description = "远程 MCP 公共端点，无需本地进程，直接 URL 接入"
    override val iconUrl = "https://aiskillstore.io/favicon.ico"
    override val requiresNetwork = true

    private val endpoint = "https://aiskillstore.io/mcp"

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("通用工具", "AI", "数据", "媒体", "开发")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "aiskillstore:universal",
            name = "Universal MCP Endpoint",
            description = "统一远程 MCP 端点，含数万技能，无需本地部署",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = endpoint,
            tags = listOf("通用工具", "远程", "免部署"),
            rating = 4.5,
            downloadCount = 18000,
            verified = true,
            metadata = mapOf(
                "transport" to "streamable-http",
                "url" to endpoint,
                "freeTier" to "daily_quota"
            )
        ),
        MarketItem(
            id = "aiskillstore:ai-tools",
            name = "AI Tools MCP",
            description = "AI 工具集（文本生成/摘要/翻译/代码生成）",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "$endpoint/ai",
            tags = listOf("AI", "远程"),
            rating = 4.6,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("transport" to "streamable-http", "url" to "$endpoint/ai")
        ),
        MarketItem(
            id = "aiskillstore:data-tools",
            name = "Data Tools MCP",
            description = "数据处理工具（CSV/JSON/Excel 解析转换）",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "$endpoint/data",
            tags = listOf("数据", "远程"),
            rating = 4.4,
            downloadCount = 8000,
            metadata = mapOf("transport" to "streamable-http", "url" to "$endpoint/data")
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
