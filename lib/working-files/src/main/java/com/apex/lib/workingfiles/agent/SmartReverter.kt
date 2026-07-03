package com.apex.lib.workingfiles.agent

import com.apex.lib.workingfiles.diff.DiffComputer
import com.apex.lib.workingfiles.snapshot.ChangeSource
import com.apex.lib.workingfiles.snapshot.ChangeType
import com.apex.lib.workingfiles.snapshot.FileSnapshot
import com.apex.lib.workingfiles.snapshot.SnapshotStorage
import com.apex.sdk.common.ApexLog

/**
 * 智能回退器 — Apex 独有的"按 Agent 步骤回退"能力。
 *
 * **创新点**（VSCode/Cline/Aider 都只能按单个快照回退）：
 *   - 选择一个 Agent 步骤，回退它和它"下游"的所有步骤
 *   - 保留与该步骤无关的其他 Agent 成果
 *   - 自动计算需要回退的文件 + 目标内容
 *   - 生成新的快照记录此次回退
 *
 * **典型场景**：
 *   Agent 在一次会话中执行了 5 步：
 *     1. 读文件 A
 *     2. 改文件 A（添加函数）
 *     3. 读文件 B
 *     4. 改文件 B（调用 A 的函数）
 *     5. 改文件 B（添加日志）
 *
 *   用户发现第 2 步的实现不好，想回退。
 *   - 传统做法：手动找到第 2 步的快照，回退（会丢失 4、5 步的成果）
 *   - Apex 智能回退：
 *     - 文件 A：回退到第 2 步之前的状态
 *     - 文件 B：保留第 4、5 步的成果（因为它们不依赖第 2 步的"具体实现"）
 *     - 但如果第 4 步调用了第 2 步添加的函数，会提示"代码可能引用已删除的函数"
 *
 * **简化实现**：
 *   - 当前版本：按步骤的 affectedFiles 找到所有受影响的文件
 *   - 对每个文件，找到该步骤的"前置快照"，回退到那个快照
 *   - 不做依赖分析（依赖分析需要 AST 解析，复杂度高，留作 v2）
 *
 *   保留：与回退步骤的 affectedFiles 无交集的其他文件
 */
class SmartReverter(
    private val snapshotStorage: SnapshotStorage,
    private val flowStorage: AgentFlowStorage
) {

    /**
     * 分析回退某步骤会影响的文件和步骤。
     *
     * @param sessionId Agent 会话 ID
     * @param stepId 要回退的步骤 ID
     * @return [RevertAnalysis] 含影响范围
     */
    fun analyzeRevert(sessionId: String, stepId: String): RevertAnalysis? {
        val flow = flowStorage.getFlow(sessionId) ?: return null
        val targetStep = flow.steps.firstOrNull { it.id == stepId } ?: return null

        // 找到目标步骤之后的所有步骤（按 order）
        val laterSteps = flow.steps.filter { it.order > targetStep.order }

        // 受影响的文件：目标步骤 + 后续步骤中所有 affectedFiles
        val targetFiles = targetStep.affectedFiles.toSet()
        val laterAffectedFiles = laterSteps.flatMap { it.affectedFiles }.toSet()

        // 需要回退的文件：目标步骤影响的文件
        val filesToRevert = targetFiles.toList()

        // 受影响但保留的文件：后续步骤影响但不在目标步骤中的文件
        val filesToKeep = (laterAffectedFiles - targetFiles).toList()

        // 后续步骤中"可能受影响"的步骤（影响了 filesToRevert 的）
        val impactedSteps = laterSteps.filter { step ->
            step.affectedFiles.any { it in targetFiles }
        }

        // 为每个需要回退的文件找到目标快照
        val fileTargets = filesToRevert.mapNotNull { filePath ->
            val snapshots = snapshotStorage.listSnapshots(filePath)
            // 找到目标步骤前的最后一个快照
            val targetSnapshot = snapshots.lastOrNull { snap ->
                snap.timestamp < targetStep.timestamp && snap.sessionId == sessionId
            } ?: snapshots.lastOrNull { it.timestamp < targetStep.timestamp }
            if (targetSnapshot != null) {
                FileRevertTarget(filePath, targetSnapshot.id, targetSnapshot.timestamp)
            } else null
        }

        return RevertAnalysis(
            sessionId = sessionId,
            targetStepId = stepId,
            targetStepTitle = targetStep.title,
            targetStepOrder = targetStep.order,
            filesToRevert = fileTargets,
            filesToKeep = filesToKeep,
            impactedLaterSteps = impactedSteps.map { it.id },
            warnings = buildWarnings(filesToRevert, filesToKeep, impactedSteps)
        )
    }

    /**
     * 执行智能回退。
     *
     * @param analysis [analyzeRevert] 的结果
     * @param operator 操作者
     * @return 回退结果
     */
    fun executeRevert(analysis: RevertAnalysis, operator: String = "user"): RevertResult {
        val revertedFiles = mutableListOf<String>()
        val newSnapshotIds = mutableListOf<String>()

        for (target in analysis.filesToRevert) {
            val snapshot = snapshotStorage.load(target.targetSnapshotId) ?: continue
            val file = java.io.File(snapshot.filePath)
            file.parentFile?.mkdirs()

            if (snapshot.changeType == ChangeType.DELETE) {
                file.delete()
            } else {
                file.writeText(snapshot.content)
            }

            // 生成回退快照
            val revertSnap = FileSnapshot.create(
                filePath = snapshot.filePath,
                relativePath = snapshot.relativePath,
                content = snapshot.content,
                changeType = ChangeType.MODIFY,
                source = ChangeSource.USER,
                sessionId = analysis.sessionId,
                description = "智能回退到步骤 #${analysis.targetStepOrder} 之前的状态（${snapshot.description}）"
            )
            snapshotStorage.save(revertSnap)
            revertedFiles.add(snapshot.filePath)
            newSnapshotIds.add(revertSnap.id)
        }

        // 在 Agent 流程中记录此次回退
        flowStorage.addStep(
            sessionId = analysis.sessionId,
            agentId = operator,
            agentName = "用户",
            type = AgentStepType.ROLLBACK,
            title = "智能回退步骤 #${analysis.targetStepOrder}",
            description = "回退 ${analysis.targetStepTitle}（影响 ${revertedFiles.size} 个文件，保留 ${analysis.filesToKeep.size} 个文件）",
            action = "smart-revert step=${analysis.targetStepId}",
            affectedFiles = revertedFiles,
            snapshotIds = newSnapshotIds
        )

        ApexLog.i("working-files", "[SmartReverter] reverted ${revertedFiles.size} files, kept ${analysis.filesToKeep.size}")

        return RevertResult(
            success = true,
            revertedFiles = revertedFiles,
            keptFiles = analysis.filesToKeep,
            newSnapshotIds = newSnapshotIds,
            warnings = analysis.warnings
        )
    }

    private fun buildWarnings(
        filesToRevert: List<FileRevertTarget>,
        filesToKeep: List<String>,
        impactedSteps: List<AgentStep>
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (filesToKeep.isNotEmpty()) {
            warnings.add("以下文件未回退，可能引用已变更的代码：${filesToKeep.joinToString(", ")}")
        }
        if (impactedSteps.isNotEmpty()) {
            warnings.add("${impactedSteps.size} 个后续步骤可能受影响：${impactedSteps.joinToString(", ") { "#${it.order} ${it.title}" }}")
        }
        return warnings
    }
}

/** 回退分析结果。 */
data class RevertAnalysis(
    val sessionId: String,
    val targetStepId: String,
    val targetStepTitle: String,
    val targetStepOrder: Int,
    val filesToRevert: List<FileRevertTarget>,
    val filesToKeep: List<String>,
    val impactedLaterSteps: List<String>,
    val warnings: List<String>
) {
    /** 是否有风险。 */
    val hasRisk: Boolean get() = warnings.isNotEmpty()

    /** 影响摘要。 */
    val summary: String
        get() = "回退 #${targetStepOrder} ${targetStepTitle}：" +
                "回退 ${filesToRevert.size} 个文件，" +
                "保留 ${filesToKeep.size} 个文件，" +
                "${impactedLaterSteps.size} 个后续步骤可能受影响"
}

/** 单个文件的回退目标。 */
data class FileRevertTarget(
    val filePath: String,
    val targetSnapshotId: String,
    val targetTimestamp: Long
)

/** 回退执行结果。 */
data class RevertResult(
    val success: Boolean,
    val revertedFiles: List<String>,
    val keptFiles: List<String>,
    val newSnapshotIds: List<String>,
    val warnings: List<String>
)
