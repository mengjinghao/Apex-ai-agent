package com.apex.plugins.skill

import android.content.Context
import android.os.Environment
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

class SkillPluginMarketplace private constructor(private val context: Context) : SkillPluginMarket {

    companion object {
        private const val TAG = "SkillPluginMarketplace"

        @Volatile private var INSTANCE: SkillPluginMarketplace? = null

        private const val MARKETPLACE_URL = "https://api.apex-agent.ai/plugins/v1"
        private const val FALLBACK_MARKETPLACE_URL = "https://marketplace.example.com/api/v1"
        private var customBaseUrl: String? = null

        fun setBaseUrl(url: String) {
            customBaseUrl = url
        }
        fun getBaseUrl(): String = customBaseUrl ?: MARKETPLACE_URL

        private const val CACHE_VALIDITY_MS = 3600000L

        fun getInstance(context: Context): SkillPluginMarketplace {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPluginMarketplace(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
        private val _searchResults = MutableStateFlow<List<SkillPluginListing>>(emptyList())
        private val _featuredPlugins = MutableStateFlow<List<SkillPluginListing>>(emptyList())
        private val _popularPlugins = MutableStateFlow<List<SkillPluginListing>>(emptyList())
        private val _updates = MutableStateFlow<List<SkillPluginUpdate>>(emptyList())
        private val _isLoading = MutableStateFlow(false)
        val searchResults: Flow<List<SkillPluginListing>> = _searchResults.asStateFlow()
        val featuredPlugins: Flow<List<SkillPluginListing>> = _featuredPlugins.asStateFlow()
        val popularPlugins: Flow<List<SkillPluginListing>> = _popularPlugins.asStateFlow()
        val availableUpdates: Flow<List<SkillPluginUpdate>> = _updates.asStateFlow()
        val isLoading: Flow<Boolean> = _isLoading.asStateFlow()
        private val cacheDir: File
        get() {
            val cacheDir = File(context.cacheDir, SkillPluginConstants.PLUGIN_CACHE_DIR)
        if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
        return cacheDir
        }
        private val downloadedPluginsDir: File
        get() {
            val downloadDir = File(context.filesDir, "downloaded_plugins")
        if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
        return downloadDir
        }

    override suspend fun searchPlugins(
        query: String,
        category: SkillPluginCategory?
    ): List<SkillPluginListing> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val cacheKey = "search_${query}_${category ?: "all"}"
        val cached = getCachedListings(cacheKey)
        if (cached != null) {
                _searchResults.value = cached
                return@withContext cached
            }
        val results = performSearch(query, category)
            cacheListings(cacheKey, results)
            _searchResults.value = results
            results
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to search plugins", e)
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun getPluginDetails(pluginId: String): SkillPluginListing? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "details_${pluginId}"
        val cached = getCachedListing(cacheKey)
        if (cached != null) return@withContext cached

            val details = fetchPluginDetails(pluginId)
            details?.let { cacheListing(cacheKey, it) }
            details
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get plugin details for ${pluginId}", e)
            null
        }
    }

    override suspend fun downloadPlugin(pluginId: String): File = withContext(Dispatchers.IO) {
        try {
            val listing = getPluginDetails(pluginId)
                ?: throw MarketplaceException("Plugin not found: ${pluginId}")
        val downloadFile = File(downloadedPluginsDir, "${pluginId}.zip")
        if (downloadFile.exists()) {
                AppLogger.d(TAG, "Plugin already downloaded: ${pluginId}")
                return@withContext downloadFile
            }

            downloadFile(listing.downloadUrl, downloadFile)
            AppLogger.i(TAG, "Downloaded plugin: ${pluginId} to ${downloadFile.absolutePath}")
            downloadFile
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download plugin ${pluginId}", e)
        throw MarketplaceException("Download failed: ${e.message}", e)
        }
    }

    override suspend fun checkUpdates(): List<SkillPluginUpdate> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val updates = fetchAvailableUpdates()
            _updates.value = updates
            updates
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check updates", e)
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun getFeaturedPlugins(): List<SkillPluginListing> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "featured"
        val cached = getCachedListings(cacheKey)
        if (cached != null) {
                _featuredPlugins.value = cached
                return@withContext cached
            }
        val featured = fetchFeaturedPlugins()
            cacheListings(cacheKey, featured)
            _featuredPlugins.value = featured
            featured
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get featured plugins", e)
            emptyList()
        }
    }

    override suspend fun getPopularPlugins(limit: Int): List<SkillPluginListing> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "popular_${limit}"
        val cached = getCachedListings(cacheKey)
        if (cached != null) {
                _popularPlugins.value = cached
                return@withContext cached
            }
        val popular = fetchPopularPlugins(limit)
            cacheListings(cacheKey, popular)
            _popularPlugins.value = popular
            popular
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get popular plugins", e)
            emptyList()
        }
    }

    override suspend fun getRecommendedPlugins(userId: String): List<SkillPluginListing> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "recommended_${userId}"
        val cached = getCachedListings(cacheKey)
        if (cached != null) return@withContext cached

            val recommended = fetchRecommendedPlugins(userId)
            cacheListings(cacheKey, recommended)
            recommended
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get recommended plugins", e)
            emptyList()
        }
    }
        private suspend fun performSearch(
        query: String,
        category: SkillPluginCategory?
    ): List<SkillPluginListing> {
        return try {
            val url = buildSearchUrl(query, category)
        val response = fetchFromNetwork(url)
            json.decodeFromString<List<SkillPluginListingData>>(response).map { it.toListing() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Search failed, using mock data", e)
            getMockPlugins().filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true) ||
                        (category != null && it.category == category)
            }
        }
    }
        private suspend fun fetchPluginDetails(pluginId: String): SkillPluginListing? {
        return try {
            val url = "${getBaseUrl()}/plugins/${pluginId}"
        val response = fetchFromNetwork(url)
            json.decodeFromString<SkillPluginListingData>(response).toListing()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch details failed, using mock", e)
            getMockPlugins().find { it.id == pluginId }
        }
    }
        private suspend fun fetchAvailableUpdates(): List<SkillPluginUpdate> {
        return try {
            val url = "${getBaseUrl()}/plugins/updates"
        val response = fetchFromNetwork(url)
            json.decodeFromString<List<SkillPluginUpdateData>>(response).map { it.toUpdate() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch updates failed", e)
            emptyList()
        }
    }
        private suspend fun fetchFeaturedPlugins(): List<SkillPluginListing> {
        return try {
            val url = "${getBaseUrl()}/plugins/featured"
        val response = fetchFromNetwork(url)
            json.decodeFromString<List<SkillPluginListingData>>(response).map { it.toListing() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch featured failed", e)
            getMockPlugins().take(5)
        }
    }
        private suspend fun fetchPopularPlugins(limit: Int): List<SkillPluginListing> {
        return try {
            val url = "${getBaseUrl()}/plugins/popular?limit=${limit}"
        val response = fetchFromNetwork(url)
            json.decodeFromString<List<SkillPluginListingData>>(response).map { it.toListing() }.take(limit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch popular failed", e)
            getMockPlugins().sortedByDescending { it.downloadCount }.take(limit)
        }
    }
        private suspend fun fetchRecommendedPlugins(userId: String): List<SkillPluginListing> {
        return try {
            val url = "${getBaseUrl()}/plugins/recommended?userId=${userId}"
        val response = fetchFromNetwork(url)
            json.decodeFromString<List<SkillPluginListingData>>(response).map { it.toListing() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch recommended failed", e)
            getMockPlugins().shuffled().take(5)
        }
    }
        private fun buildSearchUrl(query: String, category: SkillPluginCategory): String {
        return buildString {
            append("${getBaseUrl()}/plugins/search?q=${query}")
        if (category != null) {
                append("&category=${category.name.lowercase()}")
            }
        }
    }
        private suspend fun fetchFromNetwork(urlString: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(urlString)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
                    throw MarketplaceException("HTTP ${response.code}: ${body.ifBlank { response.message }}")
                }
                body
            }
        }
    }
        private fun downloadFile(urlString: String, destination: File) {
        val request = Request.Builder()
            .url(urlString)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MarketplaceException("Download failed: HTTP ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
        private fun getCachedListing(key: String): SkillPluginListing? {
        val cacheFile = File(cacheDir, "${key.hashCode()}.json")
        if (!cacheFile.exists()) return null

        val cacheValid = System.currentTimeMillis() - cacheFile.lastModified() < CACHE_VALIDITY_MS
        if (!cacheValid) {
            cacheFile.delete()
        return null
        }
        return try {
            val content = cacheFile.readText()
            json.decodeFromString<SkillPluginListingData>(content).toListing()
        } catch (e: Exception) {
            null
        }
    }
        private fun cacheListing(key: String, listing: SkillPluginListing) {
        val cacheFile = File(cacheDir, "${key.hashCode()}.json")
        try {
            val data = SkillPluginListingData.from(listing)
            cacheFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache listing", e)
        }
    }
        private fun getCachedListings(key: String): List<SkillPluginListing>? {
        val cacheFile = File(cacheDir, "${key.hashCode()}.json")
        if (!cacheFile.exists()) return null

        val cacheValid = System.currentTimeMillis() - cacheFile.lastModified() < CACHE_VALIDITY_MS
        if (!cacheValid) {
            cacheFile.delete()
        return null
        }
        return try {
            val content = cacheFile.readText()
            json.decodeFromString<List<SkillPluginListingData>>(content).map { it.toListing() }
        } catch (e: Exception) {
            null
        }
    }
        private fun cacheListings(key: String, listings: List<SkillPluginListing>) {
        val cacheFile = File(cacheDir, "${key.hashCode()}.json")
        try {
            val data = listings.map { SkillPluginListingData.from(it) }
            cacheFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache listings", e)
        }
    }
        private fun getMockPlugins(): List<SkillPluginListing> {
        return listOf(
            createMockListing("plugin.ai.analyzer", "AI Code Analyzer", SkillPluginCategory.ANALYSIS),
            createMockListing("plugin.automation.helper", "Automation Helper", SkillPluginCategory.AUTOMATION),
            createMockListing("plugin.integrations.webhook", "Webhook Integration", SkillPluginCategory.INTEGRATION),
            createMockListing("plugin.viz.charts", "Chart Visualization", SkillPluginCategory.VISUALIZATION),
            createMockListing("plugin.security.scanner", "Security Scanner", SkillPluginCategory.SECURITY),
            createMockListing("plugin.rec.smart", "Smart Recommender", SkillPluginCategory.RECOMMENDATION)
        )
    }
        private fun createMockListing(
        id: String,
        name: String,
        category: SkillPluginCategory
    ): SkillPluginListing {
        return object : SkillPluginListing {
            override val id: String = id
            override val name: String = name
            override val version: String = "1.0.0"
            override val author: String = "Marketplace"
            override val description: String = "A useful ${name} plugin for the skill system"
            override val category: SkillPluginCategory = category
            override val downloadUrl: String = "${SkillPluginMarketplace.getBaseUrl()}/download/${id}"
            override val iconUrl: String? = null
            override val rating: Double = 4.5
            override val downloadCount: Int = (100..10000).random()
            override val tags: List<String> = listOf(category.name.lowercase(), "featured")
            override val createdAt: Long = Instant.now().toEpochMilli() - 86400000
            override val updatedAt: Long = Instant.now().toEpochMilli()
        }
    }

    @Serializable
    private data class SkillPluginListingData(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val category: String,
        val downloadUrl: String,
        val iconUrl: String? = null,
        val rating: Double = 0.0,
        val downloadCount: Int = 0,
        val tags: List<String> = emptyList(),
        val createdAt: Long = 0,
        val updatedAt: Long = 0
    ) {
        fun toListing(): SkillPluginListing {
            return object : SkillPluginListing {
                override val id: String = this@SkillPluginListingData.id
                override val name: String = this@SkillPluginListingData.name
                override val version: String = this@SkillPluginListingData.version
                override val author: String = this@SkillPluginListingData.author
                override val description: String = this@SkillPluginListingData.description
                override val category: SkillPluginCategory = try {
                    SkillPluginCategory.valueOf(this@SkillPluginListingData.category.uppercase())
                } catch (e: Exception) {
                    SkillPluginCategory.CUSTOM
                }
                override val downloadUrl: String = this@SkillPluginListingData.downloadUrl
                override val iconUrl: String? = this@SkillPluginListingData.iconUrl
                override val rating: Double = this@SkillPluginListingData.rating
                override val downloadCount: Int = this@SkillPluginListingData.downloadCount
                override val tags: List<String> = this@SkillPluginListingData.tags
                override val createdAt: Long = this@SkillPluginListingData.createdAt
                override val updatedAt: Long = this@SkillPluginListingData.updatedAt
            }
        }

        companion object {
            fun from(listing: SkillPluginListing): SkillPluginListingData {
                return SkillPluginListingData(
                    id = listing.id,
                    name = listing.name,
                    version = listing.version,
                    author = listing.author,
                    description = listing.description,
                    category = listing.category.name.lowercase(),
                    downloadUrl = listing.downloadUrl,
                    iconUrl = listing.iconUrl,
                    rating = listing.rating,
                    downloadCount = listing.downloadCount,
                    tags = listing.tags,
                    createdAt = listing.createdAt,
                    updatedAt = listing.updatedAt
                )
            }
        }
    }

    @Serializable
    private data class SkillPluginUpdateData(
        val pluginId: String,
        val currentVersion: String,
        val latestVersion: String,
        val updateUrl: String,
        val changelog: String,
        val isSecurityUpdate: Boolean = false
    ) {
        fun toUpdate(): SkillPluginUpdate {
            return object : SkillPluginUpdate {
                override val pluginId: String = this@SkillPluginUpdateData.pluginId
                override val currentVersion: String = this@SkillPluginUpdateData.currentVersion
                override val latestVersion: String = this@SkillPluginUpdateData.latestVersion
                override val updateUrl: String = this@SkillPluginUpdateData.updateUrl
                override val changelog: String = this@SkillPluginUpdateData.changelog
                override val isSecurityUpdate: Boolean = this@SkillPluginUpdateData.isSecurityUpdate
            }
        }
    }
        class MarketplaceException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
