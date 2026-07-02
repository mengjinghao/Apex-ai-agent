package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * AIBase MCP 中文资源站。
 *
 * - 1.3 万+ MCP 仓库镜像
 * - 全文档汉化
 * - 免费下载部署脚本
 * - 适合国内自研 Agent 开发
 */
class AiBasePluginMarket : IntegrationMarket {

    override val marketId = "aibase"
    override val displayName = "AIBase MCP"
    override val category = IntegrationCategory.PLUGINS
    override val description = "1.3 万+ MCP 中文资源站，全文档汉化，免费部署脚本"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("中文文档", "部署脚本", "国内镜像", "汉化教程", "新手入门")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "aibase:mcp-mirror",
            name = "MCP 仓库中文镜像",
            description = "1.3 万+ MCP 仓库镜像，国内快速下载",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://aibase.cn/mcp-mirror",
            tags = listOf("中文文档", "国内镜像"),
            rating = 4.5,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("count" to "13000+", "region" to "cn", "localized" to "true")
        ),
        MarketItem(
            id = "aibase:deploy-scripts",
            name = "MCP 部署脚本集",
            description = "一键部署 MCP 服务脚本，中文教程",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://aibase.cn/deploy-scripts",
            tags = listOf("部署脚本", "汉化教程", "国内"),
            rating = 4.4,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("region" to "cn", "localized" to "true")
        ),
        MarketItem(
            id = "aibase:beginner-guide",
            name = "MCP 新手入门包",
            description = "从零开始使用 MCP，含中文视频教程",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://aibase.cn/beginner",
            tags = listOf("新手入门", "汉化教程"),
            rating = 4.6,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("region" to "cn", "level" to "beginner")
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
