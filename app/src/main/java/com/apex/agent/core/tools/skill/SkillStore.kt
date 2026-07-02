package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Skill 商店 - Marketplace 功能模块
 *
 * 功能�?
 * - Skill 商店浏览
 * - 搜索和筛�?
 * - 评分和评�?
 * - 下载管理
 * - 排行�?
 */
class SkillStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillStore"
        private const val STORE_CACHE_DIR = "store_cache"
        private const val REVIEWS_FILE = "reviews.json"
        private const val FAVORITES_FILE = "favorites.json"
        private const val DOWNLOAD_HISTORY_FILE = "download_history.json"
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5 分钟

        @Volatile private var INSTANCE: SkillStore? = null

        fun getInstance(context: Context): SkillStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    @Serializable
    data class StoreSkill(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val authorAvatar: String? = null,
        val category: String,
        val tags: List<String>,
        val rating: Float,
        val ratingCount: Int,
        val installCount: Int,
        val downloadUrl: String,
        val checksum: String,
        val size: Long,
        val changelog: String,
        val readme: String,
        val screenshots: List<String>,
        val dependencies: List<String>,
        val permissions: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val isFeatured: Boolean = false,
        val isTrending: Boolean = false,
        val isNew: Boolean = false
    )

    @Serializable
    data class SkillReview(
        val id: String,
        val skillId: String,
        val userId: String,
        val userName: String,
        val rating: Float,
        val title: String,
        val content: String,
        val createdAt: Long,
        val helpfulCount: Int = 0,
        val isVerified: Boolean = false
    )

    @Serializable
    data class ReviewStats(
        val skillId: String,
        val averageRating: Float,
        val totalReviews: Int,
        val ratingDistribution: Map<String, Int>,
        val recentReviews: List<SkillReview>
    )

    @Serializable
    data class DownloadRecord(
        val skillId: String,
        val skillName: String,
        val version: String,
        val downloadedAt: Long,
        val source: String
    )

    @Serializable
    data class UserFavorite(
        val skillId: String,
        val addedAt: Long
    )

    @Serializable
    data class LeaderboardEntry(
        val rank: Int,
        val skillId: String,
        val skillName: String,
        val installCount: Int,
        val rating: Float,
        val category: String,
        val change: Int // 排名变化，正数表示上�?
    )

    @Serializable
    data class SearchFilters(
        val query: String = "",
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val minRating: Float? = null,
        val sortBy: SortOption = SortOption.RELEVANCE,
        val page: Int = 1,
        val pageSize: Int = 20
    )

    enum class SortOption {
        RELEVANCE,
        POPULAR,
        TOP_RATED,
        NEWEST,
        UPDATED,
        NAME_ASC,
        NAME_DESC
    }

    // ========== 状�?==========

    private val _storeSkills = MutableStateFlow<List<StoreSkill>>(emptyList())
    val storeSkills: StateFlow<List<StoreSkill>> = _storeSkills.asStateFlow()

    private val _featuredSkills = MutableStateFlow<List<StoreSkill>>(emptyList())
    val featuredSkills: StateFlow<List<StoreSkill>> = _featuredSkills.asStateFlow()

    private val _trendingSkills = MutableStateFlow<List<StoreSkill>>(emptyList())
    val trendingSkills: StateFlow<List<StoreSkill>> = _trendingSkills.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val leaderboard: StateFlow<List<LeaderboardEntry>> = _leaderboard.asStateFlow()

    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults: StateFlow<SearchResults?> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    data class SearchResults(
        val skills: List<StoreSkill>,
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val hasMore: Boolean,
        val facets: SearchFacets
    )

    data class SearchFacets(
        val categories: Map<String, Int>,
        val tags: Map<String, Int>,
        val ratingRanges: Map<String, Int>
    )

    data class Category(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val skillCount: Int,
        val color: String? = null
    )

    // 本地数据
    private val localReviews = mutableMapOf<String, MutableList<SkillReview>>()
    private val localFavorites = mutableMapOf<String, UserFavorite>()
    private val downloadHistory = mutableListOf<DownloadRecord>()

    private var lastCacheTime = 0L
    private var cachedSkills = listOf<StoreSkill>()

    private val skillManager by lazy { SkillManager.getInstance(context) }
    private val skillRepoClient by lazy { SkillRepoClient.getInstance() }

    init {
        loadLocalData()
    }

    // ========== 公开 API ==========

    /**
     * 刷新商店数据
     */
    suspend fun refreshStore(): RefreshResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _error.value = null

        try {
            // 检查连�?
            val connected = skillRepoClient.testConnection()
            if (!connected) {
                // 使用缓存或示例数�?
                if (cachedSkills.isEmpty()) {
                    loadSampleData()
                }
                return@withContext RefreshResult(
                    success = false,
                    message = "Cannot connect to marketplace server. Showing cached data.",
                    skills = cachedSkills
                )
            }

            // 获取分类
            val categoriesResult = skillRepoClient.getCategories()
            categoriesResult.getOrNull()?.let { cats ->
                _categories.value = cats.mapIndexed { index, name ->
                    Category(
                        id = name.lowercase().replace(" ", "_"),
                        name = name,
                        description = "${name} 类技�?,
                        icon = getCategoryIcon(index),
                        skillCount = 0
                    )
                }
            }

            // 获取所有技�?
            val allSkills = mutableListOf<StoreSkill>()
            var page = 1
            var hasMore = true

            while (hasMore) {
                val result = skillRepoClient.getSkillList(page = page, pageSize = 50)
                result.getOrNull()?.let { skills ->
                    allSkills.addAll(skills.map { it.toStoreSkill() })
                    hasMore = skills.size >= 50
                    page++
                } ?: run {
                    hasMore = false
                }
            }

            // 更新缓存
            cachedSkills = allSkills
            lastCacheTime = System.currentTimeMillis()
            saveCacheToFile()

            // 更新状�?
            _storeSkills.value = allSkills
            _featuredSkills.value = allSkills.filter { it.isFeatured }
            _trendingSkills.value = allSkills.sortedByDescending { it.installCount }.take(10).map {
                it.copy(isTrending = true)
            }

            // 构建排行�?
            buildLeaderboard(allSkills)

            RefreshResult(
                success = true,
                message = "Store refreshed successfully. ${allSkills.size} skills available.",
                skills = allSkills
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to refresh store", e)
            _error.value = e.message

            if (cachedSkills.isEmpty()) {
                loadSampleData()
            }

            RefreshResult(
                success = false,
                message = "Error: ${e.message}. Showing cached data.",
                skills = cachedSkills
            )
        } finally {
            _isLoading.value = false
        }
    }

    private fun com.apex.agent.core.tools.skill.SkillRepoClient.SkillInfo.toStoreSkill() = StoreSkill(
        id = id,
        name = name,
        description = description,
        version = version,
        author = author,
        category = category,
        tags = emptyList(),
        rating = rating,
        ratingCount = 0,
        installCount = installCount,
        downloadUrl = downloadUrl,
        checksum = checksum,
        size = size,
        changelog = "",
        readme = "",
        screenshots = emptyList(),
        dependencies = dependencies,
        permissions = permissions,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFeatured = false,
        isTrending = installCount > 1000,
        isNew = System.currentTimeMillis() - createdAt < 7 * 24 * 60 * 60 * 1000
    )

    /**
     * 搜索技�?
     */
    suspend fun search(filters: SearchFilters): SearchResults = withContext(Dispatchers.IO) {
        _isLoading.value = true

        try {
            val result = skillRepoClient.searchSkills(
                query = filters.query,
                page = filters.page,
                pageSize = filters.pageSize,
                category = filters.category,
                sortBy = filters.sortBy.name.lowercase()
            )

            val skills = result.getOrNull()?.skills?.map { it.toStoreSkill() } ?: emptyList()

            // 应用本地筛�?
            var filtered = skills

            filters.minRating?.let { minRating ->
                filtered = filtered.filter { it.rating >= minRating }
            }

            // 统计
            val facets = SearchFacets(
                categories = skills.groupBy { it.category }.mapValues { it.value.size },
                tags = skills.flatMap { it.tags }.groupBy { it }.mapValues { it.value.size },
                ratingRanges = mapOf(
                    "5" to skills.count { it.rating >= 4.5f },
                    "4" to skills.count { it.rating >= 4.0f },
                    "3" to skills.count { it.rating >= 3.0f },
                    "2" to skills.count { it.rating >= 2.0f },
                    "1" to skills.count { it.rating >= 1.0f }
                )
            )

            // 排序
            filtered = when (filters.sortBy) {
                SortOption.POPULAR -> filtered.sortedByDescending { it.installCount }
                SortOption.TOP_RATED -> filtered.sortedByDescending { it.rating }
                SortOption.NEWEST -> filtered.sortedByDescending { it.createdAt }
                SortOption.UPDATED -> filtered.sortedByDescending { it.updatedAt }
                SortOption.NAME_ASC -> filtered.sortedBy { it.name }
                SortOption.NAME_DESC -> filtered.sortedByDescending { it.name }
                else -> filtered
            }

            val searchResults = SearchResults(
                skills = filtered,
                total = filtered.size,
                page = filters.page,
                pageSize = filters.pageSize,
                hasMore = skills.size >= filters.pageSize,
                facets = facets
            )

            _searchResults.value = searchResults
            searchResults
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 获取技能详�?
     */
    suspend fun getSkillDetail(skillId: String): StoreSkill? = withContext(Dispatchers.IO) {
        // 先检查本地缓�?
        cachedSkills.find { it.id == skillId }?.let { return@withContext it }

        // 从服务器获取
        val result = skillRepoClient.getSkillDetail(skillId)
        result.getOrNull()?.let { detail ->
            StoreSkill(
                id = detail.id,
                name = detail.name,
                description = detail.description,
                version = detail.version,
                author = detail.author,
                category = detail.category,
                tags = detail.tags,
                rating = detail.rating,
                ratingCount = 0,
                installCount = detail.installCount,
                downloadUrl = detail.downloadUrl,
                checksum = detail.checksum,
                size = detail.size,
                changelog = detail.changelog,
                readme = detail.readme,
                screenshots = detail.screenshots,
                dependencies = detail.dependencies,
                permissions = detail.permissions,
                createdAt = detail.createdAt,
                updatedAt = detail.updatedAt,
                isFeatured = false,
                isTrending = detail.installCount > 1000,
                isNew = System.currentTimeMillis() - detail.createdAt < 7 * 24 * 60 * 60 * 1000
            )
        }
    }

    /**
     * 下载并安装技�?
     */
    suspend fun downloadSkill(
        skillId: String,
        version: String? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(skillId, 0f)
        }

        try {
            // 获取技能信�?
            val detail = if (version != null) {
                val versions = skillRepoClient.getSkillVersions(skillId)
                if (versions.isFailure) {
                    return@withContext DownloadResult(
                        success = false,
                        skillId = skillId,
                        errorMessage = "Failed to get versions"
                    )
                }

                val targetVersion = versions.getOrNull()?.find { it == version } ?: versions.getOrNull()?.firstOrNull()
                    ?: return@withContext DownloadResult(
                        success = false,
                        skillId = skillId,
                        errorMessage = "Version not found: ${version}"
                    )

                skillRepoClient.getSkillDetail(skillId).getOrNull()
                    ?: return@withContext DownloadResult(
                        success = false,
                        skillId = skillId,
                        errorMessage = "Failed to get skill detail"
                    )
            } else {
                skillRepoClient.getSkillDetail(skillId).getOrNull()
                    ?: return@withContext DownloadResult(
                        success = false,
                        skillId = skillId,
                        errorMessage = "Skill not found"
                    )
            }

            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                put(skillId, 0.2f)
            }

            // 创建缓存目录
            val cacheDir = File(context.cacheDir, STORE_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val downloadFile = File(cacheDir, "${skillId}_${detail.version}.zip")

            // 下载
            val downloadResult = skillRepoClient.downloadSkill(
                skillId = skillId,
                version = detail.version,
                outputFile = downloadFile
            ) { downloaded, total ->
                if (total > 0) {
                    val progress = 0.2f + 0.6f * (downloaded.toFloat() / total)
                    _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                        put(skillId, progress)
                    }
                }
            }

            if (downloadResult.isFailure) {
                return@withContext DownloadResult(
                    success = false,
                    skillId = skillId,
                    errorMessage = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                )
            }

            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                put(skillId, 0.85f)
            }

            // 导入
            val importResult = skillManager.importSkillFromZip(downloadFile)

            // 清理
            downloadFile.delete()

            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                put(skillId, 1.0f)
            }

            // 记录下载历史
            downloadHistory.add(
                DownloadRecord(
                    skillId = skillId,
                    skillName = detail.name,
                    version = detail.version,
                    downloadedAt = System.currentTimeMillis(),
                    source = "marketplace"
                )
            )
            saveDownloadHistory()

            // 刷新技能列�?
            skillManager.refreshAvailableSkills()

            if (importResult.contains("imported") || importResult.contains("成功")) {
                DownloadResult(
                    success = true,
                    skillId = skillId,
                    skillName = detail.name,
                    version = detail.version,
                    installedPath = skillManager.getAvailableSkills()[skillId]?.directory?.absolutePath
                )
            } else {
                DownloadResult(
                    success = false,
                    skillId = skillId,
                    errorMessage = importResult
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download skill: ${skillId}", e)
            DownloadResult(
                success = false,
                skillId = skillId,
                errorMessage = e.message
            )
        } finally {
            // 清理进度
            kotlinx.coroutines.delay(1000)
            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                remove(skillId)
            }
        }
    }

    /**
     * 获取技能评�?
     */
    suspend fun getReviews(skillId: String): ReviewStats = withContext(Dispatchers.IO) {
        val reviews = localReviews[skillId] ?: emptyList()

        val allReviews = reviews + getSampleReviews(skillId)

        val avgRating = if (allReviews.isNotEmpty()) {
            allReviews.map { it.rating }.average().toFloat()
        } else 0f

        val distribution = allReviews.groupBy { it.rating.toInt().toString() }
            .mapValues { it.value.size }

        ReviewStats(
            skillId = skillId,
            averageRating = avgRating,
            totalReviews = allReviews.size,
            ratingDistribution = distribution,
            recentReviews = allReviews.sortedByDescending { it.createdAt }.take(10)
        )
    }

    /**
     * 添加评论
     */
    suspend fun addReview(
        skillId: String,
        userId: String,
        userName: String,
        rating: Float,
        title: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val review = SkillReview(
                id = "review_${System.currentTimeMillis()}",
                skillId = skillId,
                userId = userId,
                userName = userName,
                rating = rating,
                title = title,
                content = content,
                createdAt = System.currentTimeMillis(),
                helpfulCount = 0,
                isVerified = true
            )

            localReviews.getOrPut(skillId) { mutableListOf() }.add(0, review)
            saveLocalReviews()

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to add review", e)
            false
        }
    }

    /**
     * 标记评论有帮�?
     */
    suspend fun markReviewHelpful(reviewId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            localReviews.values.forEach { reviews ->
                reviews.find { it.id == reviewId }?.let { review ->
                    val index = reviews.indexOf(review)
                    reviews[index] = review.copy(helpfulCount = review.helpfulCount + 1)
                }
            }
            saveLocalReviews()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 添加到收�?
     */
    suspend fun addToFavorites(skillId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            localFavorites[skillId] = UserFavorite(
                skillId = skillId,
                addedAt = System.currentTimeMillis()
            )
            saveFavorites()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从收藏移�?
     */
    suspend fun removeFromFavorites(skillId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            localFavorites.remove(skillId)
            saveFavorites()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否已收藏
     */
    fun isFavorite(skillId: String): Boolean {
        return localFavorites.containsKey(skillId)
    }

    /**
     * 获取收藏列表
     */
    fun getFavorites(): List<String> {
        return localFavorites.keys.toList()
    }

    /**
     * 获取下载历史
     */
    fun getDownloadHistory(): List<DownloadRecord> {
        return downloadHistory.sortedByDescending { it.downloadedAt }
    }

    /**
     * 获取排行�?
     */
    fun getLeaderboard(type: LeaderboardType = LeaderboardType.INSTALLS): List<LeaderboardEntry> {
        return when (type) {
            LeaderboardType.INSTALLS -> _leaderboard.value.sortedByDescending { it.installCount }
            LeaderboardType.RATING -> _leaderboard.value.sortedByDescending { it.rating }
            LeaderboardType.TRENDING -> _leaderboard.value.filter { it.change > 0 }
                .sortedByDescending { it.change }
        }
    }

    enum class LeaderboardType {
        INSTALLS,
        RATING,
        TRENDING
    }

    // ========== 私有方法 ==========

    private fun buildLeaderboard(skills: List<StoreSkill>) {
        _leaderboard.value = skills.sortedByDescending { it.installCount }
            .take(100)
            .mapIndexed { index, skill ->
                LeaderboardEntry(
                    rank = index + 1,
                    skillId = skill.id,
                    skillName = skill.name,
                    installCount = skill.installCount,
                    rating = skill.rating,
                    category = skill.category,
                    change = (0..10).random() - 5 // 模拟排名变化
                )
            }
    }

    private fun loadLocalData() {
        try {
            // 加载收藏
            val favoritesFile = File(context.filesDir, FAVORITES_FILE)
            if (favoritesFile.exists()) {
                val favorites = Json.decodeFromString<List<UserFavorite>>(favoritesFile.readText())
                favorites.forEach { localFavorites[it.skillId] = it }
            }

            // 加载下载历史
            val historyFile = File(context.filesDir, DOWNLOAD_HISTORY_FILE)
            if (historyFile.exists()) {
                val history = Json.decodeFromString<List<DownloadRecord>>(historyFile.readText())
                downloadHistory.addAll(history)
            }

            // 加载评论
            val reviewsFile = File(context.filesDir, REVIEWS_FILE)
            if (reviewsFile.exists()) {
                val allReviews = Json.decodeFromString<Map<String, List<SkillReview>>>(reviewsFile.readText())
                localReviews.putAll(allReviews)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load local data", e)
        }
    }

    private fun saveFavorites() {
        try {
            val favoritesFile = File(context.filesDir, FAVORITES_FILE)
            favoritesFile.writeText(Json.encodeToString(localFavorites.values.toList()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save favorites", e)
        }
    }

    private fun saveDownloadHistory() {
        try {
            val historyFile = File(context.filesDir, DOWNLOAD_HISTORY_FILE)
            historyFile.writeText(Json.encodeToString(downloadHistory))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save download history", e)
        }
    }

    private fun saveLocalReviews() {
        try {
            val reviewsFile = File(context.filesDir, REVIEWS_FILE)
            reviewsFile.writeText(Json.encodeToString(localReviews))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save reviews", e)
        }
    }

    private fun saveCacheToFile() {
        try {
            val cacheDir = File(context.filesDir, STORE_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheFile = File(cacheDir, "store_cache.json")
            cacheFile.writeText(Json.encodeToString(cachedSkills))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save cache", e)
        }
    }

    private fun loadSampleData() {
        // 示例数据
        val sampleSkills = listOf(
            StoreSkill(
                id = "sample_file_organizer",
                name = "文件整理助手",
                description = "自动整理下载目录中的文件，按类型分类",
                version = "1.2.0",
                author = "Logistra Team",
                category = "文件管理",
                tags = listOf("文件", "整理", "自动�?),
                rating = 4.5f,
                ratingCount = 128,
                installCount = 3250,
                downloadUrl = "",
                checksum = "",
                size = 256000,
                changelog = "- 优化分类逻辑\n- 新增自定义规�?,
                readme = "# 文件整理助手",
                screenshots = emptyList(),
                dependencies = emptyList(),
                permissions = emptyList(),
                createdAt = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000,
                updatedAt = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000,
                isFeatured = true,
                isTrending = true
            ),
            StoreSkill(
                id = "sample_system_monitor",
                name = "系统监控专家",
                description = "实时监控 CPU、内存、电池状态，异常时自动通知",
                version = "2.0.0",
                author = "DevMaster",
                category = "系统",
                tags = listOf("监控", "系统", "通知"),
                rating = 4.8f,
                ratingCount = 256,
                installCount = 5600,
                downloadUrl = "",
                checksum = "",
                size = 128000,
                changelog = "- 全新 UI\n- 支持自定义阈�?,
                readme = "# 系统监控专家",
                screenshots = emptyList(),
                dependencies = emptyList(),
                permissions = listOf("android.permission.RECEIVE_BOOT_COMPLETED"),
                createdAt = System.currentTimeMillis() - 60 * 24 * 60 * 60 * 1000,
                updatedAt = System.currentTimeMillis(),
                isFeatured = true,
                isTrending = true,
                isNew = false
            ),
            StoreSkill(
                id = "sample_backup_pro",
                name = "备份专业�?,
                description = "一键备份应用数据、照片、文件到云端或本�?,
                version = "3.1.0",
                author = "CloudTech",
                category = "工具",
                tags = listOf("备份", "�?, "数据"),
                rating = 4.2f,
                ratingCount = 89,
                installCount = 1200,
                downloadUrl = "",
                checksum = "",
                size = 512000,
                changelog = "- 支持更多云服务\n- 增量备份优化",
                readme = "# 备份专业�?,
                screenshots = emptyList(),
                dependencies = listOf("sample_file_organizer"),
                permissions = emptyList(),
                createdAt = System.currentTimeMillis() - 10 * 24 * 60 * 60 * 1000,
                updatedAt = System.currentTimeMillis() - 1 * 24 * 60 * 60 * 1000,
                isFeatured = false,
                isTrending = false,
                isNew = true
            )
        )

        cachedSkills = sampleSkills
        _storeSkills.value = sampleSkills
        _featuredSkills.value = sampleSkills.filter { it.isFeatured }
        _trendingSkills.value = sampleSkills.filter { it.isTrending }

        _categories.value = listOf(
            Category("automation", "自动�?, "自动化任务和工作�?, "�?, 150),
            Category("file_management", "文件管理", "文件和目录操作工�?, "📁", 89),
            Category("system", "系统", "系统监控和管理工�?, "⚙️", 120),
            Category("network", "网络", "网络相关功能", "🌐", 67),
            Category("development", "开�?, "开发者工�?, "💻", 95),
            Category("communication", "通信", "通知和消息工�?, "📱", 45)
        )

        buildLeaderboard(sampleSkills)
    }

    private fun getSampleReviews(skillId: String): List<SkillReview> {
        // 生成一些示例评�?
        return listOf(
            SkillReview(
                id = "${skillId}_review_1",
                skillId = skillId,
                userId = "user_1",
                userName = "技术达�?,
                rating = 5f,
                title = "非常好用�?,
                content = "这个技能真的帮了我大忙，省了我很多时间。强烈推荐！",
                createdAt = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000
            ),
            SkillReview(
                id = "${skillId}_review_2",
                skillId = skillId,
                userId = "user_2",
                userName = "普通用�?,
                rating = 4f,
                title = "不错，但可以更好",
                content = "功能挺全的，就是有时候有点慢，希望后续优化�?,
                createdAt = System.currentTimeMillis() - 5 * 24 * 60 * 60 * 1000
            )
        )
    }

    private fun getCategoryIcon(index: Int): String {
        val icons = listOf("�?, "📁", "⚙️", "🌐", "💻", "📱", "🔧", "📊", "🎨", "🔒")
        return icons.getOrElse(index % icons.size) { "📦" }
    }

    // ========== 数据�?==========

    data class RefreshResult(
        val success: Boolean,
        val message: String,
        val skills: List<StoreSkill>
    )

    data class DownloadResult(
        val success: Boolean,
        val skillId: String,
        val skillName: String? = null,
        val version: String? = null,
        val installedPath: String? = null,
        val errorMessage: String? = null
    )

    // ========== 工具方法 ==========

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    fun formatDate(timestamp: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        ).format(formatter)
    }

    fun formatInstallCount(count: Int): String {
        return when {
            count < 1000 -> count.toString()
            count < 10000 -> "${count / 1000}.${(count % 1000) / 100}K"
            count < 1000000 -> "${count / 1000}K"
            else -> "${count / 1000000}M+"
        }
    }
}
