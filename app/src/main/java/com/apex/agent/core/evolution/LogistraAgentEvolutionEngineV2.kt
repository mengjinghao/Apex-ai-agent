package com.apex.agent.core.evolution

import android.content.Context
import com.apex.data.model.EvolutionMetadata
import com.apex.data.model.EvolutionNode
import com.apex.data.model.EvolutionNodeType
import com.apex.data.model.FitnessRecord
import com.apex.data.model.LogistraSkillSpecV2
import com.apex.util.AppLogger

data class EvolutionResultV2(
    val evolvedSkill: LogistraSkillSpecV2,
    val evaluationScore: Float,
    val iterationCount: Int
)

class LogistraAgentEvolutionEngineV2(
    private val context: Context,
    private val evaluator: SemanticEvaluator = SemanticEvaluator(context),
    private val llmEngine: LLMAssistedEvolutionEngine = LLMAssistedEvolutionEngine(context, evaluator),
    private val versionManager: SkillVersionManager = SkillVersionManager(context)
) {
    companion object {
        private const val TAG = "LogistraEvolutionV2"
        private const val EVOLUTION_THRESHOLD = 8.0f
    }

    private var iterationCount = 0

    suspend fun runEvolutionPipeline(
        currentSkill: LogistraSkillSpecV2,
        taskGoal: String,
        executionLogs: List<String>,
        finalOutput: String
    ): EvolutionResultV2 {
        iterationCount++

        AppLogger.d(TAG, "=== Evolution Cycle #${iterationCount} for ${currentSkill.skillId} ===")

        // Step 1: 语义评估
    val evaluation = evaluator.evaluateExecution(
            taskGoal = taskGoal,
            executionLogs = executionLogs,
            finalOutput = finalOutput
        )
        AppLogger.d(TAG, "Step 1: Semantic evaluation score = ${evaluation.score}")

        // Step 2: 更新 Fitness 记录
        currentSkill.metadata.fitnessHistory.add(
            FitnessRecord(
                timestamp = System.currentTimeMillis(),
                score = evaluation.score,
                metrics = mapOf("success" to if (evaluation.success) 1.0f else 0.0f),
                feedback = evaluation.failureReason
            )
        )
        versionManager.saveSkillVersion(currentSkill)
        AppLogger.d(TAG, "Step 2: Fitness history updated (${currentSkill.metadata.fitnessHistory.size} records)")

        // Step 3: 如果评分低于阈值，触发 LLM 变异
    val evolvedSkill = if (evaluation.score < EVOLUTION_THRESHOLD) {
            AppLogger.d(TAG, "Step 3: Score below threshold (${EVOLUTION_THRESHOLD}), triggering LLM mutation...")
            val newSkill = llmEngine.evolveSkill(
                currentSkill = currentSkill,
                taskGoal = taskGoal,
                executionLogs = executionLogs,
                finalOutput = finalOutput,
                evaluationResult = evaluation
            )
            versionManager.saveSkillVersion(newSkill)
            newSkill
        } else {
            AppLogger.d(TAG, "Step 3: Score above threshold, keeping current version")
            currentSkill
        }

        // Step 4: 多版本晋的降�?
        AppLogger.d(TAG, "Step 4: Running multi-version promotion analysis...")
        versionManager.promoteVersions(currentSkill.skillId)

        // Step 5: 输出迭代信息（此处简化，实际应更新RL策略权重等）
        AppLogger.d(TAG, "=== Evolution Cycle #${iterationCount} Complete ===")

        return EvolutionResultV2(
            evolvedSkill = evolvedSkill,
            evaluationScore = evaluation.score,
            iterationCount = iterationCount
        )
    }

    /**
     * 便捷方法：从任务目标和工具序列创建初始技�?     */
    fun createInitialSkill(
        skillId: String,
        name: String,
        description: String,
        taskType: String,
        rootActions: List<Pair<String, Map<String, Any?>>>
    ): LogistraSkillSpecV2 {
        val nodes = rootActions.mapIndexed { idx, (content, params) ->
            EvolutionNode(
                id = "node_${idx}_${skillId}",
                type = EvolutionNodeType.ACTION,
                content = content,
                parameters = params
            )
        }

        return LogistraSkillSpecV2(
            skillId = skillId,
            name = name,
            description = description,
            rootNode = EvolutionNode(
                id = "root_${skillId}",
                type = EvolutionNodeType.COMPOSITE,
                content = "Root",
                children = nodes
            ),
            metadata = EvolutionMetadata(
                version = "1.0.0",
                parentVersion = null,
                evolutionMethod = "INITIAL"
            ),
            status = LogistraSkillSpecV2.SkillStatus.STABLE,
            taskType = taskType,
            tags = listOf("initial", taskType)
        )
    }

    fun getVersionForExecution(skillId: String): LogistraSkillSpecV2? {
        return versionManager.routeToVersion(skillId)
    }
}
