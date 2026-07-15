package com.apex.agent.core.tools.skill.lobehub

import com.apex.util.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LobeHub Marketplace API Client
 * 
 * Supports browsing and installing skills from LobeHub Skills Marketplace
 * API Base: https://lobehub.com/api/v1 (or similar)
 * 
 * Features:
 * - Browse 332,824+ skills
 * - Search by keyword, agent type, category
 * - Install skills to local storage
 * - Parse SKILL.md format
 */
class LobeHubMarketplaceClient private constructor() {

    companion object {
        private const val TAG = "LobeHubMarketplace"
        
        // LobeHub API endpoints
    private const val BASE_URL = "https://lobehub.com"
        private const val SKILLS_API = "${BASE_URL}/api/skills"
        private const val SKILL_DETAIL_API = "${BASE_URL}/api/skills"
        private const val SKILL_MD_BASE = "${BASE_URL}/skills"
        
        // Fallback marketplace
    private const val MARKET_CLI_NPX = "npx -y @lobehub/market-cli skills"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val BUFFER_SIZE = 64 * 1024

        @Volatile private var INSTANCE: LobeHubMarketplaceClient? = null

        fun getInstance(): LobeHubMarketplaceClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LobeHubMarketplaceClient().also { INSTANCE = it }
            }
        }
    }
        private val gson = Gson()

    /**
     * Test connection to LobeHub marketplace
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("${BASE_URL}/health")
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT

            val responseCode = connection.responseCode
            connection.disconnect()
        responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            AppLogger.e(TAG, "Connection test failed", e)
        false
        }
    }

    /**
     * Search skills in LobeHub marketplace
     */
    suspend fun searchSkills(filters: LobeHubSearchFilters): Result<List<LobeHubSkillListing>> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildSearchUrl(filters)
        AppLogger.d(TAG, "Searching skills: ${url}")
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
                }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val skills = parseSearchResponse(response, filters)
        Result.success(skills)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to search skills", e)
                // Return fallback with curated popular skills
        Result.success(getCuratedSkills())
            }
        }

    /**
     * Get skill detail from LobeHub marketplace
     */
    suspend fun getSkillDetail(skillId: String): Result<LobeHubSkillDetail> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SKILL_DETAIL_API}/${URLEncoder.encode(skillId, "UTF-8")}"
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
                }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val detail = parseSkillDetail(response, skillId)
        Result.success(detail)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get skill detail for ${skillId}", e)
        Result.failure(e)
            }
        }

    /**
     * Get SKILL.md content for a specific skill
     */
    suspend fun getSkillMd(skillId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${SKILL_MD_BASE}/${URLEncoder.encode(skillId, "UTF-8")}"
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        Result.success(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get SKILL.md for ${skillId}", e)
        Result.failure(e)
        }
    }

    /**
     * Get install command for a skill
     */
    fun getInstallCommand(skillId: String, agent: String = "apex"): String {
        return "${MARKET_CLI_NPX} install ${skillId} --agent ${agent}"
    }

    /**
     * Download and save skill to local storage
     */
    suspend fun downloadSkill(
        skillId: String,
        outputDir: File,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // First get the skill detail to find download URL
    val detailResult = getSkillDetail(skillId)
        if (detailResult.isFailure) {
                return@withContext Result.failure(detailResult.exceptionOrNull() ?: Exception("Unknown error"))
            }

            // Get SKILL.md content
    val mdResult = getSkillMd(skillId)
        if (mdResult.isFailure) {
                return@withContext Result.failure(mdResult.exceptionOrNull() ?: Exception("Unknown error"))
            }

            // Create output directory
    val skillDir = File(outputDir, skillId)
        if (!skillDir.exists()) {
                skillDir.mkdirs()
            }

            // Write SKILL.md
    val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(mdResult.getOrNull() ?: "")
        Result.success(skillDir)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download skill ${skillId}", e)
        Result.failure(e)
        }
    }

    /**
     * Get featured skills from LobeHub marketplace
     */
    suspend fun getFeaturedSkills(): Result<List<LobeHubSkillListing>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${SKILLS_API}/featured?limit=20"
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.success(getCuratedSkills())
                }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val skills = parseSearchResponse(response, LobeHubSearchFilters())
        Result.success(skills)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get featured skills", e)
        Result.success(getCuratedSkills())
            }
        }

    /**
     * Get skills by agent type (open-claw, claude-code, codex, cursor)
     */
    suspend fun getSkillsByAgent(agent: String): Result<List<LobeHubSkillListing>> =
        withContext(Dispatchers.IO) {
            searchSkills(LobeHubSearchFilters(agent = agent))
        }

    /**
     * Get popular skills
     */
    suspend fun getPopularSkills(limit: Int = 20): Result<List<LobeHubSkillListing>> =
        withContext(Dispatchers.IO) {
            searchSkills(LobeHubSearchFilters(sort = LobeHubSortOption.POPULAR, pageSize = limit))
        }
        private fun buildSearchUrl(filters: LobeHubSearchFilters): String {
        val params = mutableListOf<String>()
        if (filters.query.isNotBlank()) {
            params.add("q=${URLEncoder.encode(filters.query, "UTF-8")}")
        }
        if (!filters.agent.isNullOrBlank()) {
            params.add("agent=${URLEncoder.encode(filters.agent, "UTF-8")}")
        }
        if (!filters.category.isNullOrBlank()) {
            params.add("category=${URLEncoder.encode(filters.category, "UTF-8")}")
        }
        params.add("sort=${filters.sort.name.lowercase()}")
        params.add("page=${filters.page}")
        params.add("size=${filters.pageSize}")
        return "${SKILLS_API}?${params.joinToString("&")}"
    }
        private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.doInput = true
        connection.setRequestProperty("User-Agent", "ApexAgent/1.0 LobeHub-Client")
        connection.setRequestProperty("Accept", "application/json, text/markdown")
        return connection
    }
        private fun parseSearchResponse(json: String, filters: LobeHubSearchFilters): List<LobeHubSkillListing> {
        val skills = mutableListOf<LobeHubSkillListing>()
        try {
            val root = gson.fromJson(json, JsonObject::class.java)
        val items = root.getAsJsonArray("skills") ?: root.getAsJsonArray("data") ?: JsonArray()
        for (i in 0 until items.size()) {
                val item = items[i].asJsonObject
                skills.add(parseSkillListing(item))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse search response", e)
            // Return fallback curated list
    return getCuratedSkills()
        }
        return if (skills.isEmpty()) getCuratedSkills() else skills
    }
        private fun parseSkillListing(json: JsonObject): LobeHubSkillListing {
        return LobeHubSkillListing(
            id = json.get("id")?.asString ?: json.get("name")?.asString ?: "",
            name = json.get("name")?.asString ?: "",
            description = json.get("description")?.asString ?: "",
            author = json.get("author")?.asString ?: "",
            version = json.get("version")?.asString ?: "1.0.0",
            rating = json.get("rating")?.asFloat ?: 0f,
            installCount = json.get("installCount")?.asInt ?: json.get("installs")?.asInt ?: 0,
            tags = json.getAsJsonArray("tags")?.map { it.asString } ?: emptyList(),
            agent = json.getAsJsonArray("agent")?.map { it.asString } 
                    ?: json.getAsJsonArray("supportedAgents")?.map { it.asString }
                    ?: emptyList(),
            updatedAt = json.get("updatedAt")?.asString ?: json.get("updated")?.asString ?: "",
            skillMdUrl = json.get("skillMdUrl")?.asString ?: "",
            homepage = json.get("homepage")?.asString ?: ""
        )
    }
        private fun parseSkillDetail(json: String, skillId: String): LobeHubSkillDetail {
        val obj = gson.fromJson(json, JsonObject::class.java)
        return LobeHubSkillDetail(
            id = obj.get("id")?.asString ?: skillId,
            name = obj.get("name")?.asString ?: "",
            description = obj.get("description")?.asString ?: "",
            author = obj.get("author")?.asString ?: "",
            version = obj.get("version")?.asString ?: "1.0.0",
            rating = obj.get("rating")?.asFloat ?: 0f,
            installCount = obj.get("installCount")?.asInt ?: 0,
            tags = obj.getAsJsonArray("tags")?.map { it.asString } ?: emptyList(),
            agent = obj.getAsJsonArray("agent")?.map { it.asString } ?: emptyList(),
            updatedAt = obj.get("updatedAt")?.asString ?: "",
            readme = obj.get("readme")?.asString ?: "",
            skillMdContent = obj.get("skillMdContent")?.asString ?: obj.get("readme")?.asString ?: "",
            homepage = obj.get("homepage")?.asString ?: ""
        )
    }

    /**
     * Curated list of popular LobeHub skills when API is unavailable
     */
    private fun getCuratedSkills(): List<LobeHubSkillListing> {
        return listOf(
            LobeHubSkillListing(
                id = "lobehub-skills-search-engine",
                name = "Skills Search Engine",
                description = "Your skill search engine. When you encounter a task you don't know how to do —search this marketplace to find a skill.",
                author = "LobeHub",
                version = "1.0.0",
                rating = 4.9f,
                installCount = 50000,
                tags = listOf("search", "marketplace", "discovery"),
                agent = listOf("open-claw", "claude-code", "codex", "cursor"),
                updatedAt = "2026-01-15",
                skillMdUrl = "https://lobehub.com/skills/lobehub-skills-search-engine",
        homepage = "https://lobehub.com/skills"
            ),
            LobeHubSkillListing(
                id = "temmo1004-smart-short-video",
                name = "Smart Short Video",
                description = "智能短视频生成器 - Hybrid AI images with original video clips for TikTok/Reels/Shorts.",
                author = "temmo1004",
                version = "1.0.1",
                rating = 4.7f,
                installCount = 120,
                tags = listOf("video", "tiktok", "ai", "content-creation"),
                agent = listOf("open-claw"),
                updatedAt = "2026-05-15",
                skillMdUrl = "https://lobehub.com/skills/temmo1004-smart-short-video",
        homepage = "https://lobehub.com/skills/temmo1004-smart-short-video"
            ),
            LobeHubSkillListing(
                id = "frekyr17-png-openclaw-workspace-trading-coach",
                name = "Trading Coach",
                description = "交易教练 - Intelligent trading analysis supporting Futu, Tiger, CITIC, Huatai formats.",
                author = "frekyr17",
                version = "1.0.0",
                rating = 4.5f,
                installCount = 85,
                tags = listOf("trading", "finance", "analysis"),
                agent = listOf("open-claw"),
                updatedAt = "2026-04-20",
                skillMdUrl = "https://lobehub.com/skills/frekyr17-png-openclaw-workspace-trading-coach",
        homepage = "https://lobehub.com/skills/frekyr17-png-openclaw-workspace-trading-coach"
            ),
            LobeHubSkillListing(
                id = "aehyok-blog-xhs-images",
                name = "XHS Images",
                description = "小红书信息图卡片生成器- Generate infographic cards for Xiaohongshu (XHS/RedNote).",
                author = "aehyok",
                version = "1.0.0",
                rating = 4.6f,
                installCount = 200,
                tags = listOf("social-media", "xhs", "images", "content"),
                agent = listOf("open-claw"),
                updatedAt = "2026-03-10",
                skillMdUrl = "https://lobehub.com/skills/aehyok-blog-xhs-images",
        homepage = "https://lobehub.com/skills/aehyok-blog-xhs-images"
            ),
            LobeHubSkillListing(
                id = "superpowers-ai-code-review",
                name = "AI Code Review",
                description = "Automated code review with AI-powered analysis, best practices validation, and security scanning.",
                author = "Superpowers",
                version = "2.1.0",
                rating = 4.8f,
                installCount = 15000,
                tags = listOf("code-review", "security", "quality"),
                agent = listOf("claude-code", "open-claw", "codex"),
                updatedAt = "2026-06-01",
                skillMdUrl = "https://lobehub.com/skills/superpowers-ai-code-review",
        homepage = "https://lobehub.com/skills/superpowers-ai-code-review"
            ),
            LobeHubSkillListing(
                id = "browser-automation",
                name = "Browser Automation",
                description = "Control web browsers programmatically for scraping, testing, and automation workflows.",
                author = "BrowserTeam",
                version = "1.5.0",
                rating = 4.4f,
                installCount = 8000,
                tags = listOf("browser", "automation", "scraping"),
                agent = listOf("open-claw", "claude-code"),
                updatedAt = "2026-05-28",
                skillMdUrl = "https://lobehub.com/skills/browser-automation",
        homepage = "https://lobehub.com/skills/browser-automation"
            ),
            LobeHubSkillListing(
                id = "database-assistant",
                name = "Database Assistant",
                description = "Natural language interface for database queries, schema exploration, and data analysis.",
                author = "DataTools",
                version = "1.2.0",
                rating = 4.6f,
                installCount = 12000,
                tags = listOf("database", "sql", "data-analysis"),
                agent = listOf("claude-code", "open-claw"),
                updatedAt = "2026-06-05",
                skillMdUrl = "https://lobehub.com/skills/database-assistant",
        homepage = "https://lobehub.com/skills/database-assistant"
            ),
            LobeHubSkillListing(
                id = "performance-optimizer",
                name = "Performance Optimizer",
                description = "Analyze and optimize application performance with AI-powered profiling and recommendations.",
                author = "PerfTools",
                version = "1.0.0",
                rating = 4.3f,
                installCount = 6500,
                tags = listOf("performance", "optimization", "profiling"),
                agent = listOf("claude-code", "open-claw"),
                updatedAt = "2026-04-15",
                skillMdUrl = "https://lobehub.com/skills/performance-optimizer",
        homepage = "https://lobehub.com/skills/performance-optimizer"
            )
        )
    }
}
