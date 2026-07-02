package com.apex.agent.core.memory.apex.multiagent

import com.apex.agent.core.memory.apex.core.*

/**
 * 多 Agent 记忆模块。
 *
 * 多 Agent 协作模式下的记忆，包含：
 * - **对话记忆**：Agent 间的协作对话历史
 * - **学习记忆**：协作中积累的经验
 *
 * # 协作对话
 *
 * 记录 Agent 间的消息交换：
 * - 谁说了什么（fromAgent → toAgent）
 * - 协作模式（流水线/辩论/对抗/并行）
 * - 协作结果（成功/失败/部分成功）
 *
 * # 协作经验
 *
 * 记录多 Agent 协作的经验教训：
 * - 哪种协作模式对哪类任务最有效
 * - Agent 角色分配的最佳实践
 * - 冲突解决经验
 * - 通信效率优化
 */
class MultiAgentMemory {

    private val conversationStore = BaseMemoryStore()
    private val experienceStore = BaseMemoryStore()

    /**
     * 存储协作对话。
     *
     * @param fromAgentId 发送方 Agent ID
     * @param toAgentId 接收方 Agent ID（null = 广播）
     * @param content 消息内容
     * @param collaborationMode 协作模式
     * @param taskId 关联任务 ID
     */
    fun storeCollaboration(
        fromAgentId: String,
        toAgentId: String?,
        content: String,
        collaborationMode: String = "",
        taskId: String = ""
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.COLLABORATION,
            content = content,
            importance = 0.6f,
            source = fromAgentId,
            tags = setOf("multi_agent", "collaboration", collaborationMode).filter { it.isNotBlank() }.toSet(),
            metadata = mapOf(
                "fromAgent" to fromAgentId,
                "toAgent" to (toAgentId ?: "broadcast"),
                "collaborationMode" to collaborationMode,
                "taskId" to taskId
            )
        )
        conversationStore.store(entry)
        return entry.id
    }

    /**
     * 获取协作对话历史。
     */
    fun getCollaborationHistory(taskId: String? = null, limit: Int = 50): List<MemoryEntry> {
        val all = conversationStore.getByType(MemoryType.COLLABORATION)
        return if (taskId != null) {
            all.filter { it.metadata["taskId"] == taskId }.take(limit)
        } else {
            all.take(limit)
        }
    }

    /**
     * 按协作模式获取对话。
     */
    fun getByCollaborationMode(mode: String, limit: Int = 20): List<MemoryEntry> {
        return conversationStore.query(MemoryQuery(
            type = MemoryType.COLLABORATION,
            tags = setOf(mode),
            limit = limit
        ))
    }

    /**
     * 存储协作经验。
     *
     * @param content 经验内容
     * @param collaborationMode 协作模式
     * @param success 是否成功
     * @param agentCount 参与 Agent 数
     * @param importance 重要性
     */
    fun storeExperience(
        content: String,
        collaborationMode: String = "",
        success: Boolean = true,
        agentCount: Int = 0,
        importance: Float = 0.6f,
        tags: Set<String> = emptySet()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.EXPERIENCE,
            content = content,
            importance = importance,
            confidence = if (success) 0.85f else 0.65f,
            source = "multi_agent",
            tags = tags + setOf("multi_agent", "experience", collaborationMode, if (success) "success" else "failure")
                .filter { it.isNotBlank() }.toSet(),
            metadata = mapOf(
                "collaborationMode" to collaborationMode,
                "success" to success,
                "agentCount" to agentCount
            )
        )
        experienceStore.store(entry)
        return entry.id
    }

    /**
     * 搜索协作经验。
     */
    fun searchExperiences(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return experienceStore.search(keyword, limit)
    }

    /**
     * 获取某协作模式的经验。
     */
    fun getExperiencesByMode(mode: String, limit: Int = 20): List<MemoryEntry> {
        return experienceStore.query(MemoryQuery(
            type = MemoryType.EXPERIENCE,
            tags = setOf(mode),
            limit = limit
        ))
    }

    /**
     * 清空。
     */
    fun clear() {
        conversationStore.clear()
        experienceStore.clear()
    }

    fun getStats(): MemoryModuleStats = MemoryModuleStats(
        totalEntries = conversationStore.count() + experienceStore.count(),
        conversationCount = conversationStore.count(),
        experienceCount = experienceStore.count(),
        oldestTimestamp = conversationStore.getAll().minOfOrNull { it.timestamp } ?: 0,
        newestTimestamp = conversationStore.getAll().maxOfOrNull { it.timestamp } ?: 0
    )
}
