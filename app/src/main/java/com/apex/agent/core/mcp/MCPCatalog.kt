package com.apex.agent.core.mcp

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nous-approved MCP 目录
 * 
 * 提供交互式选择器，展示 Nous 批准�?MCP 服务器目�? * 用户可以选择和配置所需�?MCP 服务�? */
class MCPCatalog(private val context: Context) {
    
    companion object {
        private const val TAG = "MCPCatalog"
        
        @Volatile
        private var INSTANCE: MCPCatalog? = null
        
        fun getInstance(context: Context): MCPCatalog {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MCPCatalog(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * MCP 服务器类�?     */
    enum class MCPCategory(val displayName: String, val description: String) {
        PRODUCTIVITY("生产力工�?, "文件管理、日历、邮件等 productivity"),
        DEVELOPMENT("开发工�?, "代码搜索、API 文档、数据库等开发相关工�?),
        AI("AI 工具", "AI 模型集成、向量搜索�?embeddings �?),
        DATA("数据处理", "数据转换、格式处理、数据分析等"),
        COMMUNICATION("通信工具", "Slack、Discord、Teams 等通信平台集成"),
        CLOUD("云服�?, "AWS、GCP、Azure 等云服务集成"),
        UTILITY("实用工具", "通用工具、实用函数等")
    }
    
    /**
     * Nous 批准�?MCP 服务器条�?     */
    data class MCPServerEntry(
        val id: String,
        val name: String,
        val description: String,
        val category: MCPCategory,
        val icon: String,
        val capabilities: List<String>,
        val endpoint: String? = null,
        val isInstalled: Boolean = false,
        val isRecommended: Boolean = false
    )
    
    /**
     * 获取 Nous 批准�?MCP 服务器目�?     * 
     * @return MCP 服务器列�?     */
    suspend fun getCatalog(): List<MCPServerEntry> = withContext(Dispatchers.IO) {
        // Nous 批准�?MCP 服务器目�?        listOf(
            // 开发工�?            MCPServerEntry(
                id = "filesystem",
                name = "Filesystem",
                description = "文件系统操作 - 读取、写入、搜索文件和目录",
                category = MCPCategory.DEVELOPMENT,
                icon = "folder",
                capabilities = listOf("read_file", "write_file", "list_directory", "search_files"),
                endpoint = "stdio",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "git",
                name = "Git",
                description = "Git 版本控制 - 提交、分支、合并等操作",
                category = MCPCategory.DEVELOPMENT,
                icon = "git_branch",
                capabilities = listOf("commit", "branch", "merge", "log", "diff"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "database",
                name = "Database",
                description = "数据库操�?- SQL 查询、数据管�?,
                category = MCPCategory.DEVELOPMENT,
                icon = "database",
                capabilities = listOf("query", "execute", "list_tables", "describe_table"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "memory",
                name = "Memory",
                description = "向量记忆存储 - 跨会话存储和检索信�?,
                category = MCPCategory.AI,
                icon = "brain",
                capabilities = listOf("store", "recall", "search", "delete"),
                endpoint = "stdio",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "brave-search",
                name = "Brave Search",
                description = "网页搜索 - 使用 Brave 搜索引擎进行网络搜索",
                category = MCPCategory.PRODUCTIVITY,
                icon = "search",
                capabilities = listOf("web_search", "image_search"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "slack",
                name = "Slack",
                description = "Slack 集成 - 发送消息、读取频�?,
                category = MCPCategory.COMMUNICATION,
                icon = "message_circle",
                capabilities = listOf("send_message", "list_channels", "read_messages"),
                endpoint = "websocket"
            ),
            MCPServerEntry(
                id = "github",
                name = "GitHub",
                description = "GitHub 集成 - Issues、PRs、代码审�?,
                category = MCPCategory.DEVELOPMENT,
                icon = "github",
                capabilities = listOf("list_issues", "create_issue", "list_prs", "review_code"),
                endpoint = "websocket"
            ),
            MCPServerEntry(
                id = "aws-kb-retrieval",
                name = "AWS KB Retrieval",
                description = "AWS Knowledge Base 检�?- 基于 Amazon Bedrock 的知识检�?,
                category = MCPCategory.AI,
                icon = "cloud",
                capabilities = listOf("retrieve", "query_knowledge_base"),
                endpoint = "websocket",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "everart",
                name = "EverArt",
                description = "AI 图像生成 - 使用多种模型生成图像",
                category = MCPCategory.AI,
                icon = "image",
                capabilities = listOf("generate_image", "list_models"),
                endpoint = "websocket"
            ),
            MCPServerEntry(
                id = "google-maps",
                name = "Google Maps",
                description = "地图和位置服�?- 地点搜索、路线规�?,
                category = MCPCategory.PRODUCTIVITY,
                icon = "map_pin",
                capabilities = listOf("geocode", "directions", "places_search"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "fetch",
                name = "Fetch",
                description = "HTTP 请求工具 - 发�?HTTP 请求、API 调用",
                category = MCPCategory.UTILITY,
                icon = "globe",
                capabilities = listOf("get", "post", "put", "delete", "head"),
                endpoint = "stdio",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "sentry",
                name = "Sentry",
                description = "错误追踪和监�?- 查看、上报错误事�?,
                category = MCPCategory.DEVELOPMENT,
                icon = "alert_triangle",
                capabilities = listOf("list_issues", "create_issue", "search_events")
            ),
            MCPServerEntry(
                id = "sequential-thinking",
                name = "Sequential Thinking",
                description = "顺序思维工具 - 复杂问题的逐步推理",
                category = MCPCategory.AI,
                icon = "git_pull_request",
                capabilities = listOf("think", "analyze", "reflect"),
                endpoint = "stdio",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "puppeteer",
                name = "Puppeteer",
                description = "浏览器自动化 - 网页抓取、自动化测试",
                category = MCPCategory.DEVELOPMENT,
                icon = "monitor",
                capabilities = listOf("navigate", "screenshot", "evaluate", "click", "type")
            ),
            MCPServerEntry(
                id = "postgres",
                name = "PostgreSQL",
                description = "PostgreSQL 数据�?- 完整�?SQL 支持",
                category = MCPCategory.DATA,
                icon = "database",
                capabilities = listOf("query", "execute", "list_tables", "describe_table"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "sqlite",
                name = "SQLite",
                description = "SQLite 数据�?- 轻量级本地数据库",
                category = MCPCategory.DATA,
                icon = "database",
                capabilities = listOf("query", "execute", "list_tables"),
                endpoint = "stdio"
            ),
            MCPServerEntry(
                id = "time",
                name = "Time",
                description = "时间和时区工�?- 当前时间、时区转�?,
                category = MCPCategory.UTILITY,
                icon = "clock",
                capabilities = listOf("now", "timezone_convert", "format"),
                endpoint = "stdio",
                isRecommended = true
            ),
            MCPServerEntry(
                id = "todoist",
                name = "Todoist",
                description = "Todoist 任务管理 - 创建、管理任�?,
                category = MCPCategory.PRODUCTIVITY,
                icon = "check_square",
                capabilities = listOf("list_tasks", "create_task", "complete_task", "delete_task")
            ),
            MCPServerEntry(
                id = "notion",
                name = "Notion",
                description = "Notion 笔记和知识库 - 页面、数据库操作",
                category = MCPCategory.PRODUCTIVITY,
                icon = "file_text",
                capabilities = listOf("get_page", "create_page", "query_database", "update_page")
            ),
            MCPServerEntry(
                id = "everything",
                name = "Everything",
                description = "Windows 文件搜索 - 快速全局文件搜索",
                category = MCPCategory.DEVELOPMENT,
                icon = "search",
                capabilities = listOf("search", "list_results")
            )
        )
    }
    
    /**
     * 按类别筛�?MCP 服务�?     * 
     * @param category 类别
     * @return 筛选后的服务器列表
     */
    suspend fun getServersByCategory(category: MCPCategory): List<MCPServerEntry> {
        return getCatalog().filter { it.category == category }
    }
    
    /**
     * 获取推荐服务�?     * 
     * @return 推荐服务器列�?     */
    suspend fun getRecommendedServers(): List<MCPServerEntry> {
        return getCatalog().filter { it.isRecommended }
    }
    
    /**
     * 搜索 MCP 服务�?     * 
     * @param query 搜索关键�?     * @return 匹配的服务器列表
     */
    suspend fun searchServers(query: String): List<MCPServerEntry> {
        val lowerQuery = query.lowercase()
        return getCatalog().filter {
            it.name.lowercase().contains(lowerQuery) ||
            it.description.lowercase().contains(lowerQuery) ||
            it.category.displayName.lowercase().contains(lowerQuery)
        }
    }
    
    /**
     * 获取服务器详�?     * 
     * @param serverId 服务�?ID
     * @return 服务器信息，如果不存在返�?null
     */
    suspend fun getServerDetails(serverId: String): MCPServerEntry? {
        return getCatalog().find { it.id == serverId }
    }
    
    /**
     * 获取所有类�?     * 
     * @return 类别列表
     */
    fun getCategories(): List<MCPCategory> {
        return MCPCategory.entries
    }
    
    /**
     * 将服务器条目转换�?JSON 格式
     */
    fun MCPServerEntry.toJson(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "category" to category.displayName,
            "icon" to icon,
            "capabilities" to capabilities,
            "endpoint" to (endpoint ?: "stdio"),
            "is_installed" to isInstalled,
            "is_recommended" to isRecommended
        )
    }
}
