package com.apex.agent.integration.category.models.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.category.models.BuiltinModelProviders
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 内置 LLM Provider 目录市场。
 *
 * 迁移自原 Apex-agent 的 ProviderProfile，包含 11 个主流 LLM 平台配置：
 * DeepSeek / Claude / OpenAI / 通义千问 / 智谱 GLM / Moonshot /
 * MiniMax / 百川 / Ollama(本地) / Agnes AI(永久免费)
 *
 * 业务侧可直接使用这些预设配置，无需手动填写 endpoint 和模型名。
 */
class BuiltinModelMarket : IntegrationMarket {

    override val marketId = "builtin_models"
    override val displayName = "内置模型平台目录"
    override val category = IntegrationCategory.MODEL_PLATFORMS
    override val description = "11 个主流 LLM 平台预设配置，无需网络即可浏览"
    override val iconUrl = null
    override val requiresNetwork = false  // 内置数据

    private val allItems by lazy { BuiltinModelProviders.toMarketItems(marketId) }

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = allItems.filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? {
        return allItems.find { it.id == itemId }
    }

    override suspend fun getCategories(): List<String> {
        return listOf("云端", "本地", "国内", "免费", "推理", "对话", "代码")
    }

    override suspend fun getFeatured(limit: Int): List<MarketItem> {
        // 推荐：免费 + 本地优先
        return allItems.sortedWith(
            compareByDescending<MarketItem> { it.metadata["freeQuota"]?.contains("永久免费") == true }
                .thenByDescending { it.rating }
        ).take(limit)
    }

    override suspend fun isAvailable(): Boolean = true

    private fun matchFilter(item: MarketItem, filter: MarketSearchFilter): Boolean {
        if (filter.query.isNotBlank() &&
            filter.query !in item.name &&
            filter.query !in item.description &&
            filter.query !in item.tags.joinToString()) {
            return false
        }
        if (filter.tags.isNotEmpty() && filter.tags.intersect(item.tags.toSet()).isEmpty()) return false
        if (filter.verifiedOnly && !item.verified) return false
        if (item.rating < filter.minRating) return false
        return true
    }
}
