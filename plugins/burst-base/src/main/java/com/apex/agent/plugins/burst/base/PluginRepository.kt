package com.apex.agent.plugins.burst.base

data class RepositoryIndex(
    val repoUrl: String,
    val repoName: String,
    val repoVersion: String,
    val plugins: List<RemotePluginInfo>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class RemotePluginInfo(
    val pluginId: String,
    val name: String,
    val description: String,
    val author: String,
    val versions: List<RemotePluginVersion>,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val iconUrl: String = "",
    val websiteUrl: String = "",
    val sourceUrl: String = "",
    val readmeUrl: String = "",
    val rating: Float = 0f,
    val downloadCount: Long = 0
)

data class RemotePluginVersion(
    val version: String,
    val apkUrl: String,
    val checksum: String,
    val checksumType: ChecksumType = ChecksumType.SHA256,
    val minApiVersion: String = "",
    val dependencies: List<PluginDependencyInfo> = emptyList(),
    val releaseNotes: String = "",
    val fileSize: Long = 0,
    val publishedAt: Long = 0,
    val isCompatible: Boolean = true
)

enum class ChecksumType {
    SHA256, MD5
}

data class PluginDependencyInfo(
    val pluginId: String,
    val versionRange: String,
    val required: Boolean = true
)

data class PluginDownloadResult(
    val success: Boolean,
    val localPath: String = "",
    val pluginId: String = "",
    val version: String = "",
    val errorMessage: String = ""
)

sealed class RepositoryOperationResult {
    data class Success(val message: String) : RepositoryOperationResult()
    data class Error(val message: String) : RepositoryOperationResult()
    data class Progress(val pluginId: String, val bytesDownloaded: Long, val totalBytes: Long) : RepositoryOperationResult()
}

interface PluginRepository {
    suspend fun fetchIndex(): RepositoryOperationResult
    suspend fun search(query: String): List<RemotePluginInfo>
    suspend fun getPluginInfo(pluginId: String): RemotePluginInfo?
    suspend fun getLatestVersion(pluginId: String): RemotePluginVersion?
    suspend fun downloadPlugin(
        pluginId: String,
        version: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): PluginDownloadResult
    suspend fun checkForUpdates(installedPlugins: Map<String, String>): List<PluginUpdateInfo>
    fun getIndex(): RepositoryIndex?
    fun getCachedPlugins(): List<RemotePluginInfo>
    fun setRepositoryUrl(url: String)
    fun getRepositoryUrl(): String
}

data class PluginUpdateInfo(
    val pluginId: String,
    val currentVersion: String,
    val latestVersion: String,
    val apkUrl: String,
    val checksum: String,
    val releaseNotes: String,
    val fileSize: Long
)
