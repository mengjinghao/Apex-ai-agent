package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.category.plugins.github.GitHubApiClient
import com.apex.agent.integration.category.plugins.github.GitHubTimeWindow
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult
import com.apex.agent.integration.market.SortBy

/**
 * GitHub 插件市场 — 深度集成版。
 *
 * 通过 [GitHubApiClient] 调用 GitHub REST API，支持：
 * - 仓库搜索（关键词/Topic/语言/Star 排序）
 * - MCP 相关仓库自动发现（多 Topic 聚合搜索）
 * - AI Agent 仓库搜索
 * - Trending 热门仓库
 * - Release 下载（通过 getLatestRelease）
 * - 仓库详情查询
 * - GitHub Token 认证（提高 API 限制到 5000/h）
 *
 * # 使用示例
 *
 * ```
 * // 不带 token（匿名，60 次/h 限制）
 * val market = GitHubPluginMarket()
 *
 * // 带 token（5000 次/h 限制）
 * val market = GitHubPluginMarket(token = "ghp_xxx")
 *
 * // 搜索 MCP 相关仓库
 * val results = market.search(MarketSearchFilter(query = "mcp server"))
 *
 * // 获取 Trending
 * val trending = market.getTrending(GitHubTimeWindow.WEEKLY, limit = 20)
 *
 * // 获取精选 MCP 仓库
 * val featured = market.getFeaturedMcpRepositories()
 * ```
 */
class GitHubPluginMarket(
    /** GitHub Personal Access Token（可选，提高 API 限制） */
    private val token: String? = null
) : IntegrationMarket {

    override val marketId = "github_plugins"
    override val displayName = "GitHub"
    override val category = IntegrationCategory.PLUGINS
    override val description = "全球最大代码托管平台，深度集成 GitHub API"
    override val iconUrl = "https://github.com/favicon.ico"
    override val requiresNetwork = true

    /** GitHub API 客户端实例。 */
    val apiClient: GitHubApiClient by lazy { GitHubApiClient(token = token) }

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        // 如果搜索词包含 "mcp"，使用 MCP 专用搜索（多 Topic 聚合）
        val query = filter.query.trim()
        return when {
            query.isBlank() && filter.tags.isEmpty() -> {
                // 无搜索词时返回精选
                val featured = apiClient.getFeaturedMcpRepositories()
                MarketSearchResult(items = featured, totalCount = featured.size, hasMore = false)
            }
            query.contains("mcp", ignoreCase = true) -> {
                apiClient.searchMcpRepositories(filter.page)
            }
            query.contains("agent", ignoreCase = true) || query.contains("ai", ignoreCase = true) -> {
                apiClient.searchAgentRepositories(filter.page)
            }
            else -> {
                apiClient.searchRepositories(
                    query = query,
                    sortBy = filter.sortBy,
                    page = filter.page
                )
            }
        }
    }

    override suspend fun getItem(itemId: String): MarketItem? {
        // itemId 格式: "github:owner/repo"
        val fullName = itemId.removePrefix("github:")
        val parts = fullName.split("/")
        if (parts.size != 2) return null
        return apiClient.getRepository(parts[0], parts[1])
    }

    override suspend fun getCategories(): List<String> {
        return listOf(
            "MCP 服务器",
            "AI Agent",
            "开发工具",
            "SDK",
            "框架",
            "CLI",
            "自动化",
            "Python",
            "Kotlin",
            "TypeScript"
        )
    }

    override suspend fun getFeatured(limit: Int): List<MarketItem> {
        return apiClient.getFeaturedMcpRepositories().take(limit)
    }

    override suspend fun getLatest(limit: Int): List<MarketItem> {
        return apiClient.getTrending(GitHubTimeWindow.WEEKLY, limit = limit)
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // 简单的可用性检查
            apiClient.getRepository("mengjinghao", "Apex-auto-agent") != null
        } catch (_: Exception) {
            false
        }
    }

    // ===== GitHub 专属高级 API =====

    /**
     * 获取 Trending 仓库。
     *
     * @param window 时间窗口（DAILY/WEEKLY/MONTHLY）
     * @param language 语言过滤
     * @param limit 数量
     */
    suspend fun getTrending(
        window: GitHubTimeWindow = GitHubTimeWindow.WEEKLY,
        language: String? = null,
        limit: Int = 20
    ): List<MarketItem> {
        return apiClient.getTrending(window, language, limit)
    }

    /**
     * 搜索 MCP 相关仓库（多 Topic 聚合）。
     */
    suspend fun searchMcpRepositories(page: Int = 1): MarketSearchResult {
        return apiClient.searchMcpRepositories(page)
    }

    /**
     * 搜索 AI Agent 相关仓库。
     */
    suspend fun searchAgentRepositories(page: Int = 1): MarketSearchResult {
        return apiClient.searchAgentRepositories(page)
    }

    /**
     * 按 GitHub Topic 搜索。
     *
     * @param topic Topic 名（如 "model-context-protocol"）
     */
    suspend fun searchByTopic(topic: String, page: Int = 1): MarketSearchResult {
        return apiClient.searchByTopic(topic, page)
    }

    /**
     * 获取最新 Release。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     */
    suspend fun getLatestRelease(owner: String, repo: String): com.apex.agent.integration.category.plugins.github.GitHubRelease? {
        return apiClient.getLatestRelease(owner, repo)
    }

    /**
     * 获取仓库 Topics。
     */
    suspend fun getRepositoryTopics(owner: String, repo: String): List<String> {
        return apiClient.getRepositoryTopics(owner, repo)
    }

    /**
     * 获取精选 MCP 仓库。
     */
    suspend fun getFeaturedMcpRepositories(): List<MarketItem> {
        return apiClient.getFeaturedMcpRepositories()
    }
}
