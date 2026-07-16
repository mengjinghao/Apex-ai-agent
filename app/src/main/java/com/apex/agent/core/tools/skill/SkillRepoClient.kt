package com.apex.agent.core.tools.skill

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

class SkillRepoClient private constructor(private val baseUrl: String) {

    companion object {
        private const val TAG = "SkillRepoClient"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_REPO_URL = "https://skill-repo.logistra.ai/api/v1"

        @Volatile private var INSTANCE: SkillRepoClient? = null

        fun getInstance(baseUrl: String = DEFAULT_REPO_URL): SkillRepoClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillRepoClient(baseUrl).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }

    private val gson = Gson()

    data class SkillInfo(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val category: String,
        val rating: Float,
        val installCount: Int,
        val downloadUrl: String,
        val checksum: String,
        val dependencies: List<String>,
        val permissions: List<String>,
        val size: Long,
        val createdAt: Long,
        val updatedAt: Long
    )

    data class SkillDetail(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val category: String,
        val rating: Float,
        val installCount: Int,
        val downloadUrl: String,
        val checksum: String,
        val dependencies: List<String>,
        val permissions: List<String>,
        val size: Long,
        val changelog: String,
        val readme: String,
        val screenshots: List<String>,
        val tags: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val versions: List<String>
    )


    data class UpdateCheckResult(
        val skillId: String,
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val updateSize: Long,
        val changelog: String,
        val downloadUrl: String,
        val checksum: String,
        val isIncremental: Boolean,
        val patchFromVersion: String?
    )

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("${baseUrl}/health")
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

    suspend fun getSkillList(page: Int = 1, pageSize: Int = 20, category: String? = null): Result<List<SkillInfo>> =
        withContext(Dispatchers.IO) {
            try {
                var url = "${baseUrl}/skills?page=${page}&size=${pageSize}"
                if (!category.isNullOrBlank()) {
                    url += "&category=${URLEncoder.encode(category, "UTF-8")}"
                }

                val connection = openConnection(url)
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val skills = parseSkillList(response)
                Result.success(skills)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get skill list", e)
                Result.failure(e)
            }
        }

    suspend fun getSkillDetail(skillId: String): Result<SkillDetail> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl}/skills/${URLEncoder.encode(skillId, "UTF-8")}"
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val detail = parseSkillDetail(response)
            Result.success(detail)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get skill detail for ${skillId}", e)
            Result.failure(e)
        }
    }

    suspend fun downloadSkill(
        skillId: String,
        version: String,
        outputFile: File,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl}/skills/${URLEncoder.encode(skillId, "UTF-8")}/download?version=${URLEncoder.encode(version, "UTF-8")}"
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val contentLength = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { bufferedInput ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalRead: Long = 0
                        var read: Int

                        while (bufferedInput.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            progressCallback?.invoke(totalRead, contentLength)
                        }
                        output.flush()
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download skill ${skillId}", e)
            outputFile.delete()
            Result.failure(e)
        }
    }

    suspend fun searchSkills(
        query: String,
        page: Int = 1,
        pageSize: Int = 20,
        category: String? = null,
        sortBy: String? = null
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            var url = "${baseUrl}/skills/search?q=${URLEncoder.encode(query, "UTF-8")}&page=${page}&size=${pageSize}"
            if (!category.isNullOrBlank()) {
                url += "&category=${URLEncoder.encode(category, "UTF-8")}"
            }
            if (!sortBy.isNullOrBlank()) {
                url += "&sort=${URLEncoder.encode(sortBy, "UTF-8")}"
            }

            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = parseSearchResult(response)
            Result.success(result)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to search skills", e)
            Result.failure(e)
        }
    }

    suspend fun checkForUpdate(
        skillId: String,
        currentVersion: String
    ): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl}/skills/${URLEncoder.encode(skillId, "UTF-8")}/updates?currentVersion=${URLEncoder.encode(currentVersion, "UTF-8")}"
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = parseUpdateCheckResult(response, skillId, currentVersion)
            Result.success(result)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check for update for ${skillId}", e)
            Result.failure(e)
        }
    }

    suspend fun downloadIncrementalUpdate(
        skillId: String,
        fromVersion: String,
        toVersion: String,
        outputFile: File,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl}/skills/${URLEncoder.encode(skillId, "UTF-8")}/patch?from=${URLEncoder.encode(fromVersion, "UTF-8")}&to=${URLEncoder.encode(toVersion, "UTF-8")}"
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val contentLength = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { bufferedInput ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalRead: Long = 0
                        var read: Int

                        while (bufferedInput.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            progressCallback?.invoke(totalRead, contentLength)
                        }
                        output.flush()
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download incremental update for ${skillId}", e)
            outputFile.delete()
            Result.failure(e)
        }
    }

    suspend fun getSkillVersions(skillId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl}/skills/${URLEncoder.encode(skillId, "UTF-8")}/versions"
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val versions = parseVersionList(response)
            Result.success(versions)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get versions for ${skillId}", e)
            Result.failure(e)
        }
    }

    suspend fun getCategories(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection("${baseUrl}/categories")
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val categories = parseCategories(response)
            Result.success(categories)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get categories", e)
            Result.failure(e)
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.doInput = true
        connection.setRequestProperty("User-Agent", "LogistraAgent/1.0")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun parseSkillList(json: String): List<SkillInfo> {
        val skills = mutableListOf<SkillInfo>()
        try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val items = root.getAsJsonArray("skills") ?: root.getAsJsonArray("data") ?: JsonArray()

            for (i in 0 until items.size()) {
                val item = items[i].asJsonObject
                skills.add(parseSkillInfo(item))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse skill list", e)
        }
        return skills
    }

    private fun parseSkillInfo(json: JsonObject): SkillInfo {
        return SkillInfo(
            id = json.get("id")?.asString ?: "",
            name = json.get("name")?.asString ?: "",
            description = json.get("description")?.asString ?: "",
            version = json.get("version")?.asString ?: "1.0.0",
            author = json.get("author")?.asString ?: "",
            category = json.get("category")?.asString ?: "",
            rating = json.get("rating")?.asFloat ?: 0f,
            installCount = json.get("installCount")?.asInt ?: 0,
            downloadUrl = json.get("downloadUrl")?.asString ?: "",
            checksum = json.get("checksum")?.asString ?: "",
            dependencies = json.getAsJsonArray("dependencies")?.map { it.asString } ?: emptyList(),
            permissions = json.getAsJsonArray("permissions")?.map { it.asString } ?: emptyList(),
            size = json.get("size")?.asLong ?: 0L,
            createdAt = json.get("createdAt")?.asLong ?: 0L,
            updatedAt = json.get("updatedAt")?.asLong ?: 0L
        )
    }

    private fun parseSkillDetail(json: String): SkillDetail {
        val obj = gson.fromJson(json, JsonObject::class.java)
        return SkillDetail(
            id = obj.get("id")?.asString ?: "",
            name = obj.get("name")?.asString ?: "",
            description = obj.get("description")?.asString ?: "",
            version = obj.get("version")?.asString ?: "1.0.0",
            author = obj.get("author")?.asString ?: "",
            category = obj.get("category")?.asString ?: "",
            rating = obj.get("rating")?.asFloat ?: 0f,
            installCount = obj.get("installCount")?.asInt ?: 0,
            downloadUrl = obj.get("downloadUrl")?.asString ?: "",
            checksum = obj.get("checksum")?.asString ?: "",
            dependencies = obj.getAsJsonArray("dependencies")?.map { it.asString } ?: emptyList(),
            permissions = obj.getAsJsonArray("permissions")?.map { it.asString } ?: emptyList(),
            size = obj.get("size")?.asLong ?: 0L,
            changelog = obj.get("changelog")?.asString ?: "",
            readme = obj.get("readme")?.asString ?: "",
            screenshots = obj.getAsJsonArray("screenshots")?.map { it.asString } ?: emptyList(),
            tags = obj.getAsJsonArray("tags")?.map { it.asString } ?: emptyList(),
            createdAt = obj.get("createdAt")?.asLong ?: 0L,
            updatedAt = obj.get("updatedAt")?.asLong ?: 0L,
            versions = obj.getAsJsonArray("versions")?.map { it.asString } ?: emptyList()
        )
    }

    private fun parseSearchResult(json: String): SearchResult {
        val root = gson.fromJson(json, JsonObject::class.java)
        val skills = parseSkillList(json)
        return SearchResult(
            skills = skills,
            total = root.get("total")?.asInt ?: skills.size,
            page = root.get("page")?.asInt ?: 1,
            pageSize = root.get("pageSize")?.asInt ?: 20,
            hasMore = root.get("hasMore")?.asBoolean ?: false
        )
    }

    private fun parseUpdateCheckResult(json: String, skillId: String, currentVersion: String): UpdateCheckResult {
        val obj = gson.fromJson(json, JsonObject::class.java)
        return UpdateCheckResult(
            skillId = skillId,
            hasUpdate = obj.get("hasUpdate")?.asBoolean ?: false,
            currentVersion = currentVersion,
            latestVersion = obj.get("latestVersion")?.asString ?: currentVersion,
            updateSize = obj.get("updateSize")?.asLong ?: 0L,
            changelog = obj.get("changelog")?.asString ?: "",
            downloadUrl = obj.get("downloadUrl")?.asString ?: "",
            checksum = obj.get("checksum")?.asString ?: "",
            isIncremental = obj.get("isIncremental")?.asBoolean ?: false,
            patchFromVersion = obj.get("patchFromVersion")?.asString
        )
    }

    private fun parseVersionList(json: String): List<String> {
        val root = gson.fromJson(json, JsonObject::class.java)
        return root.getAsJsonArray("versions")?.map { it.asString } ?: emptyList()
    }

    private fun parseCategories(json: String): List<String> {
        val root = gson.fromJson(json, JsonObject::class.java)
        return root.getAsJsonArray("categories")?.map { it.asString } ?: emptyList()
    }
}