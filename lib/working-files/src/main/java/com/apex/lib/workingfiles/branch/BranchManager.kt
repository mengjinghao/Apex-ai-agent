package com.apex.lib.workingfiles.branch

import com.apex.lib.workingfiles.diff.DiffComputer
import com.apex.lib.workingfiles.snapshot.FileSnapshot
import com.apex.lib.workingfiles.snapshot.SnapshotStorage
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 虚拟分支管理器 — Apex 独有的"假设性"分支系统。
 *
 * **核心思想**：分支只是"快照指针"，不真正复制文件。
 *   - main 分支：当前文件的真实状态
 *   - virtual 分支：基于某个快照的"假设"状态，Agent 在其上修改不会影响 main
 *
 * **存储**：所有分支元数据存 `branches.json`，分支的快照存到 SnapshotStorage。
 *
 * **分支切换**：切换分支时，把对应快照的内容写回文件（同时建立 main 的新快照）。
 * 切换回 main 时同理。这保证了 main 的修改不会丢失。
 */
class BranchManager(
    private val storageDir: File,
    private val snapshotStorage: SnapshotStorage
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val branchesFile = File(storageDir, "branches.json").apply { storageDir.mkdirs() }
    private val branches = ConcurrentHashMap<String, VirtualBranch>()
    /** 文件 → 当前活跃分支 ID（null 表示在 main） */
    private val activeBranch = ConcurrentHashMap<String, String?>()

    init { loadBranches() }

    /**
     * 创建虚拟分支。
     *
     * @param name 分支名
     * @param filePath 文件路径
     * @param baseSnapshotId 基线快照（不传则用文件当前最新快照）
     * @param description 描述
     * @param agentId 创建分支的 Agent
     * @return 新分支
     */
    fun createBranch(
        name: String,
        filePath: String,
        baseSnapshotId: String? = null,
        description: String = "",
        agentId: String? = null,
        color: String = pickColor(name)
    ): VirtualBranch {
        val baseId = baseSnapshotId ?: snapshotStorage.getLatestSnapshot(filePath)?.id
            ?: throw IllegalStateException("file has no snapshot, take one first")

        val branch = VirtualBranch(
            id = generateId(),
            name = name,
            filePath = filePath,
            baseSnapshotId = baseId,
            headSnapshotId = baseId,  // 初始 HEAD = base
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            description = description,
            agentId = agentId,
            color = color
        )
        branches[branch.id] = branch
        persist()
        ApexLog.i("working-files", "[Branch] created: ${branch.id} ($name) for $filePath")
        return branch
    }

    /**
     * 切换到指定分支（文件内容变为分支 HEAD）。
     *
     * 实现：
     *   1. 把当前 main 状态保存为新快照（避免丢失修改）
     *   2. 把分支 HEAD 内容写回文件
     *   3. 标记 activeBranch[file] = branchId
     */
    fun switchToBranch(filePath: String, branchId: String): Boolean {
        val branch = branches[branchId] ?: return false
        if (branch.filePath != filePath) return false

        // 1. 保存当前状态到 main 快照
        val file = File(filePath)
        if (file.exists()) {
            val currentContent = file.readText()
            val mainSnap = FileSnapshot.create(
                filePath = filePath,
                relativePath = branch.name + ".main",
                content = currentContent,
                changeType = com.apex.lib.workingfiles.snapshot.ChangeType.MODIFY,
                source = com.apex.lib.workingfiles.snapshot.ChangeSource.USER,
                description = "切换到分支 ${branch.name} 前（main 状态）"
            )
            snapshotStorage.save(mainSnap)
        }

        // 2. 写回分支 HEAD 内容
        val branchSnap = snapshotStorage.load(branch.headSnapshotId) ?: return false
        file.parentFile?.mkdirs()
        file.writeText(branchSnap.content)

        // 3. 标记
        activeBranch[filePath] = branchId
        ApexLog.i("working-files", "[Branch] switched to: $branchId for $filePath")
        return true
    }

    /**
     * 切换回 main 分支。
     */
    fun switchToMain(filePath: String): Boolean {
        // 保存当前分支状态
        val currentBranchId = activeBranch[filePath]
        if (currentBranchId != null) {
            val branch = branches[currentBranchId]
            val file = File(filePath)
            if (branch != null && file.exists()) {
                val content = file.readText()
                val branchSnap = FileSnapshot.create(
                    filePath = filePath,
                    relativePath = branch.name,
                    content = content,
                    changeType = com.apex.lib.workingfiles.snapshot.ChangeType.MODIFY,
                    source = com.apex.lib.workingfiles.snapshot.ChangeSource.AGENT,
                    agentId = branch.agentId,
                    description = "分支 ${branch.name} 状态保存"
                )
                snapshotStorage.save(branchSnap)
                branch.headSnapshotId = branchSnap.id
                branch.updatedAt = System.currentTimeMillis()
                persist()
            }
        }

        // 恢复 main 最新状态
        val mainSnaps = snapshotStorage.listSnapshots(filePath)
            .filter { it.description.contains("(main") || it.source == com.apex.lib.workingfiles.snapshot.ChangeSource.MANUAL }
        val latestMain = mainSnaps.lastOrNull() ?: snapshotStorage.getLatestSnapshot(filePath)
        if (latestMain != null) {
            val file = File(filePath)
            file.writeText(latestMain.content)
        }
        activeBranch[filePath] = null
        ApexLog.i("working-files", "[Branch] switched to main for $filePath")
        return true
    }

    /**
     * 合并分支到 main。
     *
     * @param branchId 分支 ID
     * @param strategy 合并策略（如果有冲突）
     */
    fun mergeBranch(branchId: String, strategy: MergeStrategy = MergeStrategy.MERGE_MANUAL): BranchMergeResult {
        val branch = branches[branchId] ?: return BranchMergeResult(false, branchId, message = "branch not found")
        if (!branch.isActive) return BranchMergeResult(false, branchId, message = "branch not active")

        // 先切回 main
        if (activeBranch[branch.filePath] == branchId) {
            switchToMain(branch.filePath)
        }

        val branchSnap = snapshotStorage.load(branch.headSnapshotId) ?: return BranchMergeResult(false, branchId, message = "branch head not found")
        val mainSnap = snapshotStorage.getLatestSnapshot(branch.filePath)
        val file = File(branch.filePath)

        // 检测冲突
        if (mainSnap != null) {
            val diff = DiffComputer.compute(mainSnap.content, branchSnap.content)
            // 简单冲突判定：双方都有修改
            val baseDiff = DiffComputer.compute(
                snapshotStorage.load(branch.baseSnapshotId)?.content ?: "",
                mainSnap.content
            )
            val hasConflict = baseDiff.summary.addedLines + baseDiff.summary.removedLines > 0 &&
                              diff.summary.addedLines + diff.summary.removedLines > 0

            if (hasConflict && strategy == MergeStrategy.MERGE_MANUAL) {
                // 生成冲突标记内容
                val conflictContent = generateConflictMarkers(mainSnap.content, branchSnap.content)
                file.writeText(conflictContent)
                val newSnap = FileSnapshot.create(
                    filePath = branch.filePath,
                    relativePath = branch.name + ".merge-conflict",
                    content = conflictContent,
                    changeType = com.apex.lib.workingfiles.snapshot.ChangeType.MODIFY,
                    source = com.apex.lib.workingfiles.snapshot.ChangeSource.USER,
                    description = "合并冲突标记（分支 ${branch.name}）"
                )
                snapshotStorage.save(newSnap)
                return BranchMergeResult(
                    success = false,
                    branchId = branchId,
                    newSnapshotId = newSnap.id,
                    conflict = BranchMergeConflict(branchId, mainSnap.id, branchSnap.id, countConflictLines(conflictContent), strategy),
                    message = "检测到冲突，已生成冲突标记，请手动解决"
                )
            }
        }

        // 应用分支内容（按策略）
        val contentToApply = when (strategy) {
            MergeStrategy.MERGE_KEEP_MAIN -> mainSnap?.content ?: branchSnap.content
            MergeStrategy.MERGE_KEEP_BRANCH, MergeStrategy.MERGE_MANUAL -> branchSnap.content
        }
        file.writeText(contentToApply)

        val mergedSnap = FileSnapshot.create(
            filePath = branch.filePath,
            relativePath = branch.filePath,
            content = contentToApply,
            changeType = com.apex.lib.workingfiles.snapshot.ChangeType.MODIFY,
            source = com.apex.lib.workingfiles.snapshot.ChangeSource.USER,
            agentId = branch.agentId,
            description = "合并分支 ${branch.name}（策略: ${strategy.displayName}）"
        )
        snapshotStorage.save(mergedSnap)

        branch.status = BranchStatus.MERGED
        branch.updatedAt = System.currentTimeMillis()
        persist()

        return BranchMergeResult(
            success = true,
            branchId = branchId,
            newSnapshotId = mergedSnap.id,
            message = "分支已合并到 main"
        )
    }

    /**
     * 丢弃分支（不影响 main）。
     */
    fun discardBranch(branchId: String): Boolean {
        val branch = branches[branchId] ?: return false
        branch.status = BranchStatus.DISCARDED
        branch.updatedAt = System.currentTimeMillis()
        // 如果当前正在此分支，切回 main
        if (activeBranch[branch.filePath] == branchId) {
            switchToMain(branch.filePath)
        }
        persist()
        return true
    }

    /**
     * 列出文件的所有分支。
     */
    fun listBranches(filePath: String): List<VirtualBranch> {
        return branches.values.filter { it.filePath == filePath }.sortedByDescending { it.updatedAt }
    }

    /**
     * 列出活跃分支。
     */
    fun listActiveBranches(filePath: String): List<VirtualBranch> {
        return listBranches(filePath).filter { it.isActive }
    }

    /**
     * 获取当前活跃分支。
     */
    fun getActiveBranch(filePath: String): VirtualBranch? {
        val id = activeBranch[filePath] ?: return null
        return branches[id]
    }

    /**
     * 获取分支的 base 与 head 之间的 diff。
     */
    fun getBranchDiff(branchId: String): com.apex.lib.workingfiles.diff.FileDiff? {
        val branch = branches[branchId] ?: return null
        val base = snapshotStorage.load(branch.baseSnapshotId) ?: return null
        val head = snapshotStorage.load(branch.headSnapshotId) ?: return null
        return DiffComputer.compute(base.content, head.content).copy(
            oldFilePath = "${base.relativePath} (base)",
            newFilePath = "${head.relativePath} (${branch.name})"
        )
    }

    /**
     * 锁定分支（防止其他 Agent 修改）。
     */
    fun lockBranch(branchId: String): Boolean {
        val branch = branches[branchId] ?: return false
        branch.status = BranchStatus.LOCKED
        persist()
        return true
    }

    fun unlockBranch(branchId: String): Boolean {
        val branch = branches[branchId] ?: return false
        branch.status = BranchStatus.ACTIVE
        persist()
        return true
    }

    /**
     * 删除分支（彻底删除，不可恢复）。
     */
    fun deleteBranch(branchId: String): Boolean {
        val branch = branches[branchId] ?: return false
        if (branch.isActive) {
            // 活跃分支不允许直接删除
            return false
        }
        branches.remove(branchId)
        persist()
        return true
    }

    // ===== 内部辅助 =====

    private fun generateConflictMarkers(main: String, branch: String): String {
        val mainLines = main.split("\n")
        val branchLines = branch.split("\n")
        val sb = StringBuilder()
        sb.append("<<<<<<< main\n")
        sb.append(mainLines.joinToString("\n"))
        sb.append("\n=======\n")
        sb.append(branchLines.joinToString("\n"))
        sb.append("\n>>>>>>> branch\n")
        return sb.toString()
    }

    private fun countConflictLines(content: String): Int {
        return content.split("\n").count {
            it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") || it.startsWith("=======")
        }
    }

    private fun pickColor(name: String): String {
        val colors = listOf("#007ACC", "#FF5722", "#4CAF50", "#9C27B0", "#FF9800", "#00BCD4", "#E91E63", "#3F51B5")
        val hash = name.hashCode().and(0x7FFFFFFF)
        return colors[hash % colors.size]
    }

    private fun generateId(): String {
        val ts = System.currentTimeMillis().toString(36)
        val rand = (0 until 6).map { ('a'..'z').random() }.joinToString("")
        return "branch-$ts-$rand"
    }

    private fun persist() {
        try {
            val data = branches.map { it.value }
            val active = activeBranch.mapKeys { it.key }
            val tmp = File(storageDir, "branches.json.tmp")
            val payload = mapOf(
                "branches" to data,
                "active" to active
            )
            tmp.writeText(json.encodeToString(payload))
            tmp.renameTo(branchesFile)
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[Branch] persist failed: ${t.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadBranches() {
        try {
            if (!branchesFile.exists()) return
            val data = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(branchesFile.readText())
            (data["branches"] as? kotlinx.serialization.json.JsonArray)?.forEach { elem ->
                val branch = json.decodeFromString(VirtualBranch.serializer(), elem.toString())
                branches[branch.id] = branch
            }
            (data["active"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
                activeBranch[k] = (v as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[Branch] loadBranches failed: ${t.message}")
        }
    }
}
