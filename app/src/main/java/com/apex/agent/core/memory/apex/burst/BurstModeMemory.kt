package com.apex.agent.core.memory.apex.burst

import com.apex.agent.core.memory.apex.core.*

/**
 * 狂暴模式记忆模块。
 *
 * 狂暴模式下的记忆，包含：
 * - **对话记忆**：狂暴模式下的任务对话记录
 * - **学习记忆**：推理策略经验/执行经验/GA 演化结果
 *
 * # 任务对话
 *
 * 记录狂暴模式执行的任务：
 * - 任务描述和目标
 * - 使用的推理策略（CoT/ToT/ReAct 等）
 * - 执行步骤和中间结果
 * - 最终结果和耗时
 *
 * # 策略经验
 *
 * 记录推理策略的效果：
 * - 哪种策略对哪类问题最有效
 * - 策略组合的最佳搭配
 * - GA 演化的最优参数
 * - 执行失败的原因分析
 */
class BurstModeMemory {

    private val conversationStore = BaseMemoryStore()
    private val experienceStore = BaseMemoryStore()
    private val strategyStore = BaseMemoryStore()

    /**
     * 存储任务记录。
     *
     * @param taskId 任务 ID
     * @param description 任务描述
     * @param strategies 使用的策略
     * @param success 是否成功
     * @param executionTimeMs 执行耗时
     * @param result 结果摘要
     */
    fun storeTaskRecord(
        taskId: String,
        description: String,
        strategies: List<String>,
        success: Boolean,
        executionTimeMs: Long,
        result: String = ""
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.CONVERSATION,
            content = "Task: $description | Result: $result",
            importance = if (success) 0.8f else 0.9f,
            confidence = if (success) 0.9f else 0.7f,
            source = "burst_mode",
            tags = setOf("burst", "task", if (success) "success" else "failure") + strategies.map { it.lowercase() }.toSet(),
            metadata = mapOf(
                "taskId" to taskId,
                "strategies" to strategies,
                "success" to success,
                "executionTimeMs" to executionTimeMs
            )
        )
        conversationStore.store(entry)
        return entry.id
    }

    /**
     * 获取任务记录。
     */
    fun getTaskRecords(limit: Int = 20): List<MemoryEntry> {
        return conversationStore.getByType(MemoryType.CONVERSATION).take(limit)
    }

    /**
     * 按策略搜索任务。
     */
    fun getTasksByStrategy(strategy: String, limit: Int = 20): List<MemoryEntry> {
        return conversationStore.query(MemoryQuery(
            type = MemoryType.CONVERSATION,
            tags = setOf(strategy.lowercase()),
            limit = limit
        ))
    }

    /**
     * 获取成功的任务。
     */
    fun getSuccessfulTasks(limit: Int = 20): List<MemoryEntry> {
        return conversationStore.query(MemoryQuery(
            type = MemoryType.CONVERSATION,
            tags = setOf("success"),
            limit = limit
        ))
    }

    /**
     * 存储策略经验。
     *
     * @param strategy 策略名
     * @param content 经验内容
     * @param successRate 成功率 (0..1)
     * @param avgTimeMs 平均耗时
     * @param taskType 适用任务类型
     */
    fun storeStrategy(
        strategy: String,
        content: String,
        successRate: Float = 0f,
        avgTimeMs: Long = 0,
        taskType: String = ""
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.STRATEGY,
            content = content,
            importance = 0.8f,
            confidence = successRate.coerceIn(0f, 1f),
            source = "burst_mode",
            tags = setOf("burst", "strategy", strategy.lowercase()),
            metadata = mapOf(
                "strategy" to strategy,
                "successRate" to successRate,
                "avgTimeMs" to avgTimeMs,
                "taskType" to taskType
            )
        )
        strategyStore.store(entry)
        return entry.id
    }

    /**
     * 获取策略经验。
     */
    fun getStrategies(limit: Int = 20): List<MemoryEntry> {
        return strategyStore.getByType(MemoryType.STRATEGY).take(limit)
    }

    /**
     * 按策略名搜索。
     */
    fun searchStrategies(strategy: String, limit: Int = 10): List<MemoryEntry> {
        return strategyStore.query(MemoryQuery(
            type = MemoryType.STRATEGY,
            tags = setOf(strategy.lowercase()),
            limit = limit
        ))
    }

    /**
     * 存储执行经验。
     */
    fun storeExperience(
        content: String,
        importance: Float = 0.7f,
        success: Boolean = true,
        tags: Set<String> = emptySet()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.EXPERIENCE,
            content = content,
            importance = importance,
            source = "burst_mode",
            tags = tags + setOf("burst", "experience", if (success) "success" else "failure"),
            metadata = mapOf("success" to success)
        )
        experienceStore.store(entry)
        return entry.id
    }

    /**
     * 搜索经验。
     */
    fun searchExperiences(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return experienceStore.search(keyword, limit)
    }

    /**
     * 清空。
     */
    fun clear() {
        conversationStore.clear()
        experienceStore.clear()
        strategyStore.clear()
    }

    fun getStats(): MemoryModuleStats = MemoryModuleStats(
        totalEntries = conversationStore.count() + experienceStore.count() + strategyStore.count(),
        conversationCount = conversationStore.count(),
        experienceCount = experienceStore.count() + strategyStore.count(),
        oldestTimestamp = conversationStore.getAll().minOfOrNull { it.timestamp } ?: 0,
        newestTimestamp = conversationStore.getAll().maxOfOrNull { it.timestamp } ?: 0
    )
}
