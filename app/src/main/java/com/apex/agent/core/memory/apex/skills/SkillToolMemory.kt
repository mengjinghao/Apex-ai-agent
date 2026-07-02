package com.apex.agent.core.memory.apex.skills

import com.apex.agent.core.memory.apex.core.*

/**
 * 工具 Skills 记忆模块。
 *
 * 3 种独立的工具经验存储，因为不同模式使用不同工具集：
 * - **普通 Agent 工具经验**：单 Agent 使用的工具经验
 * - **多 Agent 工具经验**：多 Agent 协作时的工具经验
 * - **狂暴模式工具经验**：狂暴模式下的工具经验
 *
 * # 为什么分开？
 *
 * - 普通 Agent 用基础工具（read_file/cat/grep 等）
 * - 多 Agent 用协作工具（并行搜索/结果合并/消息传递）
 * - 狂暴模式用竞速工具（racing/parallel_execution/chain_execution）
 * 工具集不同，经验不能混用。
 */
class SkillToolMemory {

    /** 普通 Agent 工具经验。 */
    val normal: ToolExperienceStore = ToolExperienceStore("normal_agent")

    /** 多 Agent 工具经验。 */
    val multiAgent: ToolExperienceStore = ToolExperienceStore("multi_agent")

    /** 狂暴模式工具经验。 */
    val burst: ToolExperienceStore = ToolExperienceStore("burst_mode")

    fun clear() {
        normal.clear()
        multiAgent.clear()
        burst.clear()
    }

    fun getStats(): MemoryModuleStats {
        val total = normal.count() + multiAgent.count() + burst.count()
        return MemoryModuleStats(
            totalEntries = total,
            conversationCount = 0,
            experienceCount = total,
            oldestTimestamp = 0,
            newestTimestamp = System.currentTimeMillis()
        )
    }
}

/**
 * 工具经验存储。
 *
 * 每种模式有独立的工具经验存储。
 */
class ToolExperienceStore(private val modeName: String) {

    private val store = BaseMemoryStore()

    /**
     * 存储工具使用经验。
     *
     * @param toolName 工具名（如 read_file/grep/racing）
     * @param content 经验内容
     * @param success 是否成功
     * @param executionTimeMs 执行耗时
     * @param inputSize 输入大小
     * @param outputSize 输出大小
     * @param importance 重要性
     * @param tags 额外标签
     */
    fun storeToolExperience(
        toolName: String,
        content: String,
        success: Boolean = true,
        executionTimeMs: Long = 0,
        inputSize: Long = 0,
        outputSize: Long = 0,
        importance: Float = 0.5f,
        tags: Set<String> = emptySet()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.TOOL_USAGE,
            content = content,
            importance = importance,
            confidence = if (success) 0.85f else 0.6f,
            source = modeName,
            tags = tags + setOf(modeName, "tool", toolName, if (success) "success" else "failure"),
            metadata = mapOf(
                "toolName" to toolName,
                "success" to success,
                "executionTimeMs" to executionTimeMs,
                "inputSize" to inputSize,
                "outputSize" to outputSize
            )
        )
        store.store(entry)
        return entry.id
    }

    /**
     * 搜索工具经验。
     */
    fun searchExperiences(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return store.search(keyword, limit)
    }

    /**
     * 获取某工具的所有经验。
     */
    fun getByToolName(toolName: String, limit: Int = 20): List<MemoryEntry> {
        return store.query(MemoryQuery(
            type = MemoryType.TOOL_USAGE,
            tags = setOf(toolName),
            limit = limit
        ))
    }

    /**
     * 获取成功经验。
     */
    fun getSuccessExperiences(toolName: String? = null, limit: Int = 20): List<MemoryEntry> {
        val tags = if (toolName != null) setOf(toolName, "success") else setOf("success")
        return store.query(MemoryQuery(type = MemoryType.TOOL_USAGE, tags = tags, limit = limit))
    }

    /**
     * 获取失败经验。
     */
    fun getFailureExperiences(toolName: String? = null, limit: Int = 20): List<MemoryEntry> {
        val tags = if (toolName != null) setOf(toolName, "failure") else setOf("failure")
        return store.query(MemoryQuery(type = MemoryType.TOOL_USAGE, tags = tags, limit = limit))
    }

    /**
     * 获取工具性能统计。
     *
     * @param toolName 工具名
     * @return 统计（平均耗时/成功率/调用次数）
     */
    fun getToolStats(toolName: String): ToolStats {
        val entries = getByToolName(toolName, 1000)
        if (entries.isEmpty()) return ToolStats(toolName, 0, 0f, 0L)

        val successCount = entries.count { it.metadata["success"] == true }
        val times = entries.mapNotNull { it.metadata["executionTimeMs"] as? Long }.filter { it > 0 }

        return ToolStats(
            toolName = toolName,
            totalUses = entries.size,
            successRate = successCount.toFloat() / entries.size,
            avgExecutionTimeMs = if (times.isNotEmpty()) times.sum() / times.size else 0L
        )
    }

    /**
     * 获取所有工具名。
     */
    fun getAllToolNames(): Set<String> {
        return store.getAll()
            .mapNotNull { it.metadata["toolName"] as? String }
            .toSet()
    }

    fun count(): Int = store.count()

    fun clear() = store.clear()
}

/**
 * 工具统计。
 */
data class ToolStats(
    val toolName: String,
    val totalUses: Int,
    val successRate: Float,
    val avgExecutionTimeMs: Long
) {
    val failureRate: Float get() = 1f - successRate
}
