package com.apex.agent.kernel.burst.enhanced.pipeline.analyzer

import com.apex.agent.kernel.burst.enhanced.pipeline.ExecutionPipelineEngine
import com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator.PipelineOrchestrator
import java.util.concurrent.ConcurrentHashMap

/**
 * B40: 流水线分析器
 *
 * 流水线性能分析与优化建议：
 * - 瓶颈检测
 * - 并行化建议
 * - 超时优化
 * - 缓存建议
 * - 成本估算
 */
class PipelineAnalyzer {

    data class AnalysisReport(
        val pipelineName: String,
        val totalSteps: Int,
        val estimatedDurationMs: Long,
        val bottleneckStep: String?,
        val parallelizableSteps: List<List<String>>,
        val recommendations: List<Recommendation>,
        val costEstimate: CostEstimate
    )

    data class Recommendation(
        val type: RecommendationType,
        val priority: RecommendationPriority,
        val title: String,
        val description: String,
        val affectedSteps: List<String>,
        val estimatedImprovement: String
    )

    enum class RecommendationType {
        PARALLELIZE,       // 可并行化
        ADD_CACHE,         // 添加缓存
        REDUCE_TIMEOUT,    // 减少超时
        INCREASE_TIMEOUT,  // 增加超时
        ADD_RETRY,         // 添加重试
        REMOVE_STEP,       // 移除冗余步骤
        MERGE_STEPS,       // 合并步骤
        ADD_CHECKPOINT,    // 添加检查点
        CHANGE_ORDER       // 调整顺序
    }

    enum class RecommendationPriority { LOW, MEDIUM, HIGH, CRITICAL }

    data class CostEstimate(
        val estimatedTokens: Long,
        val estimatedApiCalls: Int,
        val estimatedDurationMs: Long,
        val estimatedCost: Float
    )

    private val executionHistory = ConcurrentHashMap<String, MutableList<StepExecutionRecord>>()

    data class StepExecutionRecord(
        val pipelineId: String,
        val stepId: String,
        val skillId: String,
        val durationMs: Long,
        val success: Boolean,
        val tokensUsed: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 记录步骤执行
     */
    fun recordExecution(record: StepExecutionRecord) {
        val history = executionHistory.computeIfAbsent(record.pipelineId) { mutableListOf() }
        history.add(record)
        while (history.size > 500) history.removeAt(0)
    }

    /**
     * 分析流水线
     */
    fun analyze(definition: PipelineOrchestrator.PipelineDefinition): AnalysisReport {
        val stepDurations = mutableMapOf<String, Long>()
        val stepTokens = mutableMapOf<String, Long>()

        // 从历史估算
        definition.steps.forEach { step ->
            val history = executionHistory[definition.id]
            val stepHistory = history?.filter { it.stepId == step.id }
            val avgDuration = stepHistory?.map { it.durationMs }?.average()?.toLong() ?: estimateDuration(step.skillId)
            val avgTokens = stepHistory?.map { it.tokensUsed }?.average()?.toLong() ?: estimateTokens(step.skillId)
            stepDurations[step.id] = avgDuration
            stepTokens[step.id] = avgTokens
        }

        val totalDuration = stepDurations.values.sum()
        val bottleneck = stepDurations.maxByOrNull { it.value }?.key

        // 检测可并行化的步骤
        val parallelizable = detectParallelizableSteps(definition.steps)

        // 生成建议
        val recommendations = mutableListOf<Recommendation>()

        // 瓶颈建议
        if (bottleneck != null && stepDurations[bottleneck]!! > totalDuration * 0.5) {
            recommendations.add(Recommendation(
                type = RecommendationType.PARALLELIZE,
                priority = RecommendationPriority.HIGH,
                title = "瓶颈步骤 $bottleneck 可并行化",
                description = "步骤 $bottleneck 占总耗时 ${stepDurations[bottleneck]!! * 100 / totalDuration}%，考虑拆分为并行子任务",
                affectedSteps = listOf(bottleneck),
                estimatedImprovement = "预计减少 ${stepDurations[bottleneck]!! / 2}ms"
            ))
        }

        // 超时建议
        definition.steps.forEach { step ->
            val duration = stepDurations[step.id] ?: 0
            if (duration > 60_000 && definition.config.timeoutMs < duration * 2) {
                recommendations.add(Recommendation(
                    type = RecommendationType.INCREASE_TIMEOUT,
                    priority = RecommendationPriority.HIGH,
                    title = "步骤 ${step.id} 超时风险",
                    description = "平均耗时 ${duration}ms，当前超时 ${definition.config.timeoutMs}ms，可能不够",
                    affectedSteps = listOf(step.id),
                    estimatedImprovement = "建议超时设为 ${duration * 2}ms"
                ))
            }
            if (duration < 5000 && definition.config.timeoutMs > 60_000) {
                recommendations.add(Recommendation(
                    type = RecommendationType.REDUCE_TIMEOUT,
                    priority = RecommendationPriority.LOW,
                    title = "步骤 ${step.id} 超时过长",
                    description = "平均耗时 ${duration}ms，超时 ${definition.config.timeoutMs}ms 过长",
                    affectedSteps = listOf(step.id),
                    estimatedImprovement = "建议超时设为 ${duration * 3}ms"
                ))
            }
        }

        // 缓存建议
        definition.steps.forEach { step ->
            val history = executionHistory[definition.id]
            val cacheableCount = history?.count { it.stepId == step.id && it.success } ?: 0
            if (cacheableCount > 5) {
                recommendations.add(Recommendation(
                    type = RecommendationType.ADD_CACHE,
                    priority = RecommendationPriority.MEDIUM,
                    title = "步骤 ${step.id} 建议添加缓存",
                    description = "此步骤已成功执行 $cacheableCount 次，结果可缓存复用",
                    affectedSteps = listOf(step.id),
                    estimatedImprovement = "预计减少 ${stepDurations[step.id] ?: 0}ms（缓存命中时）"
                ))
            }
        }

        // 检查点建议
        if (definition.steps.size > 3 && !definition.config.enableCheckpointing) {
            recommendations.add(Recommendation(
                type = RecommendationType.ADD_CHECKPOINT,
                priority = RecommendationPriority.MEDIUM,
                title = "建议启用检查点",
                description = "流水线有 ${definition.steps.size} 个步骤，启用检查点可在中断后恢复",
                affectedSteps = emptyList(),
                estimatedImprovement = "提高可靠性"
            ))
        }

        // 成本估算
        val totalTokens = stepTokens.values.sum()
        val estimatedApiCalls = definition.steps.size
        val estimatedCost = totalTokens * 0.00001f  // 假设每 token 0.00001 元

        return AnalysisReport(
            pipelineName = definition.name,
            totalSteps = definition.steps.size,
            estimatedDurationMs = totalDuration,
            bottleneckStep = bottleneck,
            parallelizableSteps = parallelizable,
            recommendations = recommendations,
            costEstimate = CostEstimate(totalTokens, estimatedApiCalls, totalDuration, estimatedCost)
        )
    }

    /**
     * 检测可并行化的步骤
     */
    private fun detectParallelizableSteps(steps: List<ExecutionPipelineEngine.PipelineStep>): List<List<String>> {
        // 简化：连续的 Sequential 步骤如果没有数据依赖，可以并行
        val groups = mutableListOf<MutableList<String>>()
        var currentGroup = mutableListOf<String>()

        for (step in steps) {
            if (step is ExecutionPipelineEngine.PipelineStep.Sequential) {
                currentGroup.add(step.id)
            } else {
                if (currentGroup.size > 1) groups.add(currentGroup)
                currentGroup = mutableListOf()
            }
        }
        if (currentGroup.size > 1) groups.add(currentGroup)
        return groups
    }

    private fun estimateDuration(skillId: String): Long {
        return when {
            skillId.contains("tree-of-thoughts") || skillId.contains("extreme") -> 30_000L
            skillId.contains("multi-hop") || skillId.contains("adversarial") -> 20_000L
            skillId.contains("react") || skillId.contains("chain") -> 10_000L
            skillId.contains("search") || skillId.contains("rag") -> 5_000L
            else -> 8_000L
        }
    }

    private fun estimateTokens(skillId: String): Long {
        return when {
            skillId.contains("tree-of-thoughts") -> 8000L
            skillId.contains("extreme") -> 10000L
            skillId.contains("multi-hop") -> 5000L
            skillId.contains("react") -> 3000L
            skillId.contains("chain") -> 2000L
            else -> 2000L
        }
    }

    /**
     * 生成分析报告
     */
    fun generateReport(definition: PipelineOrchestrator.PipelineDefinition): String {
        val analysis = analyze(definition)
        val sb = StringBuilder()
        sb.appendLine("═══ 流水线分析报告 ═══")
        sb.appendLine("流水线: ${analysis.pipelineName}")
        sb.appendLine("步骤数: ${analysis.totalSteps}")
        sb.appendLine("预估耗时: ${analysis.estimatedDurationMs}ms")
        sb.appendLine("瓶颈步骤: ${analysis.bottleneckStep ?: "无"}")
        sb.appendLine()
        sb.appendLine("成本估算:")
        sb.appendLine("  Token: ${analysis.costEstimate.estimatedTokens}")
        sb.appendLine("  API 调用: ${analysis.costEstimate.estimatedApiCalls}")
        sb.appendLine("  预估费用: ¥${analysis.costEstimate.estimatedCost}")
        sb.appendLine()
        if (analysis.parallelizableSteps.isNotEmpty()) {
            sb.appendLine("可并行化的步骤组:")
            analysis.parallelizableSteps.forEach { group ->
                sb.appendLine("  ${group.joinToString(", ")}")
            }
            sb.appendLine()
        }
        if (analysis.recommendations.isNotEmpty()) {
            sb.appendLine("优化建议 (${analysis.recommendations.size}):")
            analysis.recommendations.forEach { rec ->
                sb.appendLine("  [${rec.priority}] ${rec.title}")
                sb.appendLine("    ${rec.description}")
                sb.appendLine("    预期: ${rec.estimatedImprovement}")
            }
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
