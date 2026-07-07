package com.apex.agent.integration.category.mcp

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem

/**
 * 内置 MCP 服务器目录。
 *
 * 迁移自原 Apex-agent 的 MCPCatalog，包含 20+ 精选 MCP 服务器条目。
 * 这些是 Anthropic 官方维护和社区热门的标准 MCP 服务，可直接 npx 启动。
 *
 * # 分类
 * - 生产力工具（文件/Git/数据库/搜索）
 * - 开发工具（代码/API 文档）
 * - AI 工具（图像生成/思维链）
 * - 通信工具（Slack/Notion）
 * - 云服务（AWS）
 */
object BuiltinMcpCatalog {

    /**
     * MCP 服务器分类。
     */
    enum class MCPCategory(val displayName: String, val description: String) {
        PRODUCTIVITY("生产力工具", "文件管理、日历、邮件等"),
        DEVELOPMENT("开发工具", "代码搜索、API 文档、数据库等"),
        AI("AI 工具", "AI 模型集成、向量搜索、embeddings 等"),
        DATA("数据处理", "数据转换、格式处理、数据分析等"),
        COMMUNICATION("通信工具", "Slack、Discord、Teams 等"),
        CLOUD("云服务", "AWS、GCP、Azure 等"),
        UTILITY("实用工具", "通用工具、实用函数等")
    }

    /**
     * MCP 服务器条目。
     */
    data class McpCatalogEntry(
        val name: String,
        val description: String,
        val category: MCPCategory,
        val command: String,
        val packageName: String,
        val verified: Boolean = true,
        val tags: List<String> = emptyList(),
        val rating: Double = 4.5,
        val downloadCount: Long = 10000
    )

    /**
     * 所有内置 MCP 服务器条目。
     */
    val entries: List<McpCatalogEntry> = listOf(
        // === 生产力工具 ===
        McpCatalogEntry(
            name = "Filesystem",
            description = "文件系统操作 - 读取、写入、搜索文件和目录",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-filesystem",
            packageName = "@modelcontextprotocol/server-filesystem",
            tags = listOf("文件", "filesystem", "本地"),
            rating = 4.8,
            downloadCount = 50000
        ),
        McpCatalogEntry(
            name = "Git",
            description = "Git 版本控制 - 提交、分支、合并等操作",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-git",
            packageName = "@modelcontextprotocol/server-git",
            tags = listOf("代码", "git", "版本控制"),
            rating = 4.6,
            downloadCount = 28000
        ),
        McpCatalogEntry(
            name = "Fetch",
            description = "网页抓取 - 获取和解析在线内容",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-fetch",
            packageName = "@modelcontextprotocol/server-fetch",
            tags = listOf("爬虫", "fetch", "网页"),
            rating = 4.7,
            downloadCount = 35000
        ),
        McpCatalogEntry(
            name = "Time",
            description = "时间工具 - 获取当前时间、时区转换",
            category = MCPCategory.UTILITY,
            command = "npx @modelcontextprotocol/server-time",
            packageName = "@modelcontextprotocol/server-time",
            tags = listOf("时间", "utility"),
            rating = 4.3,
            downloadCount = 12000
        ),

        // === 数据库 ===
        McpCatalogEntry(
            name = "SQLite",
            description = "SQLite 数据库 - 查询、插入、更新操作",
            category = MCPCategory.DATA,
            command = "npx @modelcontextprotocol/server-sqlite",
            packageName = "@modelcontextprotocol/server-sqlite",
            tags = listOf("数据库", "sqlite"),
            rating = 4.5,
            downloadCount = 18000
        ),
        McpCatalogEntry(
            name = "PostgreSQL",
            description = "PostgreSQL 数据库 - 查询和管理",
            category = MCPCategory.DATA,
            command = "npx @modelcontextprotocol/server-postgres",
            packageName = "@modelcontextprotocol/server-postgres",
            tags = listOf("数据库", "postgres"),
            rating = 4.6,
            downloadCount = 15000
        ),

        // === 搜索 ===
        McpCatalogEntry(
            name = "Brave Search",
            description = "网页搜索 - 使用 Brave 搜索引擎进行网络搜索",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-brave-search",
            packageName = "@modelcontextprotocol/server-brave-search",
            tags = listOf("搜索", "brave", "免费"),
            rating = 4.4,
            downloadCount = 22000
        ),

        // === AI 工具 ===
        McpCatalogEntry(
            name = "Memory",
            description = "知识图谱记忆 - 存储和检索结构化知识",
            category = MCPCategory.AI,
            command = "npx @modelcontextprotocol/server-memory",
            packageName = "@modelcontextprotocol/server-memory",
            tags = listOf("AI", "memory", "知识图谱"),
            rating = 4.5,
            downloadCount = 14000
        ),
        McpCatalogEntry(
            name = "Sequential Thinking",
            description = "顺序思维工具 - 复杂问题的逐步推理",
            category = MCPCategory.AI,
            command = "npx @modelcontextprotocol/server-sequential-thinking",
            packageName = "@modelcontextprotocol/server-sequential-thinking",
            tags = listOf("AI", "推理", "CoT"),
            rating = 4.7,
            downloadCount = 16000
        ),
        McpCatalogEntry(
            name = "EverArt",
            description = "AI 图像生成 - 使用多种模型生成图像",
            category = MCPCategory.AI,
            command = "npx @modelcontextprotocol/server-everart",
            packageName = "@modelcontextprotocol/server-everart",
            tags = listOf("AI", "图像生成"),
            rating = 4.2,
            downloadCount = 8000
        ),

        // === 通信 ===
        McpCatalogEntry(
            name = "Slack",
            description = "Slack 消息 - 发送消息、搜索历史",
            category = MCPCategory.COMMUNICATION,
            command = "npx @modelcontextprotocol/server-slack",
            packageName = "@modelcontextprotocol/server-slack",
            tags = listOf("通信", "slack"),
            rating = 4.4,
            downloadCount = 12000
        ),
        McpCatalogEntry(
            name = "Notion",
            description = "Notion 笔记和知识库 - 页面、数据库操作",
            category = MCPCategory.COMMUNICATION,
            command = "npx @modelcontextprotocol/server-notion",
            packageName = "@modelcontextprotocol/server-notion",
            tags = listOf("办公", "notion"),
            rating = 4.6,
            downloadCount = 15000
        ),

        // === 开发 ===
        McpCatalogEntry(
            name = "GitHub",
            description = "GitHub 操作 - 仓库管理、Issue、PR",
            category = MCPCategory.DEVELOPMENT,
            command = "npx @modelcontextprotocol/server-github",
            packageName = "@modelcontextprotocol/server-github",
            tags = listOf("开发", "github", "代码"),
            rating = 4.8,
            downloadCount = 30000
        ),
        McpCatalogEntry(
            name = "Sentry",
            description = "Sentry 错误监控 - 查询和分析应用错误",
            category = MCPCategory.DEVELOPMENT,
            command = "npx @modelcontextprotocol/server-sentry",
            packageName = "@modelcontextprotocol/server-sentry",
            tags = listOf("开发", "sentry", "监控"),
            rating = 4.3,
            downloadCount = 6000
        ),

        // === 浏览器自动化 ===
        McpCatalogEntry(
            name = "Puppeteer",
            description = "浏览器自动化 - 网页抓取、自动化测试",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-puppeteer",
            packageName = "@modelcontextprotocol/server-puppeteer",
            tags = listOf("浏览器自动化", "puppeteer"),
            rating = 4.7,
            downloadCount = 25000
        ),

        // === 云服务 ===
        McpCatalogEntry(
            name = "AWS KB Retrieval",
            description = "AWS 知识库检索 - 从 AWS Kendra 检索信息",
            category = MCPCategory.CLOUD,
            command = "npx @modelcontextprotocol/server-aws-kb-retrieval",
            packageName = "@modelcontextprotocol/server-aws-kb-retrieval",
            tags = listOf("云服务", "aws"),
            rating = 4.0,
            downloadCount = 4000
        ),

        // === 地图 ===
        McpCatalogEntry(
            name = "Google Maps",
            description = "Google 地图 - 位置搜索、路线规划",
            category = MCPCategory.UTILITY,
            command = "npx @modelcontextprotocol/server-google-maps",
            packageName = "@modelcontextprotocol/server-google-maps",
            tags = listOf("地图", "google"),
            rating = 4.5,
            downloadCount = 10000
        ),

        // === 任务管理 ===
        McpCatalogEntry(
            name = "Todoist",
            description = "Todoist 任务管理 - 创建、更新任务",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @modelcontextprotocol/server-todoist",
            packageName = "@modelcontextprotocol/server-todoist",
            tags = listOf("任务管理", "todoist"),
            rating = 4.2,
            downloadCount = 5000
        ),

        // === 系统工具 ===
        McpCatalogEntry(
            name = "Everything",
            description = "Windows 文件搜索 - 快速全局文件搜索",
            category = MCPCategory.UTILITY,
            command = "npx @modelcontextprotocol/server-everything",
            packageName = "@modelcontextprotocol/server-everything",
            tags = listOf("文件", "windows", "搜索"),
            rating = 4.1,
            downloadCount = 3000
        ),

        // === 社区热门 ===
        McpCatalogEntry(
            name = "Playwright",
            description = "Playwright 浏览器自动化 - 爬虫、表单填写、E2E 测试",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @executeautomation/playwright-mcp-server",
            packageName = "@executeautomation/playwright-mcp-server",
            tags = listOf("浏览器自动化", "playwright"),
            rating = 4.9,
            downloadCount = 42000
        ),
        McpCatalogEntry(
            name = "Apple MCP",
            description = "macOS 通讯录/日历/提醒事项，完全离线免费",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @dhravya/apple-mcp",
            packageName = "@dhravya/apple-mcp",
            tags = listOf("apple", "离线", "macos"),
            rating = 4.6,
            downloadCount = 9000
        ),
        McpCatalogEntry(
            name = "HomeAssistant MCP",
            description = "智能家居控制，本地运行",
            category = MCPCategory.UTILITY,
            command = "npx @joshuavial/homeassistant-mcp",
            packageName = "@joshuavial/homeassistant-mcp",
            tags = listOf("智能家居", "离线"),
            rating = 4.4,
            downloadCount = 5000
        ),
        McpCatalogEntry(
            name = "Fast Filesystem MCP",
            description = "增强版文件 MCP，大文件流式处理、项目依赖分析",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @punkpeye/fast-filesystem-mcp",
            packageName = "@punkpeye/fast-filesystem-mcp",
            tags = listOf("文件", "filesystem", "增强"),
            rating = 4.5,
            downloadCount = 7000
        ),
        McpCatalogEntry(
            name = "Ref MCP",
            description = "代码文档语义检索，智能读取项目注释与 API 文档",
            category = MCPCategory.DEVELOPMENT,
            command = "npx @punkpeye/ref-mcp",
            packageName = "@punkpeye/ref-mcp",
            tags = listOf("代码", "检索", "文档"),
            rating = 4.7,
            downloadCount = 6000
        ),
        McpCatalogEntry(
            name = "Free Google Search MCP",
            description = "无 key 免费谷歌搜索 MCP 服务",
            category = MCPCategory.PRODUCTIVITY,
            command = "npx @punkpeye/gsearch-free-mcp",
            packageName = "@punkpeye/gsearch-free-mcp",
            tags = listOf("搜索", "google", "免费"),
            rating = 4.3,
            downloadCount = 8000
        )
    )

    /**
     * 转换为 [MarketItem] 列表。
     *
     * @param marketId 所属市场 ID
     */
    fun toMarketItems(marketId: String = "builtin_mcp"): List<MarketItem> {
        return entries.map { entry ->
            MarketItem(
                id = "$marketId:${entry.name.lowercase().replace(" ", "_")}",
                name = entry.name,
                description = entry.description,
                author = "@modelcontextprotocol",
                version = "1.0.0",
                category = IntegrationCategory.MCP,
                marketId = marketId,
                sourceType = IntegrationSourceType.OFFICIAL_MARKET,
                downloadUrl = entry.command,
                tags = entry.tags,
                rating = entry.rating,
                downloadCount = entry.downloadCount,
                verified = entry.verified,
                metadata = mapOf(
                    "command" to entry.command,
                    "package" to entry.packageName,
                    "category" to entry.category.name,
                    "transport" to "stdio"
                )
            )
        }
    }

    /**
     * 按分类筛选。
     */
    fun getByCategory(category: MCPCategory): List<McpCatalogEntry> {
        return entries.filter { it.category == category }
    }

    /**
     * 获取所有分类。
     */
    fun getCategories(): List<MCPCategory> = MCPCategory.values().toList()
}
