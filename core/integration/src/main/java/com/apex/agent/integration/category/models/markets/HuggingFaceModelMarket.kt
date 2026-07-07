package com.apex.agent.integration.category.models.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * HuggingFace 模型平台。
 *
 * 全球最大的开源模型社区。
 * - 地址：https://huggingface.co/
 * - 特色：海量开源模型、Transformers 生态、免费推理 API
 */
class HuggingFaceModelMarket : IntegrationMarket {

    override val marketId = "huggingface"
    override val displayName = "HuggingFace"
    override val category = IntegrationCategory.MODEL_PLATFORMS
    override val description = "全球最大开源模型社区，海量免费模型"
    override val iconUrl = "https://huggingface.co/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("NLP", "CV", "语音", "多模态", "LLM", "Embedding", "翻译")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "hf:llama-3.3-70b",
            name = "Llama 3.3 70B",
            description = "Meta 开源大模型，700 亿参数",
            author = "Meta",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://huggingface.co/meta-llama/Llama-3.3-70B",
            tags = listOf("LLM", "开源", "本地部署"),
            rating = 4.8,
            downloadCount = 80000,
            verified = true,
            metadata = mapOf("license" to "Llama-3.3", "modality" to "text")
        ),
        MarketItem(
            id = "hf:mistral-7b",
            name = "Mistral 7B",
            description = "轻量高效开源模型，70 亿参数",
            author = "Mistral AI",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/mistralai/Mistral-7B",
            tags = listOf("LLM", "开源", "轻量"),
            rating = 4.7,
            downloadCount = 60000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0")
        ),
        MarketItem(
            id = "hf:bert-base",
            name = "BERT Base",
            description = "经典 NLP 模型，文本分类/NER",
            author = "Google",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/bert-base-uncased",
            tags = listOf("NLP", "Embedding", "开源"),
            rating = 4.5,
            downloadCount = 100000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0")
        ),
        MarketItem(
            id = "hf:whisper",
            name = "Whisper",
            description = "OpenAI 开源语音识别模型，多语言",
            author = "OpenAI",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/openai/whisper-large-v3",
            tags = listOf("语音", "ASR", "开源"),
            rating = 4.8,
            downloadCount = 50000,
            verified = true,
            metadata = mapOf("modality" to "audio", "license" to "MIT")
        ),
        MarketItem(
            id = "hf:sentence-transformers",
            name = "Sentence Transformers",
            description = "句子向量 Embedding 模型",
            author = "SBERT",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2",
            tags = listOf("Embedding", "向量", "开源"),
            rating = 4.6,
            downloadCount = 45000,
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
