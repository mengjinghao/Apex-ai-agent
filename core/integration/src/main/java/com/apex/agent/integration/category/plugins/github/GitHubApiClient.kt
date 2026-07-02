package com.apex.agent.integration.category.plugins.github

import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult
import com.apex.agent.integration.market.SortBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub API 客户端。
 *
 * 深度集成 GitHub REST API，支持：
 * - 仓库搜索（按关键词/Topic/语言/Star 排序）
 * - Release 下载（获取最新 Release 资产）
 * - Topic 发现（按 Topic 浏览相关仓库）
 * - 仓库详情（README/Star/Fork/语言）
 * - Trending 仓库（每日/每周/每月热门）
 * - 认证支持（GitHub Token，提高 API 限制）
 *
 * # 使用示例
 *
 * ```
 * val client = GitHubApiClient(token = "ghp_xxx")  // token 可选
 *
 * // 搜索仓库
 * val results = client.searchRepositories("mcp server", sortBy = SortBy.POPULARITY)
 *
 * // 按 Topic 搜索
 * val mcpRepos = client.searchByTopic("model-context-protocol")
 *
 * // 获取 Trending
 * val trending = client.getTrending(GitHubTimeWindow.WEEKLY)
 *
 * // 获取最新 Release
 * val release = client.getLatestRelease("punkpeye", "awesome-mcp-servers")
 * ```
 */
class GitHubApiClient(
    /** GitHub Personal Access Token（可选，提高 API 限制到 5000/h） */
    private val token: String? = null,
    /** API 基础 URL */
    private val apiBase: String = "https://api.github.com",
    /** 请求超时（毫秒） */
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) {

    companion object {
        private const val TAG = "GitHubApiClient"
        private const val ITEMS_PER_PAGE = 30
        private const val MAX_PAGES = 10

        /** MCP 相关的 GitHub Topic。 */
        val MCP_TOPICS = listOf(
            "model-context-protocol",
            "mcp-server",
            "mcp-client",
            "mcp",
            "claude-mcp"
        )

        /** AI Agent 相关的 GitHub Topic。 */
        val AGENT_TOPICS = listOf(
            "ai-agent",
            "llm-agent",
            "autonomous-agent",
            "agent-framework",
            "ai-skill"
        )

        /** 插件相关的 GitHub Topic。 */
        val PLUGIN_TOPICS = listOf(
            "mcp-plugin",
            "ai-plugin",
            "agent-plugin",
            "tool-server"
        )
    }

    /**
     * 搜索仓库。
     *
     * @param query 搜索关键词
     * @param sortBy 排序方式
     * @param language 语言过滤（如 "kotlin", "python"）
     * @param topic Topic 过滤
     * @param page 页码（从 1 开始）
     * @return 搜索结果
     */
    suspend fun searchRepositories(
        query: String,
        sortBy: SortBy = SortBy.POPULARITY,
        language: String? = null,
        topic: String? = null,
        page: Int = 1
    ): MarketSearchResult = withContext(Dispatchers.IO) {
        val sortParam = when (sortBy) {
            SortBy.POPULARITY -> "stars"
            SortBy.NEWEST -> "updated"
            SortBy.RATING -> "stars"
            SortBy.NAME -> "name"
            SortBy.DOWNLOAD_SIZE -> "stars"
        }
        val order = if (sortBy == SortBy.NAME) "asc" else "desc"

        val queryParts = mutableListOf<String>()
        queryParts.add(query)
        if (language != null) queryParts.add("language:$language")
        if (topic != null) queryParts.add("topic:$topic")

        val encodedQuery = URLEncoder.encode(queryParts.joinToString(" "), "UTF-8")
        val url = "$apiBase/search/repositories?q=$encodedQuery&sort=$sortParam&order=$order&per_page=$ITEMS_PER_PAGE&page=$page"

        try {
            val response = httpGet(url)
            val json = JSONObject(response)
            val totalCount = json.optInt("total_count", 0)
            val items = json.optJSONArray("items") ?: JSONArray()

            val marketItems = (0 until items.length()).map { i ->
                val repo = items.getJSONObject(i)
                repoToMarketItem(repo)
            }

            MarketSearchResult(
                items = marketItems,
                totalCount = totalCount,
                page = page,
                pageSize = ITEMS_PER_PAGE,
                hasMore = page * ITEMS_PER_PAGE < totalCount
            )
        } catch (e: Exception) {
            MarketSearchResult.EMPTY
        }
    }

    /**
     * 按 Topic 搜索仓库。
     *
     * @param topic GitHub Topic 名（如 "model-context-protocol"）
     * @param page 页码
     * @return 搜索结果
     */
    suspend fun searchByTopic(topic: String, page: Int = 1): MarketSearchResult {
        return searchRepositories(query = "", topic = topic, page = page)
    }

    /**
     * 搜索 MCP 相关仓库。
     *
     * 自动搜索所有 MCP 相关 Topic，合并结果。
     */
    suspend fun searchMcpRepositories(page: Int = 1): MarketSearchResult {
        val allItems = mutableListOf<MarketItem>()
        var totalCount = 0

        for (topic in MCP_TOPICS) {
            val result = searchByTopic(topic, page)
            allItems.addAll(result.items)
            totalCount += result.totalCount
        }

        // 去重（按 id）
        val unique = allItems.distinctBy { it.id }

        return MarketSearchResult(
            items = unique,
            totalCount = totalCount,
            page = page,
            pageSize = ITEMS_PER_PAGE,
            hasMore = page * ITEMS_PER_PAGE < totalCount
        )
    }

    /**
     * 搜索 AI Agent 相关仓库。
     */
    suspend fun searchAgentRepositories(page: Int = 1): MarketSearchResult {
        val allItems = mutableListOf<MarketItem>()
        var totalCount = 0

        for (topic in AGENT_TOPICS) {
            val result = searchByTopic(topic, page)
            allItems.addAll(result.items)
            totalCount += result.totalCount
        }

        val unique = allItems.distinctBy { it.id }
        return MarketSearchResult(items = unique, totalCount = totalCount, hasMore = false)
    }

    /**
     * 获取仓库详情。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @return 仓库信息，不存在返回 null
     */
    suspend fun getRepository(owner: String, repo: String): MarketItem? = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$apiBase/repos/$owner/$repo")
            val json = JSONObject(response)
            repoToMarketItem(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取最新 Release。
     *
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @return Release 信息，无 Release 返回 null
     */
    suspend fun getLatestRelease(owner: String, repo: String): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$apiBase/repos/$owner/$repo/releases/latest")
            val json = JSONObject(response)
            GitHubRelease(
                tagName = json.optString("tag_name"),
                name = json.optString("name"),
                body = json.optString("body"),
                htmlUrl = json.optString("html_url"),
                publishedAt = json.optString("published_at"),
                assets = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val asset = arr.getJSONObject(i)
                        GitHubReleaseAsset(
                            name = asset.optString("name"),
                            downloadUrl = asset.optString("browser_download_url"),
                            sizeBytes = asset.optLong("size"),
                            downloadCount = asset.optLong("download_count")
                        )
                    }
                } ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取仓库的 Topics。
     */
    suspend fun getRepositoryTopics(owner: String, repo: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = httpGet("$apiBase/repos/$owner/$repo/topics", accept = "application/vnd.github.mercy-preview+json")
            val json = JSONObject(response)
            val names = json.optJSONArray("names") ?: JSONArray()
            (0 until names.length()).map { names.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取热门仓库（Trending）。
     *
     * 通过搜索最近创建/更新的高 Star 仓库模拟 Trending。
     *
     * @param window 时间窗口
     * @param language 语言过滤
     * @param limit 数量
     */
    suspend fun getTrending(
        window: GitHubTimeWindow = GitHubTimeWindow.WEEKLY,
        language: String? = null,
        limit: Int = 20
    ): List<MarketItem> = withContext(Dispatchers.IO) {
        val daysAgo = when (window) {
            GitHubTimeWindow.DAILY -> 1
            GitHubTimeWindow.WEEKLY -> 7
            GitHubTimeWindow.MONTHLY -> 30
        }
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(calendar.time)

        val queryParts = mutableListOf("stars:>100", "created:>$dateStr")
        if (language != null) queryParts.add("language:$language")

        val encodedQuery = URLEncoder.encode(queryParts.joinToString(" "), "UTF-8")
        val url = "$apiBase/search/repositories?q=$encodedQuery&sort=stars&order=desc&per_page=$limit"

        try {
            val response = httpGet(url)
            val json = JSONObject(response)
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { i ->
                repoToMarketItem(items.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取推荐的 MCP 仓库（精选）。
     */
    suspend fun getFeaturedMcpRepositories(): List<MarketItem> {
        // 精选仓库列表
        val featuredRepos = listOf(
            "punkpeye/awesome-mcp-servers" to "MCP 服务器大全",
            "modelcontextprotocol/servers" to "官方 MCP 服务",
            "jlowin/fastmcp" to "FastMCP Python SDK",
            "supercorp-ai/supergateway" to "MCP 网关",
            "executeautomation/playwright-mcp-server" to "Playwright MCP",
            "dhravya/apple-mcp" to "Apple MCP"
        )

        val results = mutableListOf<MarketItem>()
        for ((repoPath, _) in featuredRepos) {
            val parts = repoPath.split("/")
            if (parts.size == 2) {
                getRepository(parts[0], parts[1])?.let { results.add(it) }
            }
        }
        return results
    }

    // ===== 内部方法 =====

    private fun httpGet(urlStr: String, accept: String = "application/vnd.github.v3+json"): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "Apex-Agent-Integration")
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            connection.disconnect()
            throw RuntimeException("HTTP $responseCode for $urlStr")
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return response
    }

    private fun repoToMarketItem(repo: JSONObject): MarketItem {
        val fullName = repo.optString("full_name", "")
        val owner = fullName.substringBefore("/", "")
        val repoName = fullName.substringAfter("/", fullName)
        val description = repo.optString("description", "")
        val stars = repo.optInt("stargazers_count", 0)
        val forks = repo.optInt("forks_count", 0)
        val language = repo.optString("language", "")
        val license = repo.optJSONObject("license")?.optString("spdx_id", "") ?: ""
        val topics = repo.optJSONArray("topics")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val htmlUrl = repo.optString("html_url", "")
        val cloneUrl = repo.optString("clone_url", "")
        val updatedAt = repo.optString("updated_at", "")
        val openIssues = repo.optInt("open_issues_count", 0)

        return MarketItem(
            id = "github:$fullName",
            name = repoName,
            description = description.ifBlank { "GitHub repository: $fullName" },
            author = owner,
            version = updatedAt.take(10),  // 用更新日期作为版本参考
            category = com.apex.agent.integration.api.IntegrationCategory.PLUGINS,
            marketId = "github_plugins",
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            sourceUrl = htmlUrl,
            iconUrl = repo.optJSONObject("owner")?.optString("avatar_url"),
            downloadUrl = cloneUrl,
            downloadSizeBytes = 0L,
            tags = buildList {
                addAll(topics)
                if (language.isNotBlank()) add(language.lowercase())
                if (stars > 1000) add("popular")
                if (license.isNotBlank()) add(license.lowercase())
            },
            rating = calculateRating(stars, forks, openIssues),
            downloadCount = stars.toLong(),  // GitHub 用 Star 数表示热度
            verified = repo.optJSONObject("owner")?.optString("type") == "Organization",
            metadata = mapOf(
                "stars" to stars.toString(),
                "forks" to forks.toString(),
                "language" to language,
                "license" to license,
                "openIssues" to openIssues.toString(),
                "updatedAt" to updatedAt,
                "fullName" to fullName,
                "htmlUrl" to htmlUrl
            )
        )
    }

    /**
     * 根据 Star/Fork/Issue 计算评分（0..5）。
     */
    private fun calculateRating(stars: Int, forks: Int, openIssues: Int): Double {
        if (stars == 0) return 0.0
        val starScore = (Math.log10(stars.toDouble()) / 5.0).coerceAtMost(1.0)  // 100000 star = 1.0
        val forkScore = (Math.log10(forks.toDouble().coerceAtLeast(1.0)) / 4.0).coerceAtMost(0.3)
        val issuePenalty = (openIssues.toDouble() / stars.toDouble()).coerceAtMost(0.3)
        return ((starScore * 3.5 + forkScore * 1.5 - issuePenalty) * 5.0).coerceIn(0.0, 5.0)
    }
}

/**
 * GitHub Release 信息。
 */
data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val assets: List<GitHubReleaseAsset>
)

/**
 * Release 资产。
 */
data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val downloadCount: Long
)

/**
 * Trending 时间窗口。
 */
enum class GitHubTimeWindow(val displayName: String) {
    DAILY("今日"),
    WEEKLY("本周"),
    MONTHLY("本月")
}
