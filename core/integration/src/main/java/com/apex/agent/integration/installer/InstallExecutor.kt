package com.apex.agent.integration.installer

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.exception.IntegrationException
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationItemState
import com.apex.agent.integration.market.MarketItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 安装进度。
 */
data class InstallProgress(
    val itemId: String,
    val state: InstallState,
    val progress: Float = 0f,
    val message: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)
}

/**
 * 安装状态。
 */
enum class InstallState {
    PENDING,
    DOWNLOADING,
    INSTALLING,
    CONFIGURING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 安装请求。
 *
 * @param item 要安装的市场项
 * @param targetPath 安装目标路径（可选，默认使用系统目录）
 * @param config 安装配置（如 API Key、端点等）
 */
data class InstallRequest(
    val item: MarketItem,
    val targetPath: String? = null,
    val config: Map<String, String> = emptyMap()
)

/**
 * 安装结果。
 */
data class InstallResult(
    val success: Boolean,
    val itemId: String,
    val installedPath: String? = null,
    val message: String? = null,
    val error: String? = null
) {
    companion object {
        fun success(itemId: String, path: String?, message: String? = null) =
            InstallResult(success = true, itemId = itemId, installedPath = path, message = message)

        fun failure(itemId: String, error: String) =
            InstallResult(success = false, itemId = itemId, error = error)
    }
}

/**
 * 安装执行器。
 *
 * 负责真实的安装流程：
 * 1. 下载（URL 类型的 downloadUrl）
 * 2. 安装（解压/复制到目标路径）
 * 3. 配置（写入元数据）
 * 4. 注册到 InstalledManager
 *
 * 对于 npx/STDIO 类型的 MCP，安装只是记录配置（无需下载）。
 * 对于 Git 仓库类型，执行 git clone。
 * 对于 URL 类型，执行 HTTP 下载。
 *
 * # 使用示例
 *
 * ```
 * val installer = InstallExecutor(installedManager, context)
 *
 * // 安装（异步，可观察进度）
 * installer.install(item) { progress ->
 *     println("进度: ${progress.percent}% - ${progress.message}")
 * }
 *
 * // 观察所有安装进度
 * installer.activeInstalls.collect { map ->
 *     map.forEach { (id, progress) -> println("$id: ${progress.state}") }
 * }
 *
 * // 批量安装
 * installer.installBatch(listOf(item1, item2, item3))
 *
 * // 卸载
 * installer.uninstall("item_id")
 * ```
 */
class InstallExecutor(
    private val installedManager: InstalledManager,
    private val baseDir: File
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 活跃安装进度（itemId -> progress）。 */
    private val _activeInstalls = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val activeInstalls: StateFlow<Map<String, InstallProgress>> = _activeInstalls.asStateFlow()

    /** 安装历史记录。 */
    private val installHistory = ConcurrentHashMap<String, InstallResult>()

    /**
     * 异步安装。
     *
     * @param request 安装请求
     * @param onProgress 进度回调
     * @return 安装结果
     */
    suspend fun install(
        request: InstallRequest,
        onProgress: (InstallProgress) -> Unit = {}
    ): InstallResult = withContext(Dispatchers.IO) {
        val item = request.item
        updateProgress(item.id, InstallState.PENDING, 0f, "准备安装...")

        try {
            // 检查是否已安装
            if (installedManager.isInstalled(item.id)) {
                updateProgress(item.id, InstallState.COMPLETED, 1f, "已安装")
                return@withContext InstallResult.success(item.id, null, "Already installed")
            }

            // 根据来源类型执行不同的安装流程
            val result = when {
                // npx/STDIO 类型：只需记录配置
                item.metadata["transport"] == "stdio" ||
                item.downloadUrl.startsWith("npx ") -> {
                    installStdio(item, request)
                }
                // Git 仓库：git clone
                item.downloadUrl.contains("github.com") ||
                item.downloadUrl.contains("gitee.com") ||
                item.downloadUrl.contains("gitcode.com") -> {
                    installGitRepo(item, request)
                }
                // URL 下载
                item.downloadUrl.startsWith("http://") ||
                item.downloadUrl.startsWith("https://") -> {
                    installFromUrl(item, request, onProgress)
                }
                // 模型平台：只需配置 API Key
                item.category == IntegrationCategory.MODEL_PLATFORMS -> {
                    installModelPlatform(item, request)
                }
                // 默认：记录元数据
                else -> {
                    installMetadataOnly(item, request)
                }
            }

            // 注册到 InstalledManager
            if (result.success) {
                installedManager.install(item, result.installedPath)
                updateProgress(item.id, InstallState.COMPLETED, 1f, "安装完成")
            }

            installHistory[item.id] = result
            result

        } catch (e: Exception) {
            updateProgress(item.id, InstallState.FAILED, 0f, "安装失败: ${e.message}")
            val result = InstallResult.failure(item.id, e.message ?: "Unknown error")
            installHistory[item.id] = result
            result
        } finally {
            // 延迟清除进度（让 UI 有时间显示最终状态）
            scope.launch {
                kotlinx.coroutines.delay(3000)
                removeProgress(item.id)
            }
        }
    }

    /**
     * 便捷安装方法。
     */
    suspend fun install(
        item: MarketItem,
        targetPath: String? = null,
        config: Map<String, String> = emptyMap(),
        onProgress: (InstallProgress) -> Unit = {}
    ): InstallResult {
        return install(InstallRequest(item, targetPath, config), onProgress)
    }

    /**
     * 批量安装。
     */
    suspend fun installBatch(
        requests: List<InstallRequest>
    ): List<InstallResult> {
        return requests.map { install(it) }
    }

    /**
     * 卸载。
     *
     * @param itemId 项目 ID
     * @param deleteFiles 是否删除安装文件
     * @return true 卸载成功
     */
    suspend fun uninstall(itemId: String, deleteFiles: Boolean = true): Boolean {
        val item = installedManager.get(itemId) ?: return false

        if (deleteFiles && item.installedPath != null) {
            try {
                val dir = File(item.installedPath)
                if (dir.exists()) dir.deleteRecursively()
            } catch (_: Exception) {
                // 文件删除失败不影响注销
            }
        }

        return installedManager.uninstall(itemId)
    }

    /**
     * 获取安装历史。
     */
    fun getHistory(): Map<String, InstallResult> = installHistory.toMap()

    /**
     * 获取当前安装进度。
     */
    fun getProgress(itemId: String): InstallProgress? = _activeInstalls.value[itemId]

    /**
     * 是否正在安装。
     */
    fun isInstalling(itemId: String): Boolean {
        return _activeInstalls.value[itemId]?.state in listOf(
            InstallState.PENDING,
            InstallState.DOWNLOADING,
            InstallState.INSTALLING,
            InstallState.CONFIGURING
        )
    }

    // ===== 内部安装方法 =====

    private suspend fun installStdio(item: MarketItem, request: InstallRequest): InstallResult {
        updateProgress(item.id, InstallState.CONFIGURING, 0.5f, "配置 STDIO 服务...")
        // STDIO 类型无需下载，只需记录命令
        val command = item.downloadUrl.removePrefix("npx ")
        val enrichedItem = item.copy(
            metadata = item.metadata + mapOf(
                "command" to item.downloadUrl,
                "transport" to "stdio"
            )
        )
        // 用 enriched metadata 安装
        installedManager.install(enrichedItem)
        return InstallResult.success(item.id, null, "STDIO MCP configured: $command")
    }

    private suspend fun installGitRepo(item: MarketItem, request: InstallRequest): InstallResult {
        updateProgress(item.id, InstallState.DOWNLOADING, 0.2f, "Cloning repository...")

        val targetDir = File(request.targetPath ?: File(baseDir, "repos/${item.id}").path)
        targetDir.mkdirs()

        return try {
            val process = ProcessBuilder("git", "clone", "--depth", "1", item.downloadUrl, targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                updateProgress(item.id, InstallState.COMPLETED, 1f, "Repository cloned")
                InstallResult.success(item.id, targetDir.absolutePath, "Git clone successful")
            } else {
                val error = process.inputStream.bufferedReader().readText()
                InstallResult.failure(item.id, "Git clone failed: $error")
            }
        } catch (e: Exception) {
            InstallResult.failure(item.id, "Git clone error: ${e.message}")
        }
    }

    private suspend fun installFromUrl(
        item: MarketItem,
        request: InstallRequest,
        onProgress: (InstallProgress) -> Unit
    ): InstallResult {
        updateProgress(item.id, InstallState.DOWNLOADING, 0f, "Downloading...")

        val targetFile = File(request.targetPath ?: File(baseDir, "downloads/${item.id}").path)
        targetFile.parentFile?.mkdirs()

        return try {
            val connection = URL(item.downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Apex-Agent-Integration")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return InstallResult.failure(item.id, "HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val progress = downloadedBytes.toFloat() / totalBytes
                            updateProgress(item.id, InstallState.DOWNLOADING, progress,
                                "Downloading... ${downloadedBytes / 1024}KB / ${totalBytes / 1024}KB",
                                downloadedBytes, totalBytes)
                        }
                    }
                }
            }

            updateProgress(item.id, InstallState.INSTALLING, 0.9f, "Installing...")
            InstallResult.success(item.id, targetFile.absolutePath, "Downloaded ${downloadedBytes} bytes")
        } catch (e: Exception) {
            InstallResult.failure(item.id, "Download failed: ${e.message}")
        }
    }

    private suspend fun installModelPlatform(item: MarketItem, request: InstallRequest): InstallResult {
        updateProgress(item.id, InstallState.CONFIGURING, 0.5f, "Configuring model platform...")

        val apiKey = request.config["apiKey"] ?: ""
        val endpoint = request.config["endpoint"] ?: item.metadata["endpoint"] ?: ""

        val enrichedItem = item.copy(
            metadata = item.metadata + mapOf(
                "apiKey" to apiKey,
                "endpoint" to endpoint,
                "configured" to "true"
            )
        )
        installedManager.install(enrichedItem)
        return InstallResult.success(item.id, null, "Model platform configured")
    }

    private suspend fun installMetadataOnly(item: MarketItem, request: InstallRequest): InstallResult {
        updateProgress(item.id, InstallState.COMPLETED, 1f, "Registered")
        installedManager.install(item, request.targetPath)
        return InstallResult.success(item.id, request.targetPath, "Metadata registered")
    }

    // ===== 进度管理 =====

    private fun updateProgress(
        itemId: String,
        state: InstallState,
        progress: Float,
        message: String? = null,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0
    ) {
        val current = _activeInstalls.value.toMutableMap()
        current[itemId] = InstallProgress(itemId, state, progress, message, downloadedBytes, totalBytes)
        _activeInstalls.value = current
    }

    private fun removeProgress(itemId: String) {
        val current = _activeInstalls.value.toMutableMap()
        current.remove(itemId)
        _activeInstalls.value = current
    }
}
