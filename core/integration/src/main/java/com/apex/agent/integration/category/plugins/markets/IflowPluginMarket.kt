package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 阿里 Iflow 心流 MCP 库。
 *
 * - 平台内置免费 MCP 组件
 * - 支持拖拽封装技能
 * - 导出标准 MCP 服务对接私有 Agent
 * - 地址：https://iflow.cn/
 */
class IflowPluginMarket : IntegrationMarket {

    override val marketId = "iflow"
    override val displayName = "阿里 Iflow 心流"
    override val category = IntegrationCategory.PLUGINS
    override val description = "阿里 Iflow 心流 MCP 库，拖拽封装技能，导出标准 MCP"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("阿里生态", "数据流", "AI", "自动化", "国内服务")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "iflow:oss-mcp",
            name = "阿里 OSS MCP",
            description = "阿里云对象存储，拖拽式封装为 MCP 服务",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://iflow.cn/mcp/oss",
            tags = listOf("阿里生态", "OSS", "国内"),
            rating = 4.4,
            downloadCount = 5000,
            verified = true,
            metadata = mapOf("region" to "cn", "platform" to "aliyun")
        ),
        MarketItem(
            id = "iflow:data-flow",
            name = "数据流 MCP",
            description = "数据清洗/转换/聚合，拖拽编排数据流",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://iflow.cn/mcp/data-flow",
            tags = listOf("数据流", "自动化", "国内"),
            rating = 4.5,
            downloadCount = 4000,
            verified = true,
            metadata = mapOf("region" to "cn")
        ),
        MarketItem(
            id = "iflow:ai-workflow",
            name = "AI 工作流 MCP",
            description = "AI 任务编排，文本/图像/语音组合工作流",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://iflow.cn/mcp/ai-workflow",
            tags = listOf("AI", "自动化", "国内"),
            rating = 4.3,
            downloadCount = 3000,
            metadata = mapOf("region" to "cn")
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
