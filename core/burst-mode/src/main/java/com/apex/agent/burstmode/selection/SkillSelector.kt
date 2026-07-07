package com.apex.agent.burstmode.selection

import com.apex.agent.burstmode.api.SkillManager
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.BurstSkillManifest
import com.apex.agent.plugins.burst.base.IBurstSkill

/**
 * 技能选择策略。
 *
 * 决定对于一个给定任务，应该选择哪个技能执行。
 */
interface SkillSelectionStrategy {

    /**
     * 选择最合适的技能。
     *
     * @param task 待执行的任务
     * @param availableSkills 所有可用技能
     * @return 选中的技能，null 表示无匹配
     */
    fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill?

    /**
     * 策略名称。
     */
    val name: String
}

/**
 * 基于任务类型匹配的策略。
 *
 * 通过任务的 taskType 字段匹配技能的 skillId 或 manifest 中的能力。
 */
class TypeMatchingStrategy : SkillSelectionStrategy {

    override val name: String = "TypeMatching"

    override fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill? {
        // 1. 精确匹配 taskType 与 skillId
        val taskType = task.metadata["taskType"] ?: return availableSkills.firstOrNull()
        val exactMatch = availableSkills.find { it.manifest.skillId == taskType }
        if (exactMatch != null) return exactMatch

        // 2. 匹配 taskType 与 capabilities
        val capabilityMatch = availableSkills.find { taskType in it.manifest.capabilities }
        if (capabilityMatch != null) return capabilityMatch

        // 3. 匹配 taskType 与 tags
        val tagMatch = availableSkills.find { taskType in it.manifest.tags }
        if (tagMatch != null) return tagMatch

        return null
    }
}

/**
 * 基于关键词匹配的策略。
 *
 * 分析任务描述中的关键词，匹配技能的 description 和 tags。
 */
class KeywordMatchingStrategy : SkillSelectionStrategy {

    override val name: String = "KeywordMatching"

    override fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill? {
        val description = task.description.lowercase()
        if (description.isBlank()) return null

        var bestSkill: IBurstSkill? = null
        var bestScore = 0

        for (skill in availableSkills) {
            var score = 0
            // 检查 manifest 中的每个 tag 是否出现在描述中
            for (tag in skill.manifest.tags) {
                if (tag.lowercase() in description) {
                    score += 2
                }
            }
            // 检查 description 中的关键词
            val skillDesc = skill.manifest.description.lowercase()
            val skillWords = skillDesc.split(Regex("\\W+")).filter { it.length > 3 }
            for (word in skillWords) {
                if (word in description) {
                    score += 1
                }
            }
            // 优先级加分
            score += skill.manifest.priority / 10

            if (score > bestScore) {
                bestScore = score
                bestSkill = skill
            }
        }

        return bestSkill?.takeIf { bestScore > 0 }
    }
}

/**
 * 基于优先级的策略。
 *
 * 选择优先级最高的技能。
 */
class PriorityStrategy : SkillSelectionStrategy {

    override val name: String = "Priority"

    override fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill? {
        return availableSkills.maxByOrNull { it.manifest.priority }
    }
}

/**
 * 基于复杂度的策略。
 *
 * 根据任务复杂度选择不同等级的技能。
 * - EXTREME: 选择 capabilities 包含 "extreme" 的技能
 * - HIGH: 选择 capabilities 包含 "advanced" 的技能
 * - MEDIUM: 选择 capabilities 包含 "standard" 的技能
 * - LOW: 选择 capabilities 包含 "basic" 的技能
 */
class ComplexityBasedStrategy : SkillSelectionStrategy {

    override val name: String = "ComplexityBased"

    override fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill? {
        val complexity = task.complexity
        val targetCapability = when (complexity) {
            BurstTask.Complexity.EXTREME -> "extreme"
            BurstTask.Complexity.HIGH -> "advanced"
            BurstTask.Complexity.MEDIUM -> "standard"
            BurstTask.Complexity.LOW -> "basic"
        }

        // 先找匹配复杂度的
        val matched = availableSkills.filter { targetCapability in it.manifest.capabilities }
        if (matched.isNotEmpty()) {
            return matched.maxByOrNull { it.manifest.priority }
        }

        // 降级：选择优先级最高的
        return availableSkills.maxByOrNull { it.manifest.priority }
    }
}

/**
 * 组合策略。
 *
 * 按顺序尝试多个策略，返回第一个匹配的结果。
 */
class CompositeStrategy(
    private val strategies: List<SkillSelectionStrategy>
) : SkillSelectionStrategy {

    override val name: String = "Composite(${strategies.joinToString(",") { it.name }})"

    override fun select(task: BurstTask, availableSkills: List<IBurstSkill>): IBurstSkill? {
        for (strategy in strategies) {
            val selected = strategy.select(task, availableSkills)
            if (selected != null) return selected
        }
        return null
    }
}

/**
 * 技能选择器。
 *
 * 负责为任务选择最合适的技能。支持多种策略组合。
 *
 * # 使用示例
 *
 * ```
 * val selector = SkillSelector(skillManager)
 *     .withStrategy(ComplexityBasedStrategy())
 *     .withStrategy(KeywordMatchingStrategy())
 *     .withStrategy(PriorityStrategy())  // 兜底
 *
 * val skill = selector.selectSkill(task)
 * if (skill != null) {
 *     val result = skill.execute(task)
 * }
 * ```
 */
class SkillSelector(private val skillManager: SkillManager) {

    private val strategies = mutableListOf<SkillSelectionStrategy>()

    /**
     * 添加选择策略。
     * 策略按添加顺序尝试，前面的策略优先。
     */
    fun withStrategy(strategy: SkillSelectionStrategy): SkillSelector {
        strategies.add(strategy)
        return this
    }

    /**
     * 使用默认策略组合（复杂度 → 关键词 → 优先级）。
     */
    fun withDefaultStrategies(): SkillSelector {
        strategies.clear()
        strategies.add(TypeMatchingStrategy())
        strategies.add(ComplexityBasedStrategy())
        strategies.add(KeywordMatchingStrategy())
        strategies.add(PriorityStrategy())
        return this
    }

    /**
     * 选择技能。
     *
     * @param task 任务
     * @return 最合适的技能，null 表示无可用技能
     */
    fun selectSkill(task: BurstTask): IBurstSkill? {
        val available = skillManager.getAll()
        if (available.isEmpty()) return null

        // 如果没有配置策略，使用默认
        val effectiveStrategies = if (strategies.isEmpty()) {
            listOf(TypeMatchingStrategy(), PriorityStrategy())
        } else {
            strategies
        }

        for (strategy in effectiveStrategies) {
            val selected = strategy.select(task, available)
            if (selected != null) return selected
        }

        return null
    }

    /**
     * 选择多个候选技能（按策略得分排序）。
     *
     * @param task 任务
     * @param maxCount 最大返回数
     * @return 候选技能列表（按优先级降序）
     */
    fun selectCandidates(task: BurstTask, maxCount: Int = 3): List<IBurstSkill> {
        val available = skillManager.getAll()
        if (available.isEmpty()) return emptyList()

        // 简单实现：按优先级排序返回前 N 个
        return available
            .sortedByDescending { it.manifest.priority }
            .take(maxCount)
    }

    /**
     * 判断是否有技能能处理此任务。
     */
    fun canHandle(task: BurstTask): Boolean = selectSkill(task) != null
}
