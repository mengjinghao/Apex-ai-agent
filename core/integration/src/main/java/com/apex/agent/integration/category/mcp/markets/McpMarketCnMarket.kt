package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * mcp-market.cn（MCP 星球）。
 *
 * 国内中文 MCP 聚合，5.4 万+ 国产适配 MCP，全程免费，无需翻墙。
 */
class McpMarketCnMarket : IntegrationMarket {

    override val marketId = "mcp_market_cn"
    override val displayName = "MCP 星球"
    override val category = IntegrationCategory.MCP
    override val description = "国内中文 MCP 聚合，5.4 万+ 国产适配 MCP，无需翻墙"
    override val iconUrl = "https://mcp-market.cn/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("飞书", "微信", "钉钉", "高德", "天眼查", "国内文档", "通义", "文心")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "mcpcn:feishu",
            name = "飞书 MCP",
            description = "飞书消息/文档/日历集成",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcpcn/feishu",
            tags = listOf("飞书", "办公", "国内"),
            rating = 4.5,
            downloadCount = 6000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "mcpcn:wechat",
            name = "微信 MCP",
            description = "微信公众号/小程序 API 集成",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcpcn/wechat",
            tags = listOf("微信", "国内"),
            rating = 4.3,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "mcpcn:dingtalk",
            name = "钉钉 MCP",
            description = "钉钉消息/考勤/审批集成",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcpcn/dingtalk",
            tags = listOf("钉钉", "办公", "国内"),
            rating = 4.2,
            downloadCount = 4000,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "mcpcn:wenxin",
            name = "文心一言 MCP",
            description = "百度文心一言大模型接入",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @mcpcn/wenxin",
            tags = listOf("文心", "AI", "国内"),
            rating = 4.4,
            downloadCount = 5000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
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
