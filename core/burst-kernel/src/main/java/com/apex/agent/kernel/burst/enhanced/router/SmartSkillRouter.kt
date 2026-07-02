package com.apex.agent.kernel.burst.enhanced.router

import java.util.concurrent.ConcurrentHashMap

/**
 * B18: 智能技能路由器
 *
 * 增强现有 SkillSelector：
 * - 基于任务特征的智能路由
 * - 历史成功率学习
 * - 多策略融合（规则+ML+历史）
 * - A/B 测试支持
 */
class SmartSkillRouter {

    /**
     * 任务特征
     */
    data class TaskFeatures(
        val taskType: String,
        val complexity: Int,        // 1-5
        val inputLength: Int,
        val hasCode: Boolean,
        val hasReasoning: Boolean,
        val hasMultiStep: Boolean,
        val requiresCreativity: Boolean,
        val requiresAccuracy: Boolean,
        val deadlineMs: Long?
    )

    /**
     * 路由决策
     */
    data class RoutingDecision(
        val primarySkill: String,
        val fallbackSkills: List<String>,
        val confidence: Float,
        val reason: String,
        val estimatedDurationMs: Long,
        val estimatedSuccessRate: Float
    )

    /**
     * 技能历史统计
     */
    data class SkillHistory(
        val skillId: String,
        var totalExecutions: Int = 0,
        var successCount: Int = 0,
        var totalDurationMs: Long = 0,
        var lastUsed: Long = 0,
        val taskTypeStats: MutableMap<String, TypeStats> = mutableMapOf()
    )

    data class TypeStats(
        var count: Int = 0,
        var successCount: Int = 0,
        var totalDurationMs: Long = 0
    ) {
        val successRate: Float get() = if (count > 0) successCount.toFloat() / count else 0f
        val avgDuration: Long get() = if (count > 0) totalDurationMs / count else 0L
    }

    /**
     * 路由策略
     */
    enum class RoutingStrategy {
        RULE_BASED,      // 规则路由
        HISTORY_BASED,   // 历史路由
        HYBRID,          // 混合路由
        A_B_TEST         // A/B 测试
    }

    private val skillHistory = ConcurrentHashMap<String, SkillHistory>()
    private var strategy = RoutingStrategy.HYBRID
    private val abTestAssignments = ConcurrentHashMap<String, String>()  // taskId -> skillId

    // 规则表：任务类型 → 推荐 Skill
    private val ruleTable = mapOf(
        "code_analysis" to listOf("code_quality_analyzer", "reasoning.react"),
        "code_generation" to listOf("reasoning.tree-of-thoughts", "reasoning.react"),
        "debugging" to listOf("reasoning.multi-hop", "recovery", "self_correction"),
        "translation" to listOf("reasoning.chain-of-thought", "reasoning.self-consistency"),
        "summarization" to listOf("reasoning.chain-of-thought", "infinite_context"),
        "creative_writing" to listOf("reasoning.tree-of-thoughts", "red_blue_adversarial"),
        "data_analysis" to listOf("reasoning.multi-hop", "rag_pipeline"),
        "research" to listOf("rag_pipeline", "reasoning.multi-hop", "knowledge_graph"),
        "automation" to listOf("task_scheduler", "brute_force_ui", "tool_fusion"),
        "reasoning" to listOf("reasoning.react", "reasoning.chain-of-thought"),
        "planning" to listOf("thinking_agent", "task_graph"),
        "execution" to listOf("berserk_execution", "adaptive_execution"),
        "verification" to listOf("self_correction", "reasoning.self-consistency"),
        "analysis" to listOf("reasoning.multi-hop", "reasoning.tree-of-thoughts"),
        "search" to listOf("file_search", "rag_pipeline")
    )

    /**
     * 路由决策
     */
    fun route(taskId: String, features: TaskFeatures): RoutingDecision {
        return when (strategy) {
            RoutingStrategy.RULE_BASED -> routeByRules(features)
            RoutingStrategy.HISTORY_BASED -> routeByHistory(features)
            RoutingStrategy.HYBRID -> routeHybrid(features)
            RoutingStrategy.A_B_TEST -> routeABTest(taskId, features)
        }
    }

    /**
     * 规则路由
     */
    private fun routeByRules(features: TaskFeatures): RoutingDecision {
        val candidates = ruleTable[features.taskType]
            ?: ruleTable["reasoning"]!!

        // 根据复杂度调整
        val adjusted = when {
            features.complexity >= 4 -> candidates + listOf("extreme_reasoning", "red_blue_adversarial")
            features.complexity <= 2 && candidates.size > 1 -> candidates.take(1)
            else -> candidates
        }

        val estimatedDuration = adjusted.size * 5000L * features.complexity
        val estimatedSuccess = when (features.complexity) {
            1 -> 0.95f
            2 -> 0.90f
            3 -> 0.80f
            4 -> 0.65f
            else -> 0.50f
        }

        return RoutingDecision(
            primarySkill = adjusted.first(),
            fallbackSkills = adjusted.drop(1),
            confidence = 0.6f,
            reason = "规则路由: ${features.taskType} → ${adjusted.first()}",
            estimatedDurationMs = estimatedDuration,
            estimatedSuccessRate = estimatedSuccess
        )
    }

    /**
     * 历史路由
     */
    private fun routeByHistory(features: TaskFeatures): RoutingDecision {
        // 找该任务类型下成功率最高的 Skill
        val ranked = skillHistory.values
            .mapNotNull { history ->
                val typeStats = history.taskTypeStats[features.taskType]
                if (typeStats != null && typeStats.count >= 3) {
                    history.skillId to typeStats.successRate to typeStats.avgDuration
                } else null
            }
            .sortedByDescending { it.first.second }
            .take(3)

        if (ranked.isEmpty()) {
            return routeByRules(features).copy(reason = "历史不足，降级到规则路由")
        }

        val primary = ranked.first()
        return RoutingDecision(
            primarySkill = primary.first.first,
            fallbackSkills = ranked.drop(1).map { it.first.first },
            confidence = primary.first.second,
            reason = "历史路由: ${features.taskType} → ${primary.first.first} (成功率=${primary.first.second})",
            estimatedDurationMs = primary.second,
            estimatedSuccessRate = primary.first.second
        )
    }

    /**
     * 混合路由
     */
    private fun routeHybrid(features: TaskFeatures): RoutingDecision {
        val ruleResult = routeByRules(features)
        val historyResult = routeByHistory(features)

        // 如果历史数据充分且更优，用历史
        return if (historyResult.confidence > ruleResult.confidence && historyResult.confidence > 0.7f) {
            historyResult.copy(reason = "混合路由(历史优先): ${historyResult.reason}")
        } else {
            ruleResult.copy(reason = "混合路由(规则优先): ${ruleResult.reason}")
        }
    }

    /**
     * A/B 测试路由
     */
    private fun routeABTest(taskId: String, features: TaskFeatures): RoutingDecision {
        // 已分配的用已分配的
        abTestAssignments[taskId]?.let { assigned ->
            val history = skillHistory[assigned]
            return RoutingDecision(
                primarySkill = assigned,
                fallbackSkills = emptyList(),
                confidence = 0.5f,
                reason = "A/B 测试(已分配): $assigned",
                estimatedDurationMs = history?.totalDurationMs?.div(history.totalExecutions.coerceAtLeast(1)) ?: 5000L,
                estimatedSuccessRate = history?.let { it.successCount.toFloat() / it.totalExecutions.coerceAtLeast(1) } ?: 0.7f
            )
        }

        // 随机分配
        val candidates = ruleTable[features.taskType] ?: ruleTable["reasoning"]!!
        val chosen = candidates.random()
        abTestAssignments[taskId] = chosen

        return RoutingDecision(
            primarySkill = chosen,
            fallbackSkills = candidates - chosen,
            confidence = 0.5f,
            reason = "A/B 测试(新分配): $chosen",
            estimatedDurationMs = 5000L,
            estimatedSuccessRate = 0.7f
        )
    }

    /**
     * 记录执行结果
     */
    fun recordExecution(skillId: String, taskType: String, success: Boolean, durationMs: Long) {
        val history = skillHistory.computeIfAbsent(skillId) { SkillHistory(it) }
        history.totalExecutions++
        if (success) history.successCount++
        history.totalDurationMs += durationMs
        history.lastUsed = System.currentTimeMillis()

        val typeStats = history.taskTypeStats.computeIfAbsent(taskType) { TypeStats() }
        typeStats.count++
        if (success) typeStats.successCount++
        typeStats.totalDurationMs += durationMs
    }

    /**
     * 设置策略
     */
    fun setStrategy(newStrategy: RoutingStrategy) {
        strategy = newStrategy
    }

    /**
     * 获取所有技能统计
     */
    fun getAllStats(): Map<String, SkillHistory> = skillHistory.toMap()

    /**
     * 获取最佳技能（按任务类型）
     */
    fun getBestSkillForType(taskType: String): String? {
        return skillHistory.values
            .mapNotNull { h ->
                val stats = h.taskTypeStats[taskType]
                if (stats != null && stats.count >= 3) h.skillId to stats.successRate else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * 生成路由报告
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 技能路由器 ═══")
        sb.appendLine("策略: $strategy")
        sb.appendLine("已记录技能: ${skillHistory.size}")
        sb.appendLine("A/B 测试分配: ${abTestAssignments.size}")
        sb.appendLine()
        sb.appendLine("技能排名:")
        skillHistory.values
            .sortedByDescending { it.successCount.toFloat() / it.totalExecutions.coerceAtLeast(1) }
            .take(10)
            .forEach { h ->
                val rate = h.successCount.toFloat() / h.totalExecutions.coerceAtLeast(1)
                val avgDur = h.totalDurationMs / h.totalExecutions.coerceAtLeast(1)
                sb.appendLine("  ${h.skillId}: 成功率=${(rate * 100).toInt()}% 调用=${h.totalExecutions} 平均=${avgDur}ms")
            }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
