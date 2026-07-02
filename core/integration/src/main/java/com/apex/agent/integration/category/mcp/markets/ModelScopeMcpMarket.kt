package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 阿里魔搭 ModelScope MCP 广场。
 *
 * - 免费：国内直连，社区 MCP 全部开源免费本地运行；部分云端托管每日免费额度
 * - 特色：中文场景专属插件（天眼查/高德地图/国内文档解析/阿里生态工具）
 * - 地址：https://www.modelscope.cn/mcp
 */
class ModelScopeMcpMarket : IntegrationMarket {

    override val marketId = "modelscope_mcp"
    override val displayName = "魔搭 MCP 广场"
    override val category = IntegrationCategory.MCP
    override val description = "阿里国内免费 MCP 聚合，中文场景专属，无需翻墙"
    override val iconUrl = "https://modelscope.cn/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("天眼查", "高德地图", "文档解析", "阿里OSS", "通义搜索", "飞书", "微信")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "modelscope:gaode-map",
            name = "高德地图 MCP",
            description = "高德地图服务，地理位置/路径规划/POI 搜索",
            author = "阿里",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelscope/mcp-gaode",
            tags = listOf("高德地图", "地图", "国内"),
            rating = 4.6,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:tianyancha",
            name = "天眼查 MCP",
            description = "企业信息查询，工商数据/股东/法人",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelscope/mcp-tianyancha",
            tags = listOf("天眼查", "企业查询", "国内"),
            rating = 4.4,
            downloadCount = 5000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:doc-parse",
            name = "文档解析 MCP",
            description = "国内文档解析（PDF/Word/Excel），中文 OCR",
            version = "1.2.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelscope/mcp-doc-parse",
            tags = listOf("文档解析", "OCR", "国内"),
            rating = 4.5,
            downloadCount = 7000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:oss",
            name = "阿里 OSS MCP",
            description = "阿里云对象存储服务，文件上传/下载/管理",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelscope/mcp-oss",
            tags = listOf("阿里OSS", "云存储", "国内"),
            rating = 4.3,
            downloadCount = 4000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
        ),
        MarketItem(
            id = "modelscope:tongyi-search",
            name = "通义搜索 MCP",
            description = "通义千问驱动的智能搜索",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelscope/mcp-tongyi-search",
            tags = listOf("通义搜索", "搜索", "国内"),
            rating = 4.5,
            downloadCount = 6000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "region" to "cn")
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
