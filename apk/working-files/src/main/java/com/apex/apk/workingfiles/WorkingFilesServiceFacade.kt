package com.apex.apk.workingfiles

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apex.lib.workingfiles.AgentMode
import com.apex.lib.workingfiles.CodePreview
import com.apex.lib.workingfiles.FileEvent
import com.apex.lib.workingfiles.WorkingFolder
import com.apex.lib.workingfiles.WorkingFolderManager
import com.apex.lib.workingfiles.WorkingFilesWatcher
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Working Files APK 的核心服务实现。
 *
 * **职责**：
 *   - 管理用户为三种 Agent 模式绑定的本地工作文件夹
 *   - 实时监听文件变更（基于 java.nio WatchService / inotify）
 *   - 提供代码文件预览（语言检测 + 简单 token 化）
 *   - 对其他 APK 暴露统一的 Kotlin API
 *
 * **使用方式**（其他 APK）：
 *   ```kotlin
 *   val wfm = TypedServiceRegistry.get<WorkingFilesServiceFacade>() ?: error("...")
 *   wfm.bindFolder(WorkingFolder(id="default", displayName="工作区", path="/sdcard/agent"))
 *   wfm.files("default", "").forEach { println(it.name) }
 *   ```
 *
 * **关于 SAF（Storage Access Framework）**：
 *   Android 11+ 强制使用 SAF 选择目录。本 Facade 同时支持：
 *   - 传统 File API（路径直接访问，需 MANAGE_EXTERNAL_STORAGE）
 *   - DocumentFile API（SAF 选中后通过 URI 访问）
 *   自动根据路径格式选择。
 */
class WorkingFilesServiceFacade(private val context: Context) {

    private const val TAG_SUB = "WorkingFilesFacade"

    private val manager = WorkingFolderManager()

    private val _fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 256)
    val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

    /**
     * 绑定一个工作文件夹。
     * @param folderId 文件夹 ID（业务自定义）
     * @param displayName 显示名
     * @param path 本地路径（如 /sdcard/agent）或 SAF URI 字符串
     * @param mode 关联的 Agent 模式（NORMAL / MULTI_AGENT / BURST / ALL）
     */
    suspend fun bindFolder(
        folderId: String,
        displayName: String,
        path: String,
        mode: String = "ALL"
    ): BridgeResult<Unit> = bridgeRun {
        val agentMode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.ALL)
        val folder = WorkingFolder(
            id = folderId,
            displayName = displayName,
            path = path,
            assignedAgentMode = agentMode
        )
        manager.bindFolder(folder)
        ApexLog.i(ApexSuite.ApkId.WORKING_FILES, "[$TAG_SUB] folder bound: $folderId → $path ($mode)")

        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.WORKING_FOLDER_BOUND,
            mapOf("folderId" to folderId, "path" to path, "mode" to mode),
            ApexSuite.ApkId.WORKING_FILES
        )
    }

    /**
     * 解绑。
     */
    suspend fun unbindFolder(folderId: String): BridgeResult<Boolean> = bridgeRun {
        manager.unbindFolder(folderId)
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.WORKING_FOLDER_UNBOUND,
            mapOf("folderId" to folderId),
            ApexSuite.ApkId.WORKING_FILES
        )
        true
    }

    /**
     * 列出所有绑定的文件夹。
     */
    fun listFolders(): List<WorkingFolderDto> = manager.listFolders().map { it.toDto() }

    /**
     * 列出某 Agent 模式下的所有文件夹。
     */
    fun listFoldersForMode(mode: String): List<WorkingFolderDto> {
        val agentMode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.ALL)
        return manager.foldersFor(agentMode).map { it.toDto() }
    }

    /**
     * 列出某文件夹下的文件/子目录。
     * @param folderId 文件夹 ID
     * @param relativePath 相对路径（如 "src/main/java"）
     * @return 文件条目列表
     */
    suspend fun listFiles(
        folderId: String,
        relativePath: String = ""
    ): BridgeResult<List<FileEntry>> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val target = if (relativePath.isBlank()) File(folder.path) else File(folder.path, relativePath)
        if (!target.exists()) return@bridgeRun emptyList<FileEntry>()
        target.listFiles()?.map { f ->
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                relativePath = f.absolutePath.removePrefix(folder.path).removePrefix("/"),
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                lastModified = f.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    /**
     * 读取文件内容（文本）。
     */
    suspend fun readFile(folderId: String, relativePath: String): BridgeResult<String> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val file = File(folder.path, relativePath)
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("file not found: $relativePath")
        file.readText()
    }

    /**
     * 写入文件内容。
     */
    suspend fun writeFile(
        folderId: String,
        relativePath: String,
        content: String,
        append: Boolean = false
    ): BridgeResult<Boolean> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val file = File(folder.path, relativePath)
        file.parentFile?.mkdirs()
        if (append) file.appendText(content) else file.writeText(content)
        ApexLog.d(ApexSuite.ApkId.WORKING_FILES, "[$TAG_SUB] wrote: $relativePath (${content.length} chars)")
        true
    }

    /**
     * 删除文件或目录。
     */
    suspend fun deleteFile(folderId: String, relativePath: String): BridgeResult<Boolean> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val file = File(folder.path, relativePath)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    /**
     * 创建目录。
     */
    suspend fun createDirectory(folderId: String, relativePath: String): BridgeResult<Boolean> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        File(folder.path, relativePath).mkdirs()
    }

    /**
     * 检查文件是否存在。
     */
    suspend fun exists(folderId: String, relativePath: String): BridgeResult<Boolean> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        File(folder.path, relativePath).exists()
    }

    /**
     * 加载代码文件（含语言检测 + 简单 token 化）。
     */
    suspend fun loadCodeFile(folderId: String, relativePath: String): BridgeResult<CodeFileDto> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val file = File(folder.path, relativePath)
        val code = CodePreview.load(file) ?: throw IllegalArgumentException("failed to load: $relativePath")
        val tokens = CodePreview.tokenize(code.content, code.language).map { t ->
            CodeTokenDto(text = t.text, type = t.type.name)
        }
        CodeFileDto(
            path = code.path,
            language = code.language,
            lineCount = code.lineCount,
            totalChars = code.totalChars,
            content = code.content,
            tokens = tokens
        )
    }

    /**
     * 订阅某文件夹的文件变更事件（通过 LocalSocket 推送）。
     *
     * 实现机制：
     *   1. 启动一个 [LocalStreamServer] 监听通道 `workingfiles.<folderId>`
     *   2. 启动一个协程收集 [WorkingFilesWatcher.events] 的事件
     *   3. 把每个 FileEvent 序列化为 JSON 后推送到 LocalStream
     *
     * 调用方通过 [com.apex.sdk.bridge.LocalStreamClient] 连接通道，实时收到变更。
     *
     * @return streamChannel 名（null 表示文件夹未绑定或监听失败）
     */
    fun subscribeChanges(folderId: String): String? {
        val watcher = manager.watcher(folderId) ?: return null
        val channelName = "workingfiles.$folderId"

        // 启动 LocalStream 服务端
        com.apex.sdk.bridge.StreamChannelRegistry.open(channelName)

        // 启动协程：watcher.events → LocalStream output
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            watcher.events.collect { event ->
                val json = serializeFileEvent(event)
                _fileEvents.tryEmit(event)

                // 通过 StreamChannelRegistry 推送到所有连接的客户端（同进程零延迟）
                com.apex.sdk.bridge.StreamChannelRegistry.sendToChannel(
                    channelName,
                    json.toByteArray(Charsets.UTF_8)
                )

                // 同时发布到 SuiteEventBus，让跨进程的 APK 也能收到
                com.apex.sdk.bridge.SuiteEventBus.publish(
                    com.apex.sdk.bridge.SuiteEventTypes.WORKING_FOLDER_CHANGED,
                    mapOf(
                        "folderId" to folderId,
                        "event" to event.javaClass.simpleName,
                        "path" to event.path
                    ),
                    ApexSuite.ApkId.WORKING_FILES
                )
            }
        }
        ApexLog.i(ApexSuite.ApkId.WORKING_FILES, "[$TAG_SUB] subscribed changes: $folderId → $channelName")
        return channelName
    }

    /**
     * 关闭订阅。
     */
    fun unsubscribeChanges(folderId: String) {
        com.apex.sdk.bridge.StreamChannelRegistry.close("workingfiles.$folderId")
    }

    /**
     * 把 [FileEvent] 序列化为 JSON 字符串。
     */
    private fun serializeFileEvent(event: FileEvent): String {
        val type = when (event) {
            is FileEvent.Created -> "created"
            is FileEvent.Modified -> "modified"
            is FileEvent.Deleted -> "deleted"
        }
        return """{"type":"$type","path":"${event.path.replace("\\", "/").replace("\"", "\\\"")}","timestamp":${System.currentTimeMillis()}}"""
    }

    // ============================================================
    // SAF（Storage Access Framework）支持
    // ============================================================

    /**
     * 通过 SAF URI 绑定文件夹（Android 11+ 推荐方式）。
     *
     * @param folderId 业务 ID
     * @param displayName 显示名
     * @param uriString SAF URI 字符串（如 content://com.android.externalstorage.documents/...）
     * @param mode 关联的 Agent 模式
     */
    suspend fun bindFolderByUri(
        folderId: String,
        displayName: String,
        uriString: String,
        mode: String = "ALL"
    ): BridgeResult<Unit> = bridgeRun {
        val agentMode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.ALL)
        val uri = Uri.parse(uriString)
        val docFile = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("invalid SAF URI: $uriString")
        if (!docFile.isDirectory) throw IllegalArgumentException("URI is not a directory: $uriString")

        // 把 URI 转换为可访问的本地路径（缓存在应用私有目录的映射）
        // 简化：直接以 URI 字符串作为 path 存储，后续操作通过 DocumentFile API
        val folder = WorkingFolder(
            id = folderId,
            displayName = displayName,
            path = uriString,  // SAF 模式下 path 是 URI 字符串
            assignedAgentMode = agentMode
        )
        manager.bindFolder(folder)
        ApexLog.i(ApexSuite.ApkId.WORKING_FILES, "[$TAG_SUB] SAF folder bound: $folderId → $uriString")

        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.WORKING_FOLDER_BOUND,
            mapOf("folderId" to folderId, "path" to uriString, "mode" to mode, "source" to "saf"),
            ApexSuite.ApkId.WORKING_FILES
        )
    }

    /**
     * 通过 SAF URI 列出文件。
     */
    suspend fun listFilesByUri(
        folderId: String,
        relativePath: String = ""
    ): BridgeResult<List<FileEntry>> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        if (!folder.path.startsWith("content://")) {
            throw IllegalArgumentException("folder is not a SAF URI: ${folder.path}")
        }
        val uri = Uri.parse(folder.path)
        var docFile = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("invalid URI")
        // 进入子目录
        if (relativePath.isNotBlank()) {
            for (part in relativePath.split("/").filter { it.isNotBlank() }) {
                docFile = docFile.findFile(part) ?: throw IllegalArgumentException("subdir not found: $part")
            }
        }
        docFile.listFiles().map { f ->
            FileEntry(
                name = f.name ?: "",
                path = f.uri.toString(),
                relativePath = if (relativePath.isBlank()) (f.name ?: "") else "$relativePath/${f.name}",
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                lastModified = f.lastModified()
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    /**
     * 通过 SAF URI 读取文件。
     */
    suspend fun readFileByUri(folderId: String, relativePath: String): BridgeResult<String> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val uri = Uri.parse(folder.path)
        var docFile = DocumentFile.fromTreeUri(context, uri) ?: throw IllegalArgumentException("invalid URI")
        for (part in relativePath.split("/").filter { it.isNotBlank() }) {
            docFile = docFile.findFile(part) ?: throw IllegalArgumentException("file not found: $part")
        }
        if (!docFile.isFile) throw IllegalArgumentException("not a file: $relativePath")
        context.contentResolver.openInputStream(docFile.uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("failed to open input stream")
    }

    /**
     * 通过 SAF URI 写入文件。
     */
    suspend fun writeFileByUri(
        folderId: String,
        relativePath: String,
        content: String,
        append: Boolean = false
    ): BridgeResult<Boolean> = bridgeRun {
        val folder = manager.getFolder(folderId) ?: throw IllegalArgumentException("folder not found: $folderId")
        val uri = Uri.parse(folder.path)
        var docFile = DocumentFile.fromTreeUri(context, uri) ?: throw IllegalArgumentException("invalid URI")
        val parts = relativePath.split("/").filter { it.isNotBlank() }
        // 进入或创建子目录
        for (i in 0 until parts.size - 1) {
            docFile = docFile.findFile(parts[i]) ?: docFile.createDirectory(parts[i])!!
        }
        val fileName = parts.last()
        var targetFile = docFile.findFile(fileName)
        if (targetFile == null) {
            targetFile = docFile.createFile("application/octet-stream", fileName)
                ?: throw IllegalStateException("failed to create file: $fileName")
        }
        context.contentResolver.openOutputStream(targetFile.uri, if (append) "wa" else "w")?.use { output ->
            if (append) output.write(content.toByteArray())
            else output.write(content.toByteArray())
        } ?: throw IllegalStateException("failed to open output stream")
        true
    }

    /**
     * 释放所有资源。
     */
    fun shutdown() {
        manager.listFolders().forEach { manager.unbindFolder(it.id) }
    }
}

// DTO 定义
data class WorkingFolderDto(
    val id: String,
    val displayName: String,
    val path: String,
    val assignedAgentMode: String
)

data class FileEntry(
    val name: String,
    val path: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

data class CodeFileDto(
    val path: String,
    val language: String,
    val lineCount: Int,
    val totalChars: Int,
    val content: String,
    val tokens: List<CodeTokenDto>
)

data class CodeTokenDto(
    val text: String,
    val type: String  // PLAIN / KEYWORD / STRING / COMMENT / NUMBER / OPERATOR / IDENTIFIER
)

// 扩展函数
private fun WorkingFolder.toDto() = WorkingFolderDto(
    id = id,
    displayName = displayName,
    path = path,
    assignedAgentMode = assignedAgentMode.name
)
