package com.apex.agent.kernel.burst

import android.content.Context
import android.util.Log
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class PluginRepositoryClient(
    private val context: Context
) : PluginRepository {
    companion object {
        private const val TAG = "PluginRepositoryClient"
        const val DEFAULT_REPO_URL = "https://raw.githubusercontent.com/mengjinghao/Apex-agent/main/plugins/repo/index.json"
    }

    private var repoUrl: String = DEFAULT_REPO_URL
    private var cachedIndex: RepositoryIndex? = null
    private val cacheDir: File = File(context.cacheDir, "plugin-repo").also { it.mkdirs() }
    private val downloadsDir: File = File(context.filesDir, "plugin-downloads").also { it.mkdirs() }
    private val pluginInfoCache = ConcurrentHashMap<String, RemotePluginInfo>()

    override suspend fun fetchIndex(): RepositoryOperationResult = withContext(Dispatchers.IO) {
        try {
            val json = httpGet(repoUrl)
            val root = JSONObject(json)
            val pluginsArray = root.getJSONArray("plugins")
            val plugins = mutableListOf<RemotePluginInfo>()

            for (i in 0 until pluginsArray.length()) {
                val p = pluginsArray.getJSONObject(i)
                val versionsArray = p.optJSONArray("versions") ?: JSONArray()
                val versions = mutableListOf<RemotePluginVersion>()

                for (j in 0 until versionsArray.length()) {
                    val v = versionsArray.getJSONObject(j)
                    val depsArray = v.optJSONArray("dependencies") ?: JSONArray()
                    val deps = mutableListOf<PluginDependencyInfo>()
                    for (k in 0 until depsArray.length()) {
                        val d = depsArray.getJSONObject(k)
                        deps.add(PluginDependencyInfo(
                            pluginId = d.getString("pluginId"),
                            versionRange = d.optString("versionRange", "*"),
                            required = d.optBoolean("required", true)
                        ))
                    }
                    versions.add(RemotePluginVersion(
                        version = v.getString("version"),
                        apkUrl = v.getString("apkUrl"),
                        checksum = v.optString("checksum", ""),
                        checksumType = try { ChecksumType.valueOf(v.optString("checksumType", "SHA256")) } catch (e: Exception) { Log.w(TAG, "Invalid checksumType, using SHA256", e); ChecksumType.SHA256 },
                        minApiVersion = v.optString("minApiVersion", ""),
                        dependencies = deps,
                        releaseNotes = v.optString("releaseNotes", ""),
                        fileSize = v.optLong("fileSize", 0),
                        publishedAt = v.optLong("publishedAt", 0),
                        isCompatible = v.optBoolean("isCompatible", true)
                    ))
                }

                val info = RemotePluginInfo(
                    pluginId = p.getString("pluginId"),
                    name = p.optString("name", p.getString("pluginId")),
                    description = p.optString("description", ""),
                    author = p.optString("author", ""),
                    versions = versions,
                    tags = p.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                    categories = p.optJSONArray("categories")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                    iconUrl = p.optString("iconUrl", ""),
                    websiteUrl = p.optString("websiteUrl", ""),
                    sourceUrl = p.optString("sourceUrl", ""),
                    readmeUrl = p.optString("readmeUrl", ""),
                    rating = p.optDouble("rating", 0.0).toFloat(),
                    downloadCount = p.optLong("downloadCount", 0)
                )
                plugins.add(info)
                pluginInfoCache[info.pluginId] = info
            }

            cachedIndex = RepositoryIndex(
                repoUrl = repoUrl,
                repoName = root.optString("repoName", "Apex Plugin Repository"),
                repoVersion = root.optString("repoVersion", "1.0"),
                plugins = plugins,
                updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
            )

            saveIndexCache(root)
            RepositoryOperationResult.Success("Index updated: ${plugins.size} plugins found")
        } catch (e: Exception) {
            loadIndexCache()
            cachedIndex?.let {
                RepositoryOperationResult.Success("Using cached index (${it.plugins.size} plugins)")
            } ?: run {
                RepositoryOperationResult.Error("Failed to fetch index: ${e.message}")
            }
        }
    }

    override suspend fun search(query: String): List<RemotePluginInfo> {
        val index = cachedIndex ?: return emptyList()
        val q = query.lowercase()
        return index.plugins.filter { info ->
            info.pluginId.lowercase().contains(q) ||
            info.name.lowercase().contains(q) ||
            info.description.lowercase().contains(q) ||
            info.tags.any { it.lowercase().contains(q) } ||
            info.author.lowercase().contains(q)
        }
    }

    override suspend fun getPluginInfo(pluginId: String): RemotePluginInfo? {
        return pluginInfoCache[pluginId] ?: cachedIndex?.plugins?.find { it.pluginId == pluginId }
    }

    override suspend fun getLatestVersion(pluginId: String): RemotePluginVersion? {
        val info = getPluginInfo(pluginId) ?: return null
        return info.versions.maxByOrNull { compareVersions(it.version, "0.0.0") }
    }

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String,
        onProgress: ((Long, Long) -> Unit)?  
    ): PluginDownloadResult = withContext(Dispatchers.IO) {
        try {
            val info = getPluginInfo(pluginId) ?: return@withContext PluginDownloadResult(
                false, errorMessage = "Plugin not found: $pluginId"
            )
            val ver = info.versions.find { it.version == version }
                ?: return@withContext PluginDownloadResult(false, errorMessage = "Version $version not found")

            val destFile = File(downloadsDir, "${pluginId}-${version}.apk")
            if (destFile.exists()) {
                if (verifyChecksum(destFile, ver.checksum, ver.checksumType)) {
                    return@withContext PluginDownloadResult(true, destFile.absolutePath, pluginId, version)
                }
                destFile.delete()
            }

            val url = URL(ver.apkUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()

            val totalBytes = conn.contentLength.toLong()
            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                onProgress?.invoke(totalRead, totalBytes)
            }

            outputStream.close()
            inputStream.close()
            conn.disconnect()

            if (ver.checksum.isNotBlank()) {
                if (!verifyChecksum(destFile, ver.checksum, ver.checksumType)) {
                    destFile.delete()
                    return@withContext PluginDownloadResult(false, errorMessage = "Checksum verification failed")
                }
            }

            PluginDownloadResult(true, destFile.absolutePath, pluginId, version)
        } catch (e: Exception) {
            PluginDownloadResult(false, errorMessage = "Download failed: ${e.message}")
        }
    }

    override suspend fun checkForUpdates(installedPlugins: Map<String, String>): List<PluginUpdateInfo> {
        fetchIndex()
        val index = cachedIndex ?: return emptyList()
        val updates = mutableListOf<PluginUpdateInfo>()

        installedPlugins.forEach { (pluginId, currentVersion) ->
            val remote = index.plugins.find { it.pluginId == pluginId } ?: return@forEach
            val latest = remote.versions.maxByOrNull { compareVersions(it.version, "0.0.0") } ?: return@forEach
            if (compareVersions(latest.version, currentVersion) > 0) {
                updates.add(PluginUpdateInfo(
                    pluginId = pluginId,
                    currentVersion = currentVersion,
                    latestVersion = latest.version,
                    apkUrl = latest.apkUrl,
                    checksum = latest.checksum,
                    releaseNotes = latest.releaseNotes,
                    fileSize = latest.fileSize
                ))
            }
        }

        return updates
    }

    override fun getIndex(): RepositoryIndex? = cachedIndex

    override fun getCachedPlugins(): List<RemotePluginInfo> {
        return cachedIndex?.plugins ?: pluginInfoCache.values.toList()
    }

    override fun setRepositoryUrl(url: String) {
        repoUrl = url
        cachedIndex = null
        pluginInfoCache.clear()
    }

    override fun getRepositoryUrl(): String = repoUrl

    private fun verifyChecksum(file: File, expected: String, type: ChecksumType): Boolean {
        if (expected.isBlank()) return true
        val digest = MessageDigest.getInstance(type.name)
        val hash = file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            digest.digest()
        }
        val actual = hash.joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun parseVersion(version: String): List<Int> {
        return version.split(".").map { it.toIntOrNull() ?: 0 }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = parseVersion(v1)
        val parts2 = parseVersion(v2)
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun saveIndexCache(root: JSONObject) {
        try {
            File(cacheDir, "index.json").writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "saveIndexCache failed", e)
        }
    }

    private fun loadIndexCache() {
        try {
            val file = File(cacheDir, "index.json")
            if (file.exists()) {
                val json = file.readText()
                val root = JSONObject(json)
                val pluginsArray = root.getJSONArray("plugins")
                val plugins = mutableListOf<RemotePluginInfo>()
                for (i in 0 until pluginsArray.length()) {
                    val p = pluginsArray.getJSONObject(i)
                    val versionsArray = p.optJSONArray("versions") ?: JSONArray()
                    val versions = mutableListOf<RemotePluginVersion>()
                    for (j in 0 until versionsArray.length()) {
                        val v = versionsArray.getJSONObject(j)
                        versions.add(RemotePluginVersion(
                            version = v.optString("version", "1.0.0"),
                            apkUrl = v.optString("apkUrl", ""),
                            checksum = v.optString("checksum", ""),
                            releaseNotes = v.optString("releaseNotes", "")
                        ))
                    }
                    plugins.add(RemotePluginInfo(
                        pluginId = p.optString("pluginId", ""),
                        name = p.optString("name", p.optString("pluginId", "")),
                        description = p.optString("description", ""),
                        author = p.optString("author", ""),
                        versions = versions
                    ))
                }
                cachedIndex = RepositoryIndex(
                    repoUrl = repoUrl,
                    repoName = root.optString("repoName", "Cached"),
                    repoVersion = root.optString("repoVersion", "1.0"),
                    plugins = plugins
                )
                plugins.forEach { pluginInfoCache[it.pluginId] = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadIndexCache failed", e)
        }
    }

    private suspend fun httpGet(urlStr: String): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.connect()
        conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
