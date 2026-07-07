package com.apex.agent.integration.category.models.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Agnes AI 模型平台。
 *
 * 全球全模态永久免费模型平台。
 * - 免费：永久免费，无额度限制
 * - 全模态：文本/图像/语音/视频
 */
class AgnesAiModelMarket : IntegrationMarket {

    override val marketId = "agnes_ai"
    override val displayName = "Agnes AI"
    override val category = IntegrationCategory.MODEL_PLATFORMS
    override val description = "全球全模态永久免费模型平台，无额度限制"
    override val iconUrl = null
    override val requiresNetwork = true

    private val apiBase = "https://api.agnes.ai/v1"

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("文本", "图像生成", "图像理解", "语音合成", "语音识别", "视频", "多模态")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "agnes:text-pro",
            name = "Agnes Text Pro",
            description = "高质量文本生成模型，永久免费，无额度限制",
            author = "Agnes AI",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = apiBase,
            tags = listOf("文本", "免费", "无限制"),
            rating = 4.7,
            downloadCount = 25000,
            verified = true,
            metadata = mapOf(
                "endpoint" to apiBase,
                "freePolicy" to "permanent_free",
                "quota" to "unlimited"
            )
        ),
        MarketItem(
            id = "agnes:image-gen",
            name = "Agnes Image Gen",
            description = "AI 图像生成，支持多种风格，永久免费",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "$apiBase/image/generate",
            tags = listOf("图像生成", "免费"),
            rating = 4.6,
            downloadCount = 18000,
            verified = true,
            metadata = mapOf("endpoint" to "$apiBase/image/generate", "modality" to "image")
        ),
        MarketItem(
            id = "agnes:image-understand",
            name = "Agnes Image Understand",
            description = "图像理解，OCR/目标检测/场景描述",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "$apiBase/image/understand",
            tags = listOf("图像理解", "OCR", "免费"),
            rating = 4.5,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("endpoint" to "$apiBase/image/understand", "modality" to "image")
        ),
        MarketItem(
            id = "agnes:tts",
            name = "Agnes TTS",
            description = "语音合成，多语言/多音色，永久免费",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "$apiBase/audio/tts",
            tags = listOf("语音合成", "TTS", "免费"),
            rating = 4.4,
            downloadCount = 9000,
            verified = true,
            metadata = mapOf("endpoint" to "$apiBase/audio/tts", "modality" to "audio")
        ),
        MarketItem(
            id = "agnes:multimodal",
            name = "Agnes Multimodal",
            description = "全模态模型，文本+图像+语音统一处理",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "$apiBase/multimodal",
            tags = listOf("多模态", "免费", "无限制"),
            rating = 4.8,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("endpoint" to "$apiBase/multimodal", "modality" to "multimodal")
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
