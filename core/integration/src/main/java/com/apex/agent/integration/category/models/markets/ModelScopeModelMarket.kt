package com.apex.agent.integration.category.models.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 阿里魔搭 ModelScope 模型平台。
 *
 * 国内最大的开源模型社区，提供海量免费模型下载和在线推理。
 * - 地址：https://www.modelscope.cn/
 * - 特色：国内直连、中文模型丰富、免费在线推理额度
 */
class ModelScopeModelMarket : IntegrationMarket {

    override val marketId = "modelscope_models"
    override val displayName = "魔搭 ModelScope"
    override val category = IntegrationCategory.MODEL_PLATFORMS
    override val description = "阿里魔搭社区，国内最大开源模型平台，免费在线推理"
    override val iconUrl = "https://modelscope.cn/favicon.ico"
    override val requiresNetwork = true

    private val apiBase = "https://www.modelscope.cn/api"

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("通义千问", "百川", "智谱GLM", "书生", "语音", "图像", "视频", "多模态")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "modelscope:qwen-max",
            name = "通义千问 Max",
            description = "阿里最强模型，1000 亿参数，多模态理解",
            author = "阿里",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://www.modelscope.cn/models/qwen/Qwen-Max",
            tags = listOf("通义千问", "多模态", "国内"),
            rating = 4.8,
            downloadCount = 50000,
            verified = true,
            metadata = mapOf(
                "endpoint" to "https://dashscope.aliyuncs.com/api/v1",
                "freeQuota" to "100000 tokens/月",
                "region" to "cn"
            )
        ),
        MarketItem(
            id = "modelscope:qwen-72b",
            name = "通义千问 72B",
            description = "720 亿参数大模型，开源可本地部署",
            author = "阿里",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://www.modelscope.cn/models/qwen/Qwen-72B",
            tags = listOf("通义千问", "开源", "本地部署"),
            rating = 4.7,
            downloadCount = 30000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:baichuan2-13b",
            name = "百川 2 13B",
            description = "百川智能 130 亿参数模型，中文能力优秀",
            author = "百川智能",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://www.modelscope.cn/models/baichuan-inc/Baichuan2-13B",
            tags = listOf("百川", "开源", "中文"),
            rating = 4.5,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:chatglm3-6b",
            name = "智谱 GLM-3 6B",
            description = "智谱 AI 开源模型，支持工具调用",
            author = "智谱AI",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://www.modelscope.cn/models/ZhipuAI/chatglm3-6b",
            tags = listOf("智谱GLM", "开源", "工具调用"),
            rating = 4.6,
            downloadCount = 20000,
            verified = true,
            metadata = mapOf("license" to "Apache-2.0", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:sensevoice",
            name = "SenseVoice 语音识别",
            description = "阿里语音识别模型，支持多语言",
            author = "阿里",
            version = "1.0.0",
            category = IntegrationCategory.MODEL_PLATFORMS,
            marketId = marketId,
            downloadUrl = "https://www.modelscope.cn/models/iic/SenseVoiceSmall",
            tags = listOf("语音", "ASR", "多语言"),
            rating = 4.4,
            downloadCount = 10000,
            verified = true,
            metadata = mapOf("modality" to "audio", "region" to "cn")
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
