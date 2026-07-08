package com.apex.lib.workingfiles

import com.apex.lib.workingfiles.agent.AgentFlow
import com.apex.lib.workingfiles.agent.AgentFlowStorage
import com.apex.lib.workingfiles.agent.AgentMode
import com.apex.lib.workingfiles.agent.AgentSession
import com.apex.lib.workingfiles.agent.AgentSessionStatus
import com.apex.lib.workingfiles.agent.AgentStep
import com.apex.lib.workingfiles.agent.AgentStepType
import com.apex.lib.workingfiles.agent.RevertAnalysis
import com.apex.lib.workingfiles.agent.RevertResult
import com.apex.lib.workingfiles.agent.SmartReverter
import com.apex.lib.workingfiles.branch.BranchManager
import com.apex.lib.workingfiles.branch.BranchMergeResult
import com.apex.lib.workingfiles.branch.BranchStatus
import com.apex.lib.workingfiles.branch.MergeStrategy
import com.apex.lib.workingfiles.branch.VirtualBranch
import com.apex.lib.workingfiles.conflict.ConflictDetector
import com.apex.lib.workingfiles.conflict.ConflictWarning
import com.apex.lib.workingfiles.conflict.LockStatus
import com.apex.lib.workingfiles.conflict.LockType
import com.apex.lib.workingfiles.diff.DiffComputer
import com.apex.lib.workingfiles.diff.FileDiff
import com.apex.lib.workingfiles.replay.ChangeReplayer
import com.apex.lib.workingfiles.replay.ReplayEvent
import com.apex.lib.workingfiles.semantic.SemanticDiff
import com.apex.lib.workingfiles.semantic.SemanticDiffAnalyzer
import com.apex.lib.workingfiles.snapshot.ChangeSource
import com.apex.lib.workingfiles.snapshot.ChangeType
import com.apex.lib.workingfiles.snapshot.FileSnapshot
import com.apex.lib.workingfiles.snapshot.SnapshotStorage
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
import com.apex.lib.workingfiles.timemachine.TimeMachine
import com.apex.lib.workingfiles.timemachine.TimeMachinePlayer
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import java.io.File

/**
 * 代码编辑器门面 — VSCode 式代码查看 + Agent 执行流程 + 文件变更回退。
 *
 * **核心能力**：
 *   1. **文件浏览**：FileTree + 文件内容查看（语法高亮）
 *   2. **快照系统**：每次 agent 写入前自动快照，文件可回退到任意版本
 *   3. **Diff 视图**：两个快照之间的行级 diff（Myers 算法）
 *   4. **Agent 流程**：可视化 Agent 在一次会话中执行的每一步 + 影响的文件
 *   5. **回退**：恢复文件到任意历史快照（生成新快照记录此次回退）
 *
 * **设计参考**：
 *   - VSCode Timeline：自动 + 手动快照，时间线展示
 *   - Cline (VSCode AI)：每次工具调用前快照，checkpoint 回退
 *   - Aider：git commit 跟踪 AI 编辑，/undo 回退
 *   - JetBrains Local History：差异存储，可对比任意两版本
 *
 * **使用方式**：
 *   ```kotlin
 *   val editor = CodeEditorFacade(context)
 *
 *   // 启动 Agent 会话
 *   val session = editor.startAgentSession("agent-1", "CodeBot", "修复 bug", AgentMode.NORMAL)
 *
 *   // Agent 写入文件前自动快照
 *   editor.writeWithSnapshot(
 *       sessionId = session.id,
 *       agentId = "agent-1",
 *       filePath = "/sdcard/proj/Main.kt",
 *       content = "fun main() {...}",
 *       description = "修复 NPE"
 *   )
 *
 *   // 查看文件历史
 *   val history = editor.listSnapshots("/sdcard/proj/Main.kt")
 *
 *   // 比较两个快照
 *   val diff = editor.diffSnapshots(history[0].id, history[1].id)
 *
 *   // 回退到旧版本
 *   editor.restoreSnapshot(history[0].id)
 *
 *   // 查看 Agent 执行流程
 *   val flow = editor.getAgentFlow(session.id)
 *   ```
 */
class CodeEditorFacade(
    private val context: android.content.Context
) {
    private val TAG_SUB = "CodeEditor"

    private val baseDir = File(context.filesDir, "apex-code-editor").apply { mkdirs() }
    private val snapshotStorage = SnapshotStorage(File(baseDir, "snapshots"))
    private val flowStorage = AgentFlowStorage(File(baseDir, "flows"))

    // ===== Apex 独有增强模块 =====
    /** 虚拟分支管理器 */
    val branchManager: BranchManager = BranchManager(File(baseDir, "branches"), snapshotStorage)
    /** 智能回退器（按 Agent 步骤回退） */
    val smartReverter: SmartReverter = SmartReverter(snapshotStorage, flowStorage)
    /** 时间机器（连续滑动预览） */
    val timeMachine: TimeMachine = TimeMachine(snapshotStorage)
    /** 多 Agent 冲突检测器 */
    val conflictDetector: ConflictDetector = ConflictDetector()
    /** 变更回放器（按速度回放 Agent 变更） */
    val changeReplayer: ChangeReplayer = ChangeReplayer(snapshotStorage, flowStorage)

    // ============================================================
    // 文件浏览
    // ============================================================

    /**
     * 获取文件树。
     * @param rootPath 根目录
     * @param maxDepth 最大深度（默认 10）
     * @param includeHidden 是否包含隐藏文件
     */
    fun getFileTree(
        rootPath: String,
        maxDepth: Int = 10,
        includeHidden: Boolean = false
    ): FileTreeNode {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            return FileTreeNode(name = root.name, path = root.absolutePath, isDirectory = false, children = emptyList())
        }
        return buildFileTree(root, root.absolutePath, 0, maxDepth, includeHidden)
    }

    private fun buildFileTree(
        file: File,
        rootPath: String,
        depth: Int,
        maxDepth: Int,
        includeHidden: Boolean
    ): FileTreeNode {
        val relativePath = file.absolutePath.removePrefix(rootPath).removePrefix("/")
        if (file.isFile) {
            return FileTreeNode(
                name = file.name,
                path = file.absolutePath,
                relativePath = relativePath,
                isDirectory = false,
                size = file.length(),
                lastModified = file.lastModified(),
                language = CodePreview.detectLanguage(file.name),
                children = emptyList()
            )
        }
        if (depth >= maxDepth) {
            return FileTreeNode(
                name = file.name,
                path = file.absolutePath,
                relativePath = relativePath,
                isDirectory = true,
                size = 0,
                lastModified = file.lastModified(),
                language = "",
                children = emptyList()
            )
        }
        val children = file.listFiles()
            ?.filter { includeHidden || !it.name.startsWith(".") }
            ?.filter { includeHidden || !it.name.startsWith("_") || it.name.length > 1 }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { buildFileTree(it, rootPath, depth + 1, maxDepth, includeHidden) }
            ?: emptyList()
        return FileTreeNode(
            name = file.name,
            path = file.absolutePath,
            relativePath = relativePath,
            isDirectory = true,
            size = 0,
            lastModified = file.lastModified(),
            language = "",
            children = children
        )
    }

    /**
     * 读取文件内容。
     */
    fun readFile(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        return try { file.readText() } catch (t: Throwable) {
            ApexLog.w("working-files", "[$TAG_SUB] readFile failed: ${t.message}")
            null
        }
    }

    /**
     * 读取文件 + 语法 token 化（用于 UI 渲染）。
     */
    fun loadCodeFile(filePath: String): CodeFileContent? {
        val file = File(filePath)
        val code = CodePreview.load(file) ?: return null
        val tokens = CodePreview.tokenize(code.content, code.language).map { t ->
            CodeToken(text = t.text, type = t.type.name)
        }
        return CodeFileContent(
            path = code.path,
            language = code.language,
            lineCount = code.lineCount,
            totalChars = code.totalChars,
            content = code.content,
            tokens = tokens
        )
    }

    // ============================================================
    // 快照系统
    // ============================================================

    /**
     * 创建文件快照。
     *
     * 业务侧通常不直接调用本方法，而是用 [writeWithSnapshot]（写入同时快照）。
     * 本方法用于手动建立基线快照。
     */
    fun takeSnapshot(
        filePath: String,
        rootPath: String,
        source: ChangeSource = ChangeSource.MANUAL,
        agentId: String? = null,
        sessionId: String? = null,
        stepId: String? = null,
        description: String = ""
    ): FileSnapshot? {
        val file = File(filePath)
        val content = if (file.exists() && file.isFile) {
            try { file.readText() } catch (_: Throwable) { return null }
        } else ""
        val changeType = when {
            !file.exists() -> ChangeType.DELETE
            snapshotStorage.getLatestSnapshot(filePath) == null -> ChangeType.CREATE
            else -> ChangeType.MODIFY
        }
        val snapshot = FileSnapshot.create(
            filePath = filePath,
            relativePath = filePath.removePrefix(rootPath).removePrefix("/"),
            content = content,
            changeType = changeType,
            source = source,
            agentId = agentId,
            sessionId = sessionId,
            stepId = stepId,
            description = description
        )
        val saved = snapshotStorage.save(snapshot)
        return if (saved) snapshot else null
    }

    /**
     * **写入文件 + 自动快照**（Agent 写入时的标准入口）。
     *
     * 流程：
     *   1. 读取旧内容（如果文件存在），建立"变更前"快照
     *   2. 写入新内容
     *   3. 建立"变更后"快照
     *   4. 记录 Agent 步骤
     *
     * @return 包含 before/after 两个快照的 [WriteResult]
     */
    fun writeWithSnapshot(
        filePath: String,
        rootPath: String,
        content: String,
        agentId: String,
        agentName: String,
        sessionId: String,
        description: String,
        stepTitle: String = description
    ): WriteResult {
        val file = File(filePath)
        val fileExistedBefore = file.exists()
        val oldContent = if (fileExistedBefore) {
            try { file.readText() } catch (_: Throwable) { null }
        } else null

        // 写入新内容
        file.parentFile?.mkdirs()
        file.writeText(content)

        // 建立"变更后"快照
        val afterSnapshot = FileSnapshot.create(
            filePath = filePath,
            relativePath = filePath.removePrefix(rootPath).removePrefix("/"),
            content = content,
            changeType = if (fileExistedBefore) ChangeType.MODIFY else ChangeType.CREATE,
            source = ChangeSource.AGENT,
            agentId = agentId,
            sessionId = sessionId,
            description = description
        )
        snapshotStorage.save(afterSnapshot)

        // 记录 Agent 步骤
        val stepType = if (fileExistedBefore) AgentStepType.FILE_EDIT else AgentStepType.FILE_WRITE
        val step = flowStorage.addStep(
            sessionId = sessionId,
            agentId = agentId,
            agentName = agentName,
            type = stepType,
            title = stepTitle,
            description = description,
            action = "write $filePath",
            result = "wrote ${content.length} chars",
            affectedFiles = listOf(filePath),
            snapshotIds = listOf(afterSnapshot.id)
        )

        ApexLog.i("working-files", "[$TAG_SUB] write+snapshot: $filePath, +${content.length} chars, session=$sessionId")
        return WriteResult(
            beforeSnapshot = oldContent?.let {
                // 也保存 before 快照（用于回退）
                val beforeSnapshot = FileSnapshot.create(
                    filePath = filePath,
                    relativePath = filePath.removePrefix(rootPath).removePrefix("/"),
                    content = it,
                    changeType = ChangeType.MODIFY,
                    source = ChangeSource.AGENT,
                    agentId = agentId,
                    sessionId = sessionId,
                    description = "$description (变更前)"
                )
                snapshotStorage.save(beforeSnapshot)
                beforeSnapshot
            },
            afterSnapshot = afterSnapshot,
            step = step
        )
    }

    /**
     * 列出某文件的所有快照（摘要）。
     */
    fun listSnapshots(filePath: String): List<SnapshotSummary> {
        return snapshotStorage.listSnapshotSummaries(filePath)
    }

    /**
     * 加载完整快照（含全文）。
     */
    fun getSnapshot(snapshotId: String): FileSnapshot? {
        return snapshotStorage.load(snapshotId)
    }

    /**
     * 获取某文件的最新快照。
     */
    fun getLatestSnapshot(filePath: String): FileSnapshot? {
        return snapshotStorage.getLatestSnapshot(filePath)
    }

    /**
     * **回退文件到指定快照**。
     *
     * 实现：
     *   1. 加载目标快照内容
     *   2. 写回文件
     *   3. 建立新快照记录此次回退（标记 source=USER，描述 "回退到 snapshot-xxx"）
     *   4. 记录 Agent 步骤（type=ROLLBACK）
     *
     * @param snapshotId 要回退到的目标快照 ID
     * @param operator 操作者（用户 ID 或 agent ID）
     * @return 是否成功
     */
    fun restoreSnapshot(snapshotId: String, operator: String = "user"): Boolean {
        val snapshot = snapshotStorage.load(snapshotId) ?: return false
        val file = File(snapshot.filePath)
        file.parentFile?.mkdirs()

        // 写回旧内容
        if (snapshot.changeType == ChangeType.DELETE) {
            file.delete()
        } else {
            file.writeText(snapshot.content)
        }

        // 建立回退快照（记录此次操作）
        val rollbackSnapshot = FileSnapshot.create(
            filePath = snapshot.filePath,
            relativePath = snapshot.relativePath,
            content = snapshot.content,
            changeType = ChangeType.MODIFY,
            source = ChangeSource.USER,
            description = "回退到 ${snapshot.id}（${snapshot.description.ifEmpty { "无描述" }}）"
        )
        snapshotStorage.save(rollbackSnapshot)

        // 记录到关联的 Agent 会话（如果有）
        snapshot.sessionId?.let { sid ->
            flowStorage.addStep(
                sessionId = sid,
                agentId = operator,
                agentName = "用户",
                type = AgentStepType.ROLLBACK,
                title = "回退 ${snapshot.relativePath}",
                description = "回退到快照 ${snapshot.id}",
                action = "restore $snapshotId",
                affectedFiles = listOf(snapshot.filePath),
                snapshotIds = listOf(rollbackSnapshot.id)
            )
        }

        ApexLog.i("working-files", "[$TAG_SUB] restoreSnapshot: ${snapshot.filePath} → ${snapshot.id}")
        return true
    }

    /**
     * 删除某文件的所有快照。
     */
    fun deleteAllSnapshots(filePath: String): Int {
        return snapshotStorage.deleteAllForFile(filePath)
    }

    // ============================================================
    // Diff 计算
    // ============================================================

    /**
     * 计算两个文本之间的 diff。
     */
    fun computeDiff(oldContent: String, newContent: String): FileDiff {
        return DiffComputer.compute(oldContent, newContent)
    }

    /**
     * 计算两个快照之间的 diff。
     */
    fun diffSnapshots(beforeSnapshotId: String, afterSnapshotId: String): FileDiff? {
        val before = snapshotStorage.load(beforeSnapshotId) ?: return null
        val after = snapshotStorage.load(afterSnapshotId) ?: return null
        val diff = DiffComputer.compute(before.content, after.content)
        return diff.copy(
            oldFilePath = before.relativePath,
            newFilePath = after.relativePath
        )
    }

    /**
     * 计算文件当前内容与某快照之间的 diff。
     */
    fun diffWithCurrent(snapshotId: String): FileDiff? {
        val snapshot = snapshotStorage.load(snapshotId) ?: return null
        val file = File(snapshot.filePath)
        if (!file.exists()) return null
        val current = try { file.readText() } catch (_: Throwable) { return null }
        val diff = DiffComputer.compute(snapshot.content, current)
        return diff.copy(
            oldFilePath = "${snapshot.relativePath} (快照 ${snapshot.id.take(12)})",
            newFilePath = "${snapshot.relativePath} (当前)"
        )
    }

    /**
     * 计算 Agent 步骤产生的 diff（基于步骤的快照）。
     */
    fun diffForStep(stepId: String): FileDiff? {
        // 查找所有会话中的此步骤
        for (session in flowStorage.listSessions()) {
            val flow = flowStorage.getFlow(session.id) ?: continue
            val step = flow.steps.firstOrNull { it.id == stepId } ?: continue
            if (step.snapshotIds.isEmpty()) return null
            // 取第一个快照作为参照
            val snapshot = snapshotStorage.load(step.snapshotIds.first()) ?: return null
            // 取上一个快照作为对比基线
            val allSnapshots = snapshotStorage.listSnapshots(snapshot.filePath)
            val currentIdx = allSnapshots.indexOfFirst { it.id == snapshot.id }
            if (currentIdx <= 0) return null
            val prevSnapshot = allSnapshots[currentIdx - 1]
            return diffSnapshots(prevSnapshot.id, snapshot.id)
        }
        return null
    }

    // ============================================================
    // Agent 执行流程
    // ============================================================

    /**
     * 启动 Agent 会话。
     */
    fun startAgentSession(
        agentId: String,
        agentName: String,
        taskDescription: String,
        mode: AgentMode = AgentMode.NORMAL
    ): AgentSession {
        val session = flowStorage.createSession(agentId, agentName, taskDescription, mode)
        flowStorage.addStep(
            sessionId = session.id,
            agentId = agentId,
            agentName = agentName,
            type = AgentStepType.THOUGHT,
            title = "会话开始",
            description = taskDescription
        )
        ApexLog.i("working-files", "[$TAG_SUB] agent session started: ${session.id} (agent=$agentId, mode=$mode)")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.AGENT_SESSION_STARTED,
            mapOf("sessionId" to session.id, "agentId" to agentId, "mode" to mode.name),
            ApexSuite.ApkId.WORKING_FILES
        )
        return session
    }

    /**
     * 记录一个 Agent 步骤（不涉及文件写入）。
     */
    fun recordAgentStep(
        sessionId: String,
        agentId: String,
        agentName: String,
        type: AgentStepType,
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
    ): AgentStep? {
        return flowStorage.addStep(
            sessionId = sessionId,
            agentId = agentId,
            agentName = agentName,
            type = type,
            title = title,
            description = description,
            thought = thought,
            action = action,
            result = result,
            isSuccess = isSuccess,
            errorMessage = errorMessage,
            affectedFiles = affectedFiles,
            snapshotIds = snapshotIds,
            durationMs = durationMs,
            metadata = metadata
        )
    }

    /**
     * 结束 Agent 会话。
     */
    fun finishAgentSession(
        sessionId: String,
        finalResult: String? = null,
        status: AgentSessionStatus = AgentSessionStatus.COMPLETED
    ): Boolean {
        val ok = flowStorage.finishSession(sessionId, finalResult, status)
        if (ok) {
            com.apex.sdk.bridge.SuiteEventBus.publish(
                com.apex.sdk.bridge.SuiteEventTypes.AGENT_SESSION_ENDED,
                mapOf("sessionId" to sessionId, "status" to status.name),
                ApexSuite.ApkId.WORKING_FILES
            )
        }
        return ok
    }

    /**
     * 获取 Agent 执行流程。
     */
    fun getAgentFlow(sessionId: String): AgentFlow? {
        return flowStorage.getFlow(sessionId)
    }

    /**
     * 列出所有 Agent 会话。
     */
    fun listAgentSessions(): List<AgentSession> {
        return flowStorage.listSessions()
    }

    /**
     * 列出活跃会话。
     */
    fun listActiveAgentSessions(): List<AgentSession> {
        return flowStorage.listActiveSessions()
    }

    /**
     * 获取某会话的所有步骤。
     */
    fun listAgentSteps(sessionId: String): List<AgentStep> {
        return flowStorage.listSteps(sessionId)
    }

    /**
     * 获取某会话中影响指定文件的步骤。
     */
    fun listAgentStepsForFile(sessionId: String, filePath: String): List<AgentStep> {
        return flowStorage.listStepsForFile(sessionId, filePath)
    }

    /**
     * 删除会话。
     */
    fun deleteAgentSession(sessionId: String): Boolean {
        return flowStorage.deleteSession(sessionId)
    }

    // ============================================================
    // 统计
    // ============================================================

    fun getSnapshotStats() = snapshotStorage.getStats()

    // ============================================================
    // 增强 1：虚拟分支系统（Apex 独有 — 移动端友好的"假设性"分支）
    // ============================================================

    /** 创建虚拟分支。 */
    fun createBranch(
        name: String, filePath: String, baseSnapshotId: String? = null,
        description: String = "", agentId: String? = null
    ): VirtualBranch = branchManager.createBranch(name, filePath, baseSnapshotId, description, agentId)

    /** 切换到分支。 */
    fun switchToBranch(filePath: String, branchId: String): Boolean = branchManager.switchToBranch(filePath, branchId)

    /** 切换回 main。 */
    fun switchToMain(filePath: String): Boolean = branchManager.switchToMain(filePath)

    /** 合并分支。 */
    fun mergeBranch(branchId: String, strategy: MergeStrategy = MergeStrategy.MERGE_MANUAL): BranchMergeResult =
        branchManager.mergeBranch(branchId, strategy)

    /** 丢弃分支。 */
    fun discardBranch(branchId: String): Boolean = branchManager.discardBranch(branchId)

    /** 列出文件的所有分支。 */
    fun listBranches(filePath: String): List<VirtualBranch> = branchManager.listBranches(filePath)

    /** 列出活跃分支。 */
    fun listActiveBranches(filePath: String): List<VirtualBranch> = branchManager.listActiveBranches(filePath)

    /** 获取当前活跃分支。 */
    fun getActiveBranch(filePath: String): VirtualBranch? = branchManager.getActiveBranch(filePath)

    /** 获取分支 diff。 */
    fun getBranchDiff(branchId: String): FileDiff? = branchManager.getBranchDiff(branchId)

    /** 锁定/解锁分支。 */
    fun lockBranch(branchId: String) = branchManager.lockBranch(branchId)
    fun unlockBranch(branchId: String) = branchManager.unlockBranch(branchId)

    /** 删除分支。 */
    fun deleteBranch(branchId: String): Boolean = branchManager.deleteBranch(branchId)

    // ============================================================
    // 增强 2：智能回退（Apex 独有 — 按 Agent 步骤回退，保留无关成果）
    // ============================================================

    /** 分析回退某步骤的影响范围。 */
    fun analyzeRevert(sessionId: String, stepId: String): RevertAnalysis? =
        smartReverter.analyzeRevert(sessionId, stepId)

    /** 执行智能回退。 */
    fun executeSmartRevert(analysis: RevertAnalysis, operator: String = "user"): RevertResult =
        smartReverter.executeRevert(analysis, operator)

    // ============================================================
    // 增强 3：语义 Diff（Apex 独有 — AI 增强差异分析）
    // ============================================================

    /** 分析 diff 的语义（变更类型/风险/影响符号/破坏性变更）。 */
    fun analyzeSemanticDiff(diff: FileDiff): SemanticDiff = SemanticDiffAnalyzer.analyze(diff)

    /** 直接分析两个快照的语义 diff。 */
    fun semanticDiffSnapshots(beforeId: String, afterId: String): SemanticDiff? {
        val diff = diffSnapshots(beforeId, afterId) ?: return null
        return SemanticDiffAnalyzer.analyze(diff)
    }

    // ============================================================
    // 增强 4：时间机器（Apex 独有 — 连续滑动预览）
    // ============================================================

    /** 加载文件到时间机器。 */
    fun loadTimeMachine(filePath: String): Boolean = timeMachine.load(filePath)

    /** 时间机器跳到指定索引。 */
    fun timeMachineJumpTo(index: Int) = timeMachine.jumpTo(index)

    /** 时间机器跳到指定时间戳。 */
    fun timeMachineJumpToTimestamp(timestamp: Long) = timeMachine.jumpToTimestamp(timestamp)

    /** 时间机器前进/后退。 */
    fun timeMachineNext() = timeMachine.next()
    fun timeMachinePrevious() = timeMachine.previous()
    fun timeMachineJumpToStart() = timeMachine.jumpToStart()
    fun timeMachineJumpToEnd() = timeMachine.jumpToEnd()

    /** 创建时间机器播放器。 */
    fun createTimeMachinePlayer(onUpdate: (SnapshotSummary?) -> Unit): TimeMachinePlayer =
        TimeMachinePlayer(timeMachine, onUpdate)

    // ============================================================
    // 增强 5：多 Agent 冲突检测（Apex 独有）
    // ============================================================

    /** 获取文件锁。 */
    fun acquireFileLock(filePath: String, agentId: String, type: LockType, ttlMs: Long = 30_000L): String? =
        conflictDetector.acquireLock(filePath, agentId, type, ttlMs)

    /** 释放文件锁。 */
    fun releaseFileLock(token: String): Boolean = conflictDetector.releaseLock(token)

    /** 释放某 Agent 的所有锁。 */
    fun releaseAllLocksForAgent(agentId: String): Int = conflictDetector.releaseAllForAgent(agentId)

    /** 文件是否被锁定。 */
    fun isFileLocked(filePath: String): Boolean = conflictDetector.isLocked(filePath)

    /** 获取文件锁状态。 */
    fun getFileLockStatus(filePath: String): LockStatus = conflictDetector.getLockStatus(filePath)

    /** 检测潜在冲突。 */
    fun detectConflict(filePath: String, agentId: String): ConflictWarning? =
        conflictDetector.detectPotentialConflict(filePath, agentId)

    /** 列出所有被锁定的文件。 */
    fun listLockedFiles(): List<String> = conflictDetector.listLockedFiles()

    // ============================================================
    // 增强 6：变更回放（Apex 独有 — 按速度回放 Agent 变更）
    // ============================================================

    /** 加载会话到回放器。 */
    fun loadReplayer(sessionId: String): Boolean = changeReplayer.load(sessionId)

    /** 开始回放。 */
    fun playReplay(speed: Float = 1.0f) = changeReplayer.play(speed)

    /** 暂停回放。 */
    fun pauseReplay() = changeReplayer.pause()

    /** 重置回放。 */
    fun resetReplay() = changeReplayer.reset()

    /** 跳到指定步骤。 */
    fun jumpReplayTo(stepIndex: Int) = changeReplayer.jumpTo(stepIndex)

    /** 下一步/上一步。 */
    fun replayNextStep() = changeReplayer.nextStep()
    fun replayPreviousStep() = changeReplayer.previousStep()

    /** 设置回放速度。 */
    fun setReplaySpeed(speed: Float) = changeReplayer.setSpeed(speed)

    /** 回放进度。 */
    fun replayProgress(): Float = changeReplayer.getProgress()
}

// ============================================================
// DTO 数据类
// ============================================================

/** 文件树节点。 */
data class FileTreeNode(
    val name: String,
    val path: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val language: String = "",
    val children: List<FileTreeNode>
)

/** 代码文件内容（含 token）。 */
data class CodeFileContent(
    val path: String,
    val language: String,
    val lineCount: Int,
    val totalChars: Int,
    val content: String,
    val tokens: List<CodeToken>
)

data class CodeToken(
    val text: String,
    val type: String  // PLAIN / KEYWORD / STRING / COMMENT / NUMBER / OPERATOR / IDENTIFIER
)

/** 写入 + 快照结果。 */
data class WriteResult(
    val beforeSnapshot: FileSnapshot?,
    val afterSnapshot: FileSnapshot,
    val step: AgentStep?
)
