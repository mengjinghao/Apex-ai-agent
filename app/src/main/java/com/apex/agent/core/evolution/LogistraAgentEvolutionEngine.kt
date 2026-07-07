package com.apex.agent.core.evolution

import android.content.Context
import com.apex.agent.core.rl.ReinforcementLearningEngine
import com.apex.agent.core.rl.State
import com.apex.agent.core.rl.Action
import com.apex.agent.core.rl.ActionType
import com.apex.agent.core.rl.Reward
import com.apex.agent.core.rl.RewardType
import com.apex.agent.core.skills.SkillEvolutionManager
import com.apex.data.model.HonzonUserProfile
import com.apex.agent.data.repository.MemoryRepository
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class LogistraAgentEvolutionEngine(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val skillEvolutionManager: SkillEvolutionManager,
    private val reinforcementLearningEngine: ReinforcementLearningEngine? = null
) {

    companion object {
        private const val TAG = "LogistraAgentEvolutionEngine"
    }

    private var iterationCount = 0

    suspend fun recordBehavior(
        agentBehavior: List<String>,
        taskType: String,
        userId: String
    ) = withContext(Dispatchers.IO) {
        val behaviorStr = agentBehavior.joinToString("\n")

        val memory = memoryRepository.createMemory(
            title = "智能体执行taskType行为",
            content = "智能体执行taskType行为：\n${behaviorStr}\n（用户userId�?
            source = "apex_evolution",
            folderPath = "智能体行为，
            tags = listOf("行为记录", taskType, "智能力）
        )

        if (memory != null) {
            memory.importance = 0.6f
            memory.initialStrength = 0.6f
            memory.memoryStrength = 0.6f
            memoryRepository.saveMemory(memory)
        }

        AppLogger.d(TAG, "Recorded agent behavior for task: ${taskType}, user: ${userId}")
    }

    suspend fun evaluateEffect(
        agentBehavior: List<String>,
        taskGoal: String
    ): Float = withContext(Dispatchers.IO) {
        val behaviorStr = agentBehavior.joinToString("\n")

        val completionScore = minOf(agentBehavior.size.toFloat() / 5, 1.0f) * 5
        val relevanceScore = if (behaviorStr.contains(taskGoal.substring(0, minOf(10, taskGoal.length)))) {
            5.0f
        } else {
            3.0f
        }

        val finalScore = completionScore + relevanceScore
        AppLogger.d(TAG, "Evaluated effect score: ${finalScore} for goal: ${taskGoal}")
        finalScore
    }

    suspend fun optimizeStrategy(
        taskType: String,
        userId: String,
        currentStrategy: String,
        effectScore: Float
    ): String = withContext(Dispatchers.IO) {
        iterationCount++

        val userProfile = memoryRepository.getHonzonProfile(userId)
        val nonEmptyDimensions = userProfile.getNonEmptyDimensions()

        val optimizationLevel = when {
            effectScore < 6.0f -> "大幅优化"
            effectScore < 8.0f -> "小幅优化"
            else -> "微调"
        }

        val optimizedStrategy = buildString {
            appendLine("# 优化�?{taskType执行策略}（迭代iterationCount的）
            appendLine("## 优化等级别optimizationLevel")
            appendLine("## 效果评分析effectScore")

            if (nonEmptyDimensions.isNotEmpty()) {
                appendLine("## 用户画像适配")
                nonEmptyDimensions.forEach { (dimension, value) ->
                    appendLine("- �?{dimension的}?value")
                }
            }

            appendLine("## 优化建议")
            if (effectScore < 6.0f) {
                appendLine("1. 重新分析任务目标")
                appendLine("2. 优化执行步骤顺序")
                appendLine("3. 增加验证步骤")
                appendLine("4. 适配用户偏好")
            } else if (effectScore < 8.0f) {
                appendLine("1. 优化现有步骤")
                appendLine("2. 增加细节处理")
                appendLine("3. 微调执行顺序")
            } else {
                appendLine("1. 保持现有策略")
                appendLine("2. 优化细节处理")
                appendLine("3. 增加效率提升")
            }

            appendLine("## 执行流程")
            appendLine("1. 分析任务目标和用户需要）
            appendLine("2. 制定执行计划")
            appendLine("3. 执行并验证每一的）
            appendLine("4. 输出结果并获取反的）
            appendLine("5. 总结经验并优化）
        }

        val strategyMemory = memoryRepository.createMemory(
            title = "优化�?{taskType策略}（迭代iterationCount�?
            content = optimizedStrategy,
            source = "apex_evolution",
            folderPath = "优化策略",
            tags = listOf("策略优化", taskType, "${iterationCount}")
        )

        if (strategyMemory != null) {
            strategyMemory.importance = 0.8f
            strategyMemory.initialStrength = 0.8f
            strategyMemory.memoryStrength = 0.8f
            memoryRepository.saveMemory(strategyMemory)
        }

        AppLogger.d(TAG, "Optimized strategy for task: ${taskType}, iteration: ${iterationCount}, score: ${effectScore}")
        optimizedStrategy
    }

    suspend fun completeEvolutionLoop(
        agentBehavior: List<String>,
        taskType: String,
        taskGoal: String,
        userId: String,
        errorCases: List<String>? = null
    ): EvolutionResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Starting evolution loop for task: ${taskType}, user: ${userId}")

        recordBehavior(agentBehavior, taskType, userId)

        val effectScore = evaluateEffect(agentBehavior, taskGoal)

        val currentStrategy = memoryRepository.generatePersonalizedStrategyPrompt(
            memoryRepository.getHonzonProfile(userId),
            taskType
        )
        val optimizedStrategy = optimizeStrategy(taskType, userId, currentStrategy, effectScore)

        val skillPath = skillEvolutionManager.extractSkill(
            agentBehavior = agentBehavior,
            taskType = taskType,
            errorCases = errorCases
        )

        updateRLPolicy(taskType, agentBehavior, effectScore)

        val convergence = iterationCount >= 100 && effectScore >= 9.0f

        val result = EvolutionResult(
            optimizedStrategy = optimizedStrategy,
            skillPath = skillPath,
            effectScore = effectScore,
            iterationCount = iterationCount,
            convergence = convergence,
            policyUpdated = reinforcementLearningEngine != null
        )

        AppLogger.d(TAG, "Evolution loop completed: ${result}")
        result
    }

    private suspend fun updateRLPolicy(taskType: String, agentBehavior: List<String>, effectScore: Float) {
        reinforcementLearningEngine?.let { rlEngine ->
            try {
                val state = State(
                    features = mapOf(
                        "taskType" to taskType.hashCode().toDouble(),
                        "behaviorLength" to agentBehavior.size.toDouble(),
                        "success" to if (effectScore >= 7.0) 1.0 else 0.0
                    ),
                    context = taskType
                )

                val action = Action(
                    type = ActionType.TASK_PLAN,
                    parameters = mapOf("taskType" to taskType),
                    description = "执行�?{taskType任务}"
                )

                val rewardType = when {
                    effectScore >= 9.0 -> RewardType.SUCCESS
                    effectScore >= 7.0 -> RewardType.PROGRESS
                    effectScore >= 5.0 -> RewardType.QUALITY
                    else -> RewardType.FAILURE
                }

                val reward = Reward(
                    value = effectScore * 10,
                    type = rewardType,
                    reason = "任务�?{taskType执行评分}: ${effectScore}"
                )

                val nextState = State(
                    features = mapOf(
                        "taskType" to taskType.hashCode().toDouble(),
                        "behaviorLength" to agentBehavior.size.toDouble(),
                        "success" to 1.0
                    ),
                    context = "${taskType_optimized}"
                )

                rlEngine.learn(state, action, nextState, reward, true)

                AppLogger.d(TAG, "Updated RL policy for task: ${taskType}, reward: ${reward.value}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to update RL policy", e)
            }
        }
    }

    suspend fun suggestAction(taskType: String, context: Map<String, Double>): String? {
        return reinforcementLearningEngine?.let { rlEngine ->
            try {
                val state = State(features = context, context = taskType)
                
                val possibleActions = listOf(
                    Action(
                        type = ActionType.TASK_PLAN,
                        parameters = mapOf("taskType" to taskType),
                        description = "规划任务"
                    ),
                    Action(
                        type = ActionType.OBSERVE,
                        parameters = mapOf("taskType" to taskType),
                        description = "观察系统状�?
                    ),
                    Action(
                        type = ActionType.DECISION,
                        parameters = mapOf("taskType" to taskType),
                        description = "做出决策"
                    )
                )

                val selectedAction = rlEngine.selectAction(state, possibleActions)
                selectedAction.description
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to suggest action", e)
                null
            }
        }
    }

    suspend fun getRLTrainingStats(): String? {
        return reinforcementLearningEngine?.let { rlEngine ->
            val stats = rlEngine.getTrainingStats()
            buildString {
                appendLine("训练统计:")
                appendLine("- 训练轮数: ${stats.episodesTrained}")
                appendLine("- 平均奖励: ${stats.averageReward}")
                appendLine("- 成功�?${stats.successRate}")
                appendLine("- Epsilon: ${stats.epsilon}")
                appendLine("- 学习�?${stats.learningRate}")
            }
        }
    }

    fun getIterationCount(): Int {
        return iterationCount
    }

    fun resetIterationCount() {
        iterationCount = 0
        AppLogger.d(TAG, "Reset iteration count to 0")
    }

    fun setRLHyperparameters(learningRate: Double, discountFactor: Double, epsilon: Double) {
        reinforcementLearningEngine?.setHyperparameters(learningRate, discountFactor, epsilon)
    }
}

data class EvolutionResult(
    val optimizedStrategy: String,
    val skillPath: String,
    val effectScore: Float,
    val iterationCount: Int,
    val convergence: Boolean,
    val policyUpdated: Boolean = false
)