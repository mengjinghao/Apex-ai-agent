package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * HuggingFace 插件市场（Spaces / Datasets / Models 工具）。
 *
 * 除了模型，HuggingFace 还提供 Spaces（在线应用）和工具集。
 * - 地址：https://huggingface.co/
 */
class HuggingFacePluginMarket : IntegrationMarket {

    override val marketId = "hf_plugins"
    override val displayName = "HuggingFace Spaces"
    override val category = IntegrationCategory.PLUGINS
    override val description = "HuggingFace 在线应用和工具集，免费托管"
    override val iconUrl = "https://huggingface.co/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("图像", "语音", "NLP", "数据集", "可视化", "工具")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "hf:stable-diffusion-space",
            name = "Stable Diffusion WebUI",
            description = "免费在线图像生成，Stable Diffusion WebUI",
            author = "AUTOMATIC",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://huggingface.co/spaces/stabilityai/stable-diffusion",
            tags = listOf("图像", "生成", "免费"),
            rating = 4.8,
            downloadCount = 30000,
            verified = true,
            metadata = mapOf("type" to "space", "free" to "true")
        ),
        MarketItem(
            id = "hf:whisper-space",
            name = "Whisper 语音转文字",
            description = "免费在线语音识别，多语言支持",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/spaces/openai/whisper",
            tags = listOf("语音", "ASR", "免费"),
            rating = 4.7,
            downloadCount = 20000,
            verified = true,
            metadata = mapOf("type" to "space")
        ),
        MarketItem(
            id = "hf:datasets-tools",
            name = "Datasets 工具集",
            description = "数据集加载/处理/分析工具",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/docs/datasets",
            tags = listOf("数据集", "工具"),
            rating = 4.5,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0")
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
