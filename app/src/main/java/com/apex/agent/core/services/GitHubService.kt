package com.apex.agent.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub API 服务
 * 
 * 提供�?GitHub API 交互的功�?
 */
class GitHubService {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val baseUrl = "https://api.github.com"
    
    /**
     * 测试 GitHub 连接
     */
    suspend fun testConnection(authConfig: GitHubAuthConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthenticatedRequest("/user", authConfig)
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                Result.success("Connected as: ${json.optString("login")}")
            } else {
                Result.failure(Exception("Authentication failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取仓库信息
     */
    suspend fun getRepositoryInfo(owner: String, repo: String, authConfig: GitHubAuthConfig): Result<RepositoryInfo> = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthenticatedRequest("/repos/${owner}/${repo}", authConfig)
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                
                Result.success(
                    RepositoryInfo(
                        name = json.getString("name"),
                        fullName = json.getString("full_name"),
                        description = json.optString("description", ""),
                        defaultBranch = json.getString("default_branch"),
                        stars = json.getInt("stargazers_count"),
                        forks = json.getInt("forks_count"),
                        language = json.optString("language", ""),
                        size = json.getLong("size"),
                        isPrivate = json.getBoolean("private"),
                        updatedAt = json.getString("updated_at")
                    )
                )
            } else {
                Result.failure(Exception("Failed to fetch repository: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取文件�?
     */
    suspend fun getFileTree(
        owner: String,
        repo: String,
        branch: String,
        authConfig: GitHubAuthConfig,
        path: String = ""
    ): Result<List<GitHubFileNode>> = withContext(Dispatchers.IO) {
        try {
            val urlPath = if (path.isEmpty()) {
                "/repos/${owner}/${repo}/git/trees/${branch}?recursive=1"
            } else {
                "/repos/${owner}/${repo}/contents/${path}?ref=${branch}"
            }
            
            val request = buildAuthenticatedRequest(urlPath, authConfig)
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val nodes = parseFileTree(JSONObject(body ?: "{}"), path)
                Result.success(nodes)
            } else {
                Result.failure(Exception("Failed to fetch file tree: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取文件内容
     */
    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        branch: String,
        authConfig: GitHubAuthConfig
    ): Result<FileContent> = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthenticatedRequest(
                "/repos/${owner}/${repo}/contents/${path}?ref=${branch}",
                authConfig
            )
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                
                val content = json.optString("content", "")
                val decodedContent = if (json.optBoolean("encoded", false)) {
                    android.util.Base64.decode(content, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                } else {
                    content
                }
                
                Result.success(
                    FileContent(
                        path = path,
                        content = decodedContent,
                        size = json.optLong("size", 0),
                        sha = json.optString("sha"),
                        lastModified = System.currentTimeMillis()
                    )
                )
            } else {
                Result.failure(Exception("Failed to fetch file: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取 README 内容
     */
    suspend fun getReadme(
        owner: String,
        repo: String,
        branch: String,
        authConfig: GitHubAuthConfig
    ): Result<FileContent?> = withContext(Dispatchers.IO) {
        try {
            // 尝试常见�?README 文件�?
            val readmeNames = listOf("README.md", "README.rst", "README.txt", "readme.md")
            
            for (readmeName in readmeNames) {
                val result = getFileContent(owner, repo, readmeName, branch, authConfig)
                if (result.isSuccess) {
                    return@withContext result
                }
            }
            
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取分支列表
     */
    suspend fun getBranches(
        owner: String,
        repo: String,
        authConfig: GitHubAuthConfig
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthenticatedRequest("/repos/${owner}/${repo}/branches", authConfig)
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val jsonArray = org.json.JSONArray(body ?: "[]")
                val branches = mutableListOf<String>()
                
                for (i in 0 until jsonArray.length()) {
                    val branch = jsonArray.getJSONObject(i)
                    branches.add(branch.getString("name"))
                }
                
                Result.success(branches)
            } else {
                Result.failure(Exception("Failed to fetch branches: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 搜索仓库中的文件
     */
    suspend fun searchFiles(
        owner: String,
        repo: String,
        query: String,
        authConfig: GitHubAuthConfig
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = buildAuthenticatedRequest(
                "/search/code?q=repo:${owner}/${repo}+${encodedQuery}",
                authConfig
            )
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val items = json.optJSONArray("items") ?: org.json.JSONArray("[]")
                
                val paths = mutableListOf<String>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    paths.add(item.getString("path"))
                }
                
                Result.success(paths)
            } else {
                Result.failure(Exception("Search failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 构建认证请求
     */
    private fun buildAuthenticatedRequest(path: String, authConfig: GitHubAuthConfig): Request {
        val url = "${baseUrl}${path}"
        val builder = Request.Builder().url(url)
        
        when (authConfig.authType) {
            AuthType.PUBLIC -> {
                // 无需认证
            }
            AuthType.PERSONAL_TOKEN -> {
                authConfig.personalAccessToken?.let { token ->
                    builder.addHeader("Authorization", "token ${token}")
                }
            }
            AuthType.OAUTH -> {
                authConfig.oauthToken?.let { token ->
                    builder.addHeader("Authorization", "Bearer ${token}")
                }
            }
            AuthType.SSH -> {
                // SSH 需要通过 git 命令，这里暂不支�?
            }
        }
        
        // GitHub API v3 需要这�?header
        builder.addHeader("Accept", "application/vnd.github.v3+json")
        
        return builder.build()
    }
    
    /**
     * 解析文件�?JSON
     */
    private fun parseFileTree(json: JSONObject, basePath: String): List<GitHubFileNode> {
        val nodes = mutableListOf<GitHubFileNode>()
        
        // 检查是否是单个文件
        if (json.has("type")) {
            val type = json.getString("type")
            if (type == "file") {
                nodes.add(createFileNode(json, basePath))
            }
            return nodes
        }
        
        // 检查是否是目录列表
        if (json.has("tree")) {
            val tree = json.getJSONArray("tree")
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                val node = createNodeFromTreeItem(item, basePath)
                if (node != null) {
                    nodes.add(node)
                }
            }
        }
        
        return nodes
    }
    
    private fun createNodeFromTreeItem(item: JSONObject, basePath: String): GitHubFileNode? {
        val type = item.getString("type")
        val path = item.getString("path")
        val fullPath = if (basePath.isEmpty()) path else "${basePath}/${path}"
        
        return when (type) {
            "blob" -> createFileNode(item, fullPath)
            "tree" -> GitHubFileNode.Directory(
                name = path.substringAfterLast("/"),
                path = fullPath,
                children = emptyList(),
                size = 0
            )
            else -> null
        }
    }
    
    private fun createFileNode(json: JSONObject, path: String): GitHubFileNode.File {
        val name = path.substringAfterLast("/")
        val size = json.optLong("size", 0)
        val fileType = detectFileType(name)
        val language = detectLanguage(name)
        
        return GitHubFileNode.File(
            name = name,
            path = path,
            size = size,
            type = fileType,
            language = language,
            lastModified = System.currentTimeMillis(),
            sha = json.optString("sha")
        )
    }
    
    private fun detectFileType(fileName: String): FileType {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        
        return when (extension) {
            "kt", "java", "py", "js", "ts", "cpp", "c", "h", "rs", "go" -> FileType.SOURCE_CODE
            "xml", "json", "yml", "yaml", "toml", "properties" -> FileType.CONFIG
            "md", "txt", "rst" -> FileType.DOCUMENTATION
            "png", "jpg", "jpeg", "gif", "svg", "woff", "ttf" -> FileType.RESOURCE
            else -> FileType.OTHER
        }
    }
    
    private fun detectLanguage(fileName: String): String? {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        
        return when (extension) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "cpp", "cc", "cxx" -> "C++"
            "c" -> "C"
            "h" -> "C/C++ Header"
            "rs" -> "Rust"
            "go" -> "Go"
            "xml" -> "XML"
            "json" -> "JSON"
            "md" -> "Markdown"
            else -> null
        }
    }
}

/**
 * 仓库信息
 */
data class RepositoryInfo(
    val name: String,
    val fullName: String,
    val description: String,
    val defaultBranch: String,
    val stars: Int,
    val forks: Int,
    val language: String,
    val size: Long,  // KB
    val isPrivate: Boolean,
    val updatedAt: String
)

/**
 * 文件内容
 */
data class FileContent(
    val path: String,
    val content: String,
    val size: Long,
    val sha: String?,
    val lastModified: Long
)
