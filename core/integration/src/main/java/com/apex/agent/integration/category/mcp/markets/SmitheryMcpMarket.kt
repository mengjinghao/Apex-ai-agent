package com.apex.agent.integration.category.mcp.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Smithery.ai MCP 市场。
 *
 * 全球最主流的免费 MCP 商店，4000+ MCP 技能插件。
 *
 * - 免费政策：永久免费浏览、免费本地部署，无额度限制，仅付费托管云端实例
 * - 资源：4000+ MCP 技能插件（文件/爬虫/数据库/搜索/办公/代码/云服务）
 * - 适配：一键生成 Claude/Cursor/Apex-Agent 配置 JSON，npx 一键启动本地 MCP
 * - 地址：https://smithery.ai/
 */
class SmitheryMcpMarket : IntegrationMarket {

    override val marketId = "smithery"
    override val displayName = "Smithery.ai"
    override val category = IntegrationCategory.MCP
    override val description = "全球最主流免费 MCP 商店，4000+ MCP 技能插件"
    override val iconUrl = "https://smithery.ai/favicon.ico"
    override val requiresNetwork = true

    private val apiBase = "https://smithery.ai/api"

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        // 生产环境应调用 smithery.ai API
        // 此处返回内置示例数据，实际部署时替换为 HTTP 调用
        return MarketSearchResult(
            items = builtinItems().filter { matchFilter(it, filter) },
            totalCount = builtinItems().size,
            hasMore = false
        )
    }

    override suspend fun getItem(itemId: String): MarketItem? {
        return builtinItems().find { it.id == itemId }
    }

    override suspend fun getCategories(): List<String> {
        return listOf("文件", "爬虫", "数据库", "搜索", "办公", "代码", "云服务", "浏览器自动化")
    }

    override suspend fun isAvailable(): Boolean = true

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "smithery:filesystem",
            name = "Filesystem MCP",
            description = "本地文件读写、项目代码扫描，开发 Agent 必备",
            author = "@modelcontextprotocol",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "npx @modelcontextprotocol/server-filesystem",
            tags = listOf("文件", "filesystem", "本地"),
            rating = 4.8,
            downloadCount = 50000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "command" to "npx")
        ),
        MarketItem(
            id = "smithery:fetch",
            name = "Fetch MCP",
            description = "网页抓取、文章解析，获取在线内容",
            author = "@modelcontextprotocol",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "npx @modelcontextprotocol/server-fetch",
            tags = listOf("爬虫", "fetch", "网页"),
            rating = 4.7,
            downloadCount = 35000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "command" to "npx")
        ),
        MarketItem(
            id = "smithery:git",
            name = "Git MCP",
            description = "仓库读取、提交、分支管理",
            author = "@modelcontextprotocol",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "npx @modelcontextprotocol/server-git",
            tags = listOf("代码", "git", "版本控制"),
            rating = 4.6,
            downloadCount = 28000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "command" to "npx")
        ),
        MarketItem(
            id = "smithery:playwright",
            name = "Playwright MCP",
            description = "AI 操控浏览器、爬虫、表单填写，本地运行无调用费",
            author = "@executeautomation",
            version = "1.2.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            sourceType = IntegrationSourceType.THIRD_PARTY_MARKET,
            downloadUrl = "npx @executeautomation/playwright-mcp-server",
            tags = listOf("浏览器自动化", "playwright", "爬虫"),
            rating = 4.9,
            downloadCount = 42000,
            verified = true,
            metadata = mapOf("transport" to "stdio", "command" to "npx")
        ),
        MarketItem(
            id = "smithery:sqlite",
            name = "SQLite MCP",
            description = "SQLite 数据库查询 MCP",
            author = "@modelcontextprotocol",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelcontextprotocol/server-sqlite",
            tags = listOf("数据库", "sqlite"),
            rating = 4.5,
            downloadCount = 18000,
            verified = true,
            metadata = mapOf("transport" to "stdio")
        ),
        MarketItem(
            id = "smithery:brave-search",
            name = "Brave Search MCP",
            description = "免费网页搜索插件，无需付费 key",
            author = "@modelcontextprotocol",
            version = "1.0.0",
            category = IntegrationCategory.MCP,
            marketId = marketId,
            downloadUrl = "npx @modelcontextprotocol/server-brave-search",
            tags = listOf("搜索", "brave", "免费"),
            rating = 4.4,
            downloadCount = 22000,
            verified = true,
            metadata = mapOf("transport" to "stdio")
        )
    )

    private fun matchFilter(item: MarketItem, filter: MarketSearchFilter): Boolean {
        if (filter.query.isNotBlank() &&
            filter.query !in item.name &&
            filter.query !in item.description &&
            filter.query !in item.tags.joinToString()) {
            return false
        }
        if (filter.tags.isNotEmpty() && filter.tags.intersect(item.tags.toSet()).isEmpty()) {
            return false
        }
        if (filter.verifiedOnly && !item.verified) return false
        if (item.rating < filter.minRating) return false
        return true
    }
}
