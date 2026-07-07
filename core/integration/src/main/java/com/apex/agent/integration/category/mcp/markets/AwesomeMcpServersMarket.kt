package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * awesome-mcp-servers 开源仓库。
 *
 * GitHub 37k+ Star，3000+ 分类 MCP，全部开源免费自托管。
 * 地址：https://github.com/punkpeye/awesome-mcp-servers
 */
class AwesomeMcpServersMarket : IntegrationMarket {

    override val marketId = "awesome_mcp"
    override val displayName = "awesome-mcp-servers"
    override val category = IntegrationCategory.MCP
    override val description = "GitHub 37k Star 开源仓库，3000+ 社区免费 MCP"
    override val iconUrl = "https://github.com/favicon.ico"
    override val requiresNetwork = true

    private val repoUrl = "https://github.com/punkpeye/awesome-mcp-servers"

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("搜索", "数据库", "办公", "云", "代码", "浏览器", "邮件", "智能家居")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "awesome:apple-mcp",
            name = "Apple MCP",
            description = "macOS 通讯录/日历/提醒事项，完全离线免费",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/dhravya/apple-mcp",
            tags = listOf("智能家居", "apple", "离线"),
            rating = 4.6,
            downloadCount = 9000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "license" to "MIT")
        ),
        MarketItem(
            id = "awesome:homeassistant",
            name = "HomeAssistant MCP",
            description = "智能家居控制，本地运行",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/joshuavial/homeassistant-mcp",
            tags = listOf("智能家居", "离线"),
            rating = 4.4,
            downloadCount = 5000,
            metadata = mapOf("transport" to "stdio", "license" to "MIT")
        ),
        MarketItem(
            id = "awesome:fast-filesystem",
            name = "Fast Filesystem MCP",
            description = "增强版文件 MCP，大文件流式处理、项目依赖分析",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/punkpeye/awesome-mcp-servers",
            tags = listOf("文件", "filesystem"),
            rating = 4.5,
            downloadCount = 7000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "license" to "MIT")
        ),
        MarketItem(
            id = "awesome:ref-mcp",
            name = "Ref MCP",
            description = "代码文档语义检索，智能读取项目注释与 API 文档",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/punkpeye/awesome-mcp-servers",
            tags = listOf("代码", "检索"),
            rating = 4.7,
            downloadCount = 6000,
            metadata = mapOf("transport" to "stdio", "license" to "MIT")
        ),
        MarketItem(
            id = "awesome:gsearch-free",
            name = "Free Google Search MCP",
            description = "无 key 免费谷歌搜索 MCP 服务",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/punkpeye/awesome-mcp-servers",
            tags = listOf("搜索", "google", "免费"),
            rating = 4.3,
            downloadCount = 8000,
            metadata = mapOf("transport" to "stdio", "license" to "MIT")
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
