package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Composio 第三方工具聚合平台。
 *
 * - 免费层：每日有限次调用（Notion/GitHub/飞书/Google 套件/钉钉）
 * - 特色：自动生成标准 MCP 服务接入 Agent，不用单独申请 API Key
 * - OAuth 托管：统一管理第三方工具密钥
 * - 地址：https://composio.dev/
 */
class ComposioPluginMarket : IntegrationMarket {

    override val marketId = "composio"
    override val displayName = "Composio"
    override val category = IntegrationCategory.PLUGINS
    override val description = "第三方工具聚合，OAuth 托管，免费层每日调用"
    override val iconUrl = "https://composio.dev/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("Notion", "GitHub", "飞书", "Google", "钉钉", "Slack", "Jira", "Linear")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "composio:notion",
            name = "Notion 集成",
            description = "Notion 文档管理，读写/搜索页面，免费层每日调用",
            author = "Composio",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.THIRD_PARTY_MARKET,
            downloadUrl = "https://composio.dev/tools/notion",
            tags = listOf("Notion", "办公", "OAuth"),
            rating = 4.6,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("freeTier" to "daily_quota", "oauth" to "managed")
        ),
        MarketItem(
            id = "composio:google-suite",
            name = "Google 套件集成",
            description = "Gmail/Drive/Calendar/Docs 统一接入",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://composio.dev/tools/google",
            tags = listOf("Google", "办公", "OAuth"),
            rating = 4.7,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("freeTier" to "daily_quota", "oauth" to "managed")
        ),
        MarketItem(
            id = "composio:feishu",
            name = "飞书集成",
            description = "飞书消息/文档/日历，OAuth 托管",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://composio.dev/tools/feishu",
            tags = listOf("飞书", "办公", "国内"),
            rating = 4.5,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("freeTier" to "daily_quota", "oauth" to "managed", "region" to "cn")
        ),
        MarketItem(
            id = "composio:github",
            name = "GitHub 集成",
            description = "GitHub Issue/PR/仓库管理，OAuth 托管",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://composio.dev/tools/github",
            tags = listOf("GitHub", "开发", "OAuth"),
            rating = 4.7,
            downloadCount = 10000,
            verified = true,
            metadata = mapOf("freeTier" to "daily_quota", "oauth" to "managed")
        ),
        MarketItem(
            id = "composio:slack",
            name = "Slack 集成",
            description = "Slack 消息发送/读取，频道管理",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://composio.dev/tools/slack",
            tags = listOf("Slack", "通信", "OAuth"),
            rating = 4.4,
            downloadCount = 6000,
            metadata = mapOf("freeTier" to "daily_quota", "oauth" to "managed")
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
