package com.apex.agent.kernel.burst.enhanced.pipeline.checkpoint

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * B34: 流水线检查点
 *
 * 流水线状态持久化与恢复：
 * - 步骤级检查点
 * - 中断恢复
 * - 回滚到指定步骤
 * - 检查点压缩
 */
class PipelineCheckpoint {

    data class PipelineCheckpointData(
        val checkpointId: String,
        val pipelineId: String,
        val stepIndex: Int,
        val stepId: String,
        val timestamp: Long,
        val inputData: String,
        val outputData: String?,
        val variables: Map<String, Any>,
        val stepResults: Map<String, String>,
        val isComplete: Boolean,
        val metadata: Map<String, String> = emptyMap()
    )

    data class RecoveryPlan(
        val pipelineId: String,
        val fromCheckpoint: PipelineCheckpointData,
        val remainingSteps: List<String>,
        val estimatedRecoveryMs: Long,
        val strategy: RecoveryStrategy
    )

    enum class RecoveryStrategy {
        RESUME_FROM_CHECKPOINT,   // 从检查点恢复
        REPLAY_FROM_STEP,         // 从指定步骤重放
        FULL_RESTART,             // 完全重启
        SKIP_FAILED_STEP          // 跳过失败步骤
    }

    private val checkpoints = ConcurrentHashMap<String, ConcurrentSkipListMap<Int, PipelineCheckpointData>>()
    private val maxCheckpointsPerPipeline = 20

    /**
     * 保存检查点
     */
    fun save(
        pipelineId: String,
        stepIndex: Int,
        stepId: String,
        inputData: String,
        outputData: String?,
        variables: Map<String, Any> = emptyMap(),
        stepResults: Map<String, String> = emptyMap(),
        metadata: Map<String, String> = emptyMap()
    ): PipelineCheckpointData {
        val pipelineCps = checkpoints.computeIfAbsent(pipelineId) { ConcurrentSkipListMap() }
        val cp = PipelineCheckpointData(
            checkpointId = "cp_${pipelineId}_${stepIndex}_${System.currentTimeMillis()}",
            pipelineId = pipelineId,
            stepIndex = stepIndex,
            stepId = stepId,
            timestamp = System.currentTimeMillis(),
            inputData = inputData.take(5000),  // 限制大小
            outputData = outputData?.take(5000),
            variables = variables,
            stepResults = stepResults,
            isComplete = false,
            metadata = metadata
        )
        pipelineCps[stepIndex] = cp
        // 限制检查点数量
        while (pipelineCps.size > maxCheckpointsPerPipeline) {
            pipelineCps.pollFirstEntry()
        }
        return cp
    }

    /**
     * 标记完成
     */
    fun markComplete(pipelineId: String) {
        val pipelineCps = checkpoints[pipelineId] ?: return
        val last = pipelineCps.lastEntry()?.value ?: return
        pipelineCps[last.key] = last.copy(isComplete = true)
    }

    /**
     * 获取最新检查点
     */
    fun getLatest(pipelineId: String): PipelineCheckpointData? {
        return checkpoints[pipelineId]?.lastEntry()?.value
    }

    /**
     * 获取指定步骤的检查点
     */
    fun getByStep(pipelineId: String, stepIndex: Int): PipelineCheckpointData? {
        return checkpoints[pipelineId]?.get(stepIndex)
    }

    /**
     * 创建恢复计划
     */
    fun createRecoveryPlan(pipelineId: String, allStepIds: List<String>): RecoveryPlan? {
        val latest = getLatest(pipelineId) ?: return null
        val remainingSteps = allStepIds.drop(latest.stepIndex + 1)
        val strategy = when {
            latest.isComplete -> RecoveryStrategy.RESUME_FROM_CHECKPOINT
            remainingSteps.isEmpty() -> RecoveryStrategy.FULL_RESTART
            else -> RecoveryStrategy.REPLAY_FROM_STEP
        }
        return RecoveryPlan(
            pipelineId = pipelineId,
            fromCheckpoint = latest,
            remainingSteps = remainingSteps,
            estimatedRecoveryMs = remainingSteps.size * 3000L,
            strategy = strategy
        )
    }

    /**
     * 获取所有检查点
     */
    fun getAll(pipelineId: String): List<PipelineCheckpointData> {
        return checkpoints[pipelineId]?.values?.toList() ?: emptyList()
    }

    /**
     * 清理
     */
    fun clear(pipelineId: String) {
        checkpoints.remove(pipelineId)
    }

    /**
     * 清理过期
     */
    fun cleanupExpired(maxAgeMs: Long = 3600_000L): Int {
        val threshold = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        checkpoints.forEach { (pipelineId, cps) ->
            val toRemove = cps.entries.filter { it.value.timestamp < threshold }
            toRemove.forEach { cps.remove(it.key); removed++ }
            if (cps.isEmpty()) checkpoints.remove(pipelineId)
        }
        return removed
    }

    /**
     * 获取统计
     */
    fun getStats(): CheckpointStats {
        val all = checkpoints.values.flatMap { it.values }
        return CheckpointStats(
            totalPipelines = checkpoints.size,
            totalCheckpoints = all.size,
            avgCheckpointsPerPipeline = if (checkpoints.isNotEmpty()) all.size / checkpoints.size else 0,
            oldestCheckpoint = all.minOfOrNull { it.timestamp },
            newestCheckpoint = all.maxOfOrNull { it.timestamp }
        )
    }

    data class CheckpointStats(
        val totalPipelines: Int,
        val totalCheckpoints: Int,
        val avgCheckpointsPerPipeline: Int,
        val oldestCheckpoint: Long?,
        val newestCheckpoint: Long?
    )
}
