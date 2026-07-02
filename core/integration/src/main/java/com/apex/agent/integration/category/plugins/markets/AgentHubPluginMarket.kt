package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * AgentHub MCP+Skill 双聚合平台。
 *
 * - 完全免费
 * - 收录 11800+ MCP、5800+ Agent 自定义技能
 * - 统一 Manifest 标准
 * - 内置适配微内核 Agent 的配置模板
 * - 支持批量导入多 MCP 服务
 * - 地址：https://agenthub.io/
 */
class AgentHubPluginMarket : IntegrationMarket {

    override val marketId = "agenthub"
    override val displayName = "AgentHub"
    override val category = IntegrationCategory.PLUGINS
    override val description = "MCP+Skill 双聚合，11800+ MCP，5800+ Agent 技能"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("MCP 服务", "Agent 技能", "Manifest 模板", "批量导入", "微内核适配")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "agenthub:mcp-bundle",
            name = "MCP 服务合集",
            description = "11800+ MCP 服务，统一 Manifest 标准，批量导入",
            author = "AgentHub",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://agenthub.io/mcp-bundle",
            tags = listOf("MCP 服务", "批量导入", "Manifest"),
            rating = 4.7,
            downloadCount = 20000,
            verified = true,
            metadata = mapOf("count" to "11800+", "format" to "manifest")
        ),
        MarketItem(
            id = "agenthub:skill-bundle",
            name = "Agent 技能合集",
            description = "5800+ Agent 自定义技能，适配多 Agent 框架",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://agenthub.io/skill-bundle",
            tags = listOf("Agent 技能"),
            rating = 4.6,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("count" to "5800+", "format" to "skill")
        ),
        MarketItem(
            id = "agenthub:apex-template",
            name = "Apex-Agent 配置模板",
            description = "内置适配 Apex-Agent 微内核的配置模板，一键导入",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://agenthub.io/templates/apex",
            tags = listOf("微内核适配", "Apex-Agent", "模板"),
            rating = 4.8,
            downloadCount = 10000,
            verified = true,
            metadata = mapOf("target" to "apex-agent", "format" to "template")
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
