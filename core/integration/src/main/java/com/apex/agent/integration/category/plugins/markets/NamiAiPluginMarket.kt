package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 纳米 AI MCP 万能工具箱。
 *
 * - 内置 110+ 封装好的 MCP 技能（地图/代码/图像生成/数据库）
 * - 统一 SSE 接口，免费沙箱调用
 * - 无需单独申请各平台 API 密钥
 * - 地址：https://nami.ai/
 */
class NamiAiPluginMarket : IntegrationMarket {

    override val marketId = "nami_ai"
    override val displayName = "纳米 AI 工具箱"
    override val category = IntegrationCategory.PLUGINS
    override val description = "110+ MCP 技能，统一 SSE 接口，免费沙箱调用"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("地图", "代码", "图像生成", "数据库", "翻译", "天气", "搜索", "国内服务")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "nami:map",
            name = "地图服务工具",
            description = "高德/百度地图，路径规划/POI/逆地理编码",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://nami.ai/mcp/map",
            tags = listOf("地图", "高德", "国内"),
            rating = 4.5,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox", "region" to "cn")
        ),
        MarketItem(
            id = "nami:image-gen",
            name = "图像生成工具",
            description = "AI 图像生成，文生图/图生图",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://nami.ai/mcp/image-gen",
            tags = listOf("图像生成", "AI"),
            rating = 4.6,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox")
        ),
        MarketItem(
            id = "nami:code-tools",
            name = "代码工具集",
            description = "代码执行/格式化/审查/生成",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://nami.ai/mcp/code",
            tags = listOf("代码", "开发"),
            rating = 4.4,
            downloadCount = 6000,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox")
        ),
        MarketItem(
            id = "nami:database",
            name = "数据库工具",
            description = "SQL 执行/查询/表结构分析",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://nami.ai/mcp/database",
            tags = listOf("数据库", "SQL"),
            rating = 4.3,
            downloadCount = 4000,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox")
        ),
        MarketItem(
            id = "nami:translate",
            name = "翻译工具",
            description = "多语言翻译，支持 100+ 语言",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://nami.ai/mcp/translate",
            tags = listOf("翻译", "多语言"),
            rating = 4.5,
            downloadCount = 7000,
            verified = true,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox")
        ),
        MarketItem(
            id = "nami:weather",
            name = "天气查询工具",
            description = "实时天气/预报/历史天气",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://nami.ai/mcp/weather",
            tags = listOf("天气", "国内"),
            rating = 4.2,
            downloadCount = 3000,
            metadata = mapOf("transport" to "sse", "freeTier" to "sandbox", "region" to "cn")
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
