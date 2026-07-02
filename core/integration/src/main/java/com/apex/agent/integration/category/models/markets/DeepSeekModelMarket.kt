package com.apex.agent.integration.category.models.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * DeepSeek 模型平台。
 *
 * 高性价比 API，深度推理能力。
 * - 地址：https://platform.deepseek.com/
 * - 免费额度：新用户赠送
 */
class DeepSeekModelMarket : IntegrationMarket {

    override val marketId = "deepseek"
    override val displayName = "DeepSeek"
    override val category = IntegrationCategory.MODEL_PLATFORMS
    override val description = "高性价比 LLM API，深度推理能力"
    override val iconUrl = "https://platform.deepseek.com/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> = listOf("推理", "对话", "代码", "数学")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "deepseek:chat",
            name = "DeepSeek Chat",
            description = "通用对话模型，高性价比",
            author = "DeepSeek",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://api.deepseek.com/v1",
            tags = listOf("对话", "性价比"),
            rating = 4.7,
            downloadCount = 40000,
            verified = true,
            metadata = mapOf("endpoint" to "https://api.deepseek.com/v1", "model" to "deepseek-chat")
        ),
        MarketItem(
            id = "deepseek:reasoner",
            name = "DeepSeek Reasoner",
            description = "深度推理模型，链式思考",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://api.deepseek.com/v1",
            tags = listOf("推理", "CoT"),
            rating = 4.9,
            downloadCount = 35000,
            verified = true,
            metadata = mapOf("endpoint" to "https://api.deepseek.com/v1", "model" to "deepseek-reasoner")
        ),
        MarketItem(
            id = "deepseek:coder",
            name = "DeepSeek Coder",
            description = "代码生成专用模型",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://api.deepseek.com/v1",
            tags = listOf("代码"),
            rating = 4.8,
            downloadCount = 30000,
            verified = true,
            metadata = mapOf("endpoint" to "https://api.deepseek.com/v1", "model" to "deepseek-coder")
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
