package com.apex.agent.kernel.burst.enhanced.fallback

import java.util.concurrent.ConcurrentHashMap

/**
 * B45: 自动 Fallback 链构建器
 *
 * 根据技能历史自动构建 fallback 链：
 * - 基于成功率的 fallback 排序
 * - 基于任务类型的 fallback 匹配
 * - 动态调整 fallback 顺序
 * - Fallback 执行追踪
 */
class FallbackChainBuilder {

    data class FallbackEntry(
        val skillId: String,
        val priority: Int,
        val condition: ((String) -> Boolean)? = null,
        val reason: String
    )

    data class FallbackChain(
        val taskId: String,
        val primarySkill: String,
        val fallbacks: List<FallbackEntry>,
        val maxAttempts: Int
    )

    data class FallbackExecution(
        val chain: FallbackChain,
        val attempts: List<FallbackAttempt>,
        val finalSuccess: Boolean,
        val winningSkill: String?,
        val totalDurationMs: Long
    )

    data class FallbackAttempt(
        val skillId: String,
        val attempt: Int,
        val success: Boolean,
        val durationMs: Long,
        val error: String?
    )

    data class SkillReliability(
        val skillId: String,
        val successRate: Float,
        val avgDurationMs: Long,
        val totalExecutions: Int,
        val lastFailure: Long?,
        val failureTypes: Map<String, Int>
    )

    private val skillStats = ConcurrentHashMap<String, SkillReliability>()
    private val taskTypeFallbacks = ConcurrentHashMap<String, MutableList<String>>()
    private val executionHistory = mutableListOf<FallbackExecution>()

    init {
        // 预置任务类型 → fallback 映射
        taskTypeFallbacks["reasoning"] = mutableListOf("reasoning.react", "reasoning.chain-of-thought", "reasoning.self-consistency", "reasoning.tree-of-thoughts")
        taskTypeFallbacks["execution"] = mutableListOf("berserk_execution", "adaptive_execution", "recovery", "recovery_chain")
        taskTypeFallbacks["search"] = mutableListOf("file_search", "rag_pipeline", "knowledge_graph")
        taskTypeFallbacks["analysis"] = mutableListOf("reasoning.multi-hop", "code_quality_analyzer", "reasoning.react")
        taskTypeFallbacks["verification"] = mutableListOf("self_correction", "reasoning.self-consistency", "red_blue_adversarial")
    }

    /**
     * 构建 fallback 链
     */
    fun buildChain(taskId: String, primarySkill: String, taskType: String? = null): FallbackChain {
        val candidates = mutableListOf<FallbackEntry>()

        // 1. 从任务类型映射获取
        if (taskType != null) {
            val typeFallbacks = taskTypeFallbacks[taskType] ?: emptyList()
            typeFallbacks.filter { it != primarySkill }.forEachIndexed { index, skillId ->
                candidates.add(FallbackEntry(skillId, index + 1, null, "任务类型 fallback"))
            }
        }

        // 2. 按可靠性排序
        candidates.sortByDescending { skillStats[it.skillId]?.successRate ?: 0.5f }

        // 3. 添加通用 fallback
        if (candidates.none { it.skillId == "recovery" }) {
            candidates.add(FallbackEntry("recovery", 100, null, "通用恢复"))
        }
        if (candidates.none { it.skillId == "recovery_chain" }) {
            candidates.add(FallbackEntry("recovery_chain", 101, null, "恢复链"))
        }

        return FallbackChain(
            taskId = taskId,
            primarySkill = primarySkill,
            fallbacks = candidates.take(5),
            maxAttempts = candidates.size + 1
        )
    }

    /**
     * 执行 fallback 链
     */
    suspend fun execute(
        chain: FallbackChain,
        input: String,
        executor: suspend (String, String) -> Result<String>
    ): FallbackExecution {
        val start = System.currentTimeMillis()
        val attempts = mutableListOf<FallbackAttempt>()
        val allSkills = listOf(chain.primarySkill) + chain.fallbacks.map { it.skillId }

        var winningSkill: String? = null
        var finalSuccess = false

        for ((index, skillId) in allSkills.withIndex()) {
            val attemptStart = System.currentTimeMillis()
            val result = try { executor(skillId, input) } catch (e: Exception) { Result.failure(e) }
            val duration = System.currentTimeMillis() - attemptStart

            val attempt = FallbackAttempt(skillId, index + 1, result.isSuccess, duration, result.exceptionOrNull()?.message)
            attempts.add(attempt)

            recordSkillExecution(skillId, result.isSuccess, duration, result.exceptionOrNull())

            if (result.isSuccess) {
                winningSkill = skillId
                finalSuccess = true
                break
            }

            // 检查条件
            val fallbackEntry = chain.fallbacks.getOrNull(index - 1)
            if (fallbackEntry?.condition != null && !fallbackEntry.condition.invoke(input)) {
                continue  // 跳过不满足条件的
            }
        }

        val execution = FallbackExecution(chain, attempts, finalSuccess, winningSkill, System.currentTimeMillis() - start)
        executionHistory.add(execution)
        while (executionHistory.size > 200) executionHistory.removeAt(0)

        return execution
    }

    /**
     * 记录技能执行
     */
    private fun recordSkillExecution(skillId: String, success: Boolean, durationMs: Long, error: Throwable?) {
        val current = skillStats[skillId]
        val newStats = if (current == null) {
            SkillReliability(skillId, if (success) 1f else 0f, durationMs, 1,
                if (!success) System.currentTimeMillis() else null,
                if (error != null) mapOf(error::class.simpleName ?: "Unknown" to 1) else emptyMap())
        } else {
            val newTotal = current.totalExecutions + 1
            val newSuccessCount = (current.successRate * current.totalExecutions + if (success) 1 else 0)
            SkillReliability(
                skillId = skillId,
                successRate = newSuccessCount / newTotal,
                avgDurationMs = (current.avgDurationMs * current.totalExecutions + durationMs) / newTotal,
                totalExecutions = newTotal,
                lastFailure = if (!success) System.currentTimeMillis() else current.lastFailure,
                failureTypes = if (error != null) {
                    val errorType = error::class.simpleName ?: "Unknown"
                    current.failureTypes + (errorType to (current.failureTypes[errorType] ?: 0) + 1)
                } else current.failureTypes
            )
        }
        skillStats[skillId] = newStats
    }

    /**
     * 注册自定义 fallback 映射
     */
    fun registerTypeFallbacks(taskType: String, skills: List<String>) {
        taskTypeFallbacks[taskType] = skills.toMutableList()
    }

    fun getSkillReliability(skillId: String): SkillReliability? = skillStats[skillId]
    fun getAllReliability(): List<SkillReliability> = skillStats.values.sortedByDescending { it.successRate }.toList()
    fun getExecutionHistory(): List<FallbackExecution> = executionHistory.toList()

    fun getStats(): FallbackStats {
        return FallbackStats(
            totalChains = executionHistory.size,
            successRate = if (executionHistory.isNotEmpty())
                executionHistory.count { it.finalSuccess }.toFloat() / executionHistory.size else 0f,
            avgAttempts = if (executionHistory.isNotEmpty())
                executionHistory.map { it.attempts.size }.average().toFloat() else 0f,
            trackedSkills = skillStats.size
        )
    }

    data class FallbackStats(
        val totalChains: Int,
        val successRate: Float,
        val avgAttempts: Float,
        val trackedSkills: Int
    )
}
