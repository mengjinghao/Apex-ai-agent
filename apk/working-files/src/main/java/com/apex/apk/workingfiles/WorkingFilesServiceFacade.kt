package com.apex.apk.workingfiles

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.apex.lib.workingfiles.AgentMode
import com.apex.lib.workingfiles.CodeEditorFacade
import com.apex.lib.workingfiles.CodePreview
import com.apex.lib.workingfiles.FileEvent
import com.apex.lib.workingfiles.WorkingFolder
import com.apex.lib.workingfiles.WorkingFolderManager
import com.apex.lib.workingfiles.WorkingFilesWatcher
import com.apex.lib.workingfiles.agent.AgentMode as FlowAgentMode
import com.apex.lib.workingfiles.agent.AgentSession
import com.apex.lib.workingfiles.agent.AgentSessionStatus
import com.apex.lib.workingfiles.agent.AgentStep
import com.apex.lib.workingfiles.agent.AgentStepType
import com.apex.lib.workingfiles.diff.FileDiff
import com.apex.lib.workingfiles.snapshot.ChangeSource
import com.apex.lib.workingfiles.snapshot.FileSnapshot
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
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
    /** 代码编辑器门面 — 文件快照 / diff / Agent 流程 / 回退 */
    val codeEditor: CodeEditorFacade = CodeEditorFacade(context)

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

    // ============================================================
    // VSCode 式代码查看 + Agent 执行流程 + 文件变更回退
    // （委派给 CodeEditorFacade）
    // ============================================================

    /** 获取文件树。 */
    suspend fun getFileTree(rootPath: String, maxDepth: Int = 10, includeHidden: Boolean = false): BridgeResult<com.apex.lib.workingfiles.FileTreeNode> =
        bridgeRun { codeEditor.getFileTree(rootPath, maxDepth, includeHidden) }

    /** 加载代码文件（含语法 token 化）。 */
    suspend fun loadCodeFileWithTokens(filePath: String): BridgeResult<com.apex.lib.workingfiles.CodeFileContent?> = bridgeRun {
        codeEditor.loadCodeFile(filePath)
    }

    /** 创建文件快照。 */
    suspend fun takeSnapshot(
        filePath: String,
        rootPath: String,
        source: String = "MANUAL",
        agentId: String? = null,
        sessionId: String? = null,
        stepId: String? = null,
        description: String = ""
    ): BridgeResult<FileSnapshot?> = bridgeRun {
        val src = runCatching { ChangeSource.valueOf(source) }.getOrDefault(ChangeSource.MANUAL)
        codeEditor.takeSnapshot(filePath, rootPath, src, agentId, sessionId, stepId, description)
    }

    /** 写入文件 + 自动快照（Agent 写入时的标准入口）。 */
    suspend fun writeWithSnapshot(
        filePath: String,
        rootPath: String,
        content: String,
        agentId: String,
        agentName: String,
        sessionId: String,
        description: String,
        stepTitle: String = description
    ): BridgeResult<com.apex.lib.workingfiles.WriteResult> = bridgeRun {
        codeEditor.writeWithSnapshot(filePath, rootPath, content, agentId, agentName, sessionId, description, stepTitle)
    }

    /** 列出文件的所有快照（摘要）。 */
    suspend fun listSnapshots(filePath: String): BridgeResult<List<SnapshotSummary>> = bridgeRun {
        codeEditor.listSnapshots(filePath)
    }

    /** 加载完整快照。 */
    suspend fun getSnapshot(snapshotId: String): BridgeResult<FileSnapshot?> = bridgeRun {
        codeEditor.getSnapshot(snapshotId)
    }

    /** 获取文件最新快照。 */
    suspend fun getLatestSnapshot(filePath: String): BridgeResult<FileSnapshot?> = bridgeRun {
        codeEditor.getLatestSnapshot(filePath)
    }

    /** 回退文件到指定快照。 */
    suspend fun restoreSnapshot(snapshotId: String, operator: String = "user"): BridgeResult<Boolean> = bridgeRun {
        codeEditor.restoreSnapshot(snapshotId, operator)
    }

    /** 删除某文件的所有快照。 */
    suspend fun deleteAllSnapshots(filePath: String): BridgeResult<Int> = bridgeRun {
        codeEditor.deleteAllSnapshots(filePath)
    }

    /** 计算两段文本的 diff。 */
    suspend fun computeDiff(oldContent: String, newContent: String): BridgeResult<FileDiff> = bridgeRun {
        codeEditor.computeDiff(oldContent, newContent)
    }

    /** 计算两个快照的 diff。 */
    suspend fun diffSnapshots(beforeId: String, afterId: String): BridgeResult<FileDiff?> = bridgeRun {
        codeEditor.diffSnapshots(beforeId, afterId)
    }

    /** 计算快照与当前文件内容的 diff。 */
    suspend fun diffWithCurrent(snapshotId: String): BridgeResult<FileDiff?> = bridgeRun {
        codeEditor.diffWithCurrent(snapshotId)
    }

    /** 计算 Agent 步骤的 diff。 */
    suspend fun diffForStep(stepId: String): BridgeResult<FileDiff?> = bridgeRun {
        codeEditor.diffForStep(stepId)
    }

    // ============================================================
    // Agent 执行流程
    // ============================================================

    /** 启动 Agent 会话。 */
    suspend fun startAgentSession(
        agentId: String,
        agentName: String,
        taskDescription: String,
        mode: String = "NORMAL"
    ): BridgeResult<AgentSession> = bridgeRun {
        val m = runCatching { FlowAgentMode.valueOf(mode) }.getOrDefault(FlowAgentMode.NORMAL)
        codeEditor.startAgentSession(agentId, agentName, taskDescription, m)
    }

    /** 记录 Agent 步骤。 */
    suspend fun recordAgentStep(
        sessionId: String,
        agentId: String,
        agentName: String,
        type: String,
        title: String,
        description: String = "",
        thought: String? = null,
        action: String? = null,
        result: String? = null,
        isSuccess: Boolean = true,
        errorMessage: String? = null,
        affectedFiles: List<String> = emptyList(),
        snapshotIds: List<String> = emptyList(),
        durationMs: Long = 0,
        metadata: Map<String, String> = emptyMap()
    ): BridgeResult<AgentStep?> = bridgeRun {
        val t = runCatching { AgentStepType.valueOf(type) }.getOrDefault(AgentStepType.CUSTOM)
        codeEditor.recordAgentStep(
            sessionId, agentId, agentName, t, title, description, thought, action, result,
            isSuccess, errorMessage, affectedFiles, snapshotIds, durationMs, metadata
        )
    }

    /** 结束 Agent 会话。 */
    suspend fun finishAgentSession(
        sessionId: String,
        finalResult: String? = null,
        status: String = "COMPLETED"
    ): BridgeResult<Boolean> = bridgeRun {
        val s = runCatching { AgentSessionStatus.valueOf(status) }.getOrDefault(AgentSessionStatus.COMPLETED)
        codeEditor.finishAgentSession(sessionId, finalResult, s)
    }

    /** 获取 Agent 执行流程。 */
    suspend fun getAgentFlow(sessionId: String): BridgeResult<com.apex.lib.workingfiles.agent.AgentFlow?> = bridgeRun {
        codeEditor.getAgentFlow(sessionId)
    }

    /** 列出所有 Agent 会话。 */
    suspend fun listAgentSessions(): BridgeResult<List<AgentSession>> = bridgeRun {
        codeEditor.listAgentSessions()
    }

    /** 列出活跃会话。 */
    suspend fun listActiveAgentSessions(): BridgeResult<List<AgentSession>> = bridgeRun {
        codeEditor.listActiveAgentSessions()
    }

    /** 获取会话所有步骤。 */
    suspend fun listAgentSteps(sessionId: String): BridgeResult<List<AgentStep>> = bridgeRun {
        codeEditor.listAgentSteps(sessionId)
    }

    /** 获取会话中影响指定文件的步骤。 */
    suspend fun listAgentStepsForFile(sessionId: String, filePath: String): BridgeResult<List<AgentStep>> = bridgeRun {
        codeEditor.listAgentStepsForFile(sessionId, filePath)
    }

    /** 删除会话。 */
    suspend fun deleteAgentSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        codeEditor.deleteAgentSession(sessionId)
    }

    /** 获取快照存储统计。 */
    fun getSnapshotStats() = codeEditor.getSnapshotStats()

    // ============================================================
    // Apex 独有增强（委派给 CodeEditorFacade）
    // ============================================================

    // ===== 虚拟分支 =====
    suspend fun createBranch(name: String, filePath: String, baseSnapshotId: String? = null, description: String = "", agentId: String? = null): BridgeResult<com.apex.lib.workingfiles.branch.VirtualBranch> = bridgeRun {
        codeEditor.createBranch(name, filePath, baseSnapshotId, description, agentId)
    }
    suspend fun switchToBranch(filePath: String, branchId: String): BridgeResult<Boolean> = bridgeRun { codeEditor.switchToBranch(filePath, branchId) }
    suspend fun switchToMain(filePath: String): BridgeResult<Boolean> = bridgeRun { codeEditor.switchToMain(filePath) }
    suspend fun mergeBranch(branchId: String, strategy: String = "MERGE_MANUAL"): BridgeResult<com.apex.lib.workingfiles.branch.BranchMergeResult> = bridgeRun {
        val s = runCatching { com.apex.lib.workingfiles.branch.MergeStrategy.valueOf(strategy) }.getOrDefault(com.apex.lib.workingfiles.branch.MergeStrategy.MERGE_MANUAL)
        codeEditor.mergeBranch(branchId, s)
    }
    suspend fun discardBranch(branchId: String): BridgeResult<Boolean> = bridgeRun { codeEditor.discardBranch(branchId) }
    suspend fun listBranches(filePath: String): BridgeResult<List<com.apex.lib.workingfiles.branch.VirtualBranch>> = bridgeRun { codeEditor.listBranches(filePath) }
    suspend fun listActiveBranches(filePath: String): BridgeResult<List<com.apex.lib.workingfiles.branch.VirtualBranch>> = bridgeRun { codeEditor.listActiveBranches(filePath) }
    suspend fun getActiveBranch(filePath: String): BridgeResult<com.apex.lib.workingfiles.branch.VirtualBranch?> = bridgeRun { codeEditor.getActiveBranch(filePath) }
    suspend fun getBranchDiff(branchId: String): BridgeResult<com.apex.lib.workingfiles.diff.FileDiff?> = bridgeRun { codeEditor.getBranchDiff(branchId) }
    suspend fun deleteBranch(branchId: String): BridgeResult<Boolean> = bridgeRun { codeEditor.deleteBranch(branchId) }

    // ===== 智能回退 =====
    suspend fun analyzeRevert(sessionId: String, stepId: String): BridgeResult<com.apex.lib.workingfiles.agent.RevertAnalysis?> = bridgeRun {
        codeEditor.analyzeRevert(sessionId, stepId)
    }
    suspend fun executeSmartRevert(sessionId: String, stepId: String, operator: String = "user"): BridgeResult<com.apex.lib.workingfiles.agent.RevertResult> = bridgeRun {
        val analysis = codeEditor.analyzeRevert(sessionId, stepId) ?: throw IllegalStateException("step not found")
        codeEditor.executeSmartRevert(analysis, operator)
    }

    // ===== 语义 Diff =====
    suspend fun analyzeSemanticDiff(oldContent: String, newContent: String): BridgeResult<com.apex.lib.workingfiles.semantic.SemanticDiff> = bridgeRun {
        val diff = codeEditor.computeDiff(oldContent, newContent)
        codeEditor.analyzeSemanticDiff(diff)
    }
    suspend fun semanticDiffSnapshots(beforeId: String, afterId: String): BridgeResult<com.apex.lib.workingfiles.semantic.SemanticDiff?> = bridgeRun {
        codeEditor.semanticDiffSnapshots(beforeId, afterId)
    }

    // ===== 时间机器 =====
    suspend fun loadTimeMachine(filePath: String): BridgeResult<Boolean> = bridgeRun { codeEditor.loadTimeMachine(filePath) }
    suspend fun timeMachineJumpTo(index: Int): BridgeResult<com.apex.lib.workingfiles.snapshot.SnapshotSummary?> = bridgeRun { codeEditor.timeMachineJumpTo(index) }
    suspend fun timeMachineJumpToTimestamp(timestamp: Long): BridgeResult<com.apex.lib.workingfiles.snapshot.SnapshotSummary?> = bridgeRun { codeEditor.timeMachineJumpToTimestamp(timestamp) }
    suspend fun timeMachineNext(): BridgeResult<com.apex.lib.workingfiles.snapshot.SnapshotSummary?> = bridgeRun { codeEditor.timeMachineNext() }
    suspend fun timeMachinePrevious(): BridgeResult<com.apex.lib.workingfiles.snapshot.SnapshotSummary?> = bridgeRun { codeEditor.timeMachinePrevious() }
    suspend fun timeMachineTimeline(filePath: String): BridgeResult<List<com.apex.lib.workingfiles.snapshot.SnapshotSummary>> = bridgeRun {
        codeEditor.loadTimeMachine(filePath)
        codeEditor.timeMachine.getTimeline()
    }

    // ===== 冲突检测 =====
    suspend fun acquireFileLock(filePath: String, agentId: String, type: String = "WRITE_LOCK", ttlMs: Long = 30_000L): BridgeResult<String?> = bridgeRun {
        val t = runCatching { com.apex.lib.workingfiles.conflict.LockType.valueOf(type) }.getOrDefault(com.apex.lib.workingfiles.conflict.LockType.WRITE_LOCK)
        codeEditor.acquireFileLock(filePath, agentId, t, ttlMs)
    }
    suspend fun releaseFileLock(token: String): BridgeResult<Boolean> = bridgeRun { codeEditor.releaseFileLock(token) }
    suspend fun releaseAllLocksForAgent(agentId: String): BridgeResult<Int> = bridgeRun { codeEditor.releaseAllLocksForAgent(agentId) }
    suspend fun isFileLocked(filePath: String): BridgeResult<Boolean> = bridgeRun { codeEditor.isFileLocked(filePath) }
    suspend fun getFileLockStatus(filePath: String): BridgeResult<com.apex.lib.workingfiles.conflict.LockStatus> = bridgeRun { codeEditor.getFileLockStatus(filePath) }
    suspend fun detectConflict(filePath: String, agentId: String): BridgeResult<com.apex.lib.workingfiles.conflict.ConflictWarning?> = bridgeRun { codeEditor.detectConflict(filePath, agentId) }
    suspend fun listLockedFiles(): BridgeResult<List<String>> = bridgeRun { codeEditor.listLockedFiles() }

    // ===== 变更回放 =====
    suspend fun loadReplayer(sessionId: String): BridgeResult<Boolean> = bridgeRun { codeEditor.loadReplayer(sessionId) }
    suspend fun playReplay(speed: Float = 1.0f): BridgeResult<Unit> = bridgeRun { codeEditor.playReplay(speed) }
    suspend fun pauseReplay(): BridgeResult<Unit> = bridgeRun { codeEditor.pauseReplay() }
    suspend fun resetReplay(): BridgeResult<Unit> = bridgeRun { codeEditor.resetReplay() }
    suspend fun jumpReplayTo(stepIndex: Int): BridgeResult<Unit> = bridgeRun { codeEditor.jumpReplayTo(stepIndex) }
    suspend fun replayNextStep(): BridgeResult<Unit> = bridgeRun { codeEditor.replayNextStep() }
    suspend fun replayPreviousStep(): BridgeResult<Unit> = bridgeRun { codeEditor.replayPreviousStep() }
    suspend fun setReplaySpeed(speed: Float): BridgeResult<Unit> = bridgeRun { codeEditor.setReplaySpeed(speed) }
    suspend fun replayProgress(): BridgeResult<Float> = bridgeRun { codeEditor.replayProgress() }
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
