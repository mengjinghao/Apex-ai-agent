package com.apex.agent.core.memory.apex.normal

import com.apex.agent.core.memory.apex.core.*

/**
 * 普通 Agent 记忆模块。
 *
 * 单 Agent 模式下的记忆，包含：
 * - **对话记忆**：用户与 Agent 的对话历史
 * - **学习记忆**：任务执行中的重要经验和教训
 *
 * # 对话记忆
 *
 * 记录用户与 Agent 的每轮对话，支持：
 * - 按时间/关键词/标签检索
 * - 对话摘要（长对话自动压缩）
 * - 上下文窗口管理（保留最近 N 轮）
 *
 * # 学习记忆
 *
 * 记录任务执行中积累的经验：
 * - 成功经验（什么方法有效）
 * - 失败教训（什么方法无效/出错）
 * - 最佳实践（推荐的执行方式）
 * - 性能数据（执行时间/资源消耗）
 */
class NormalAgentMemory {

    /** 对话记忆存储。 */
    private val conversationStore = BaseMemoryStore()

    /** 学习/经验记忆存储。 */
    private val experienceStore = BaseMemoryStore()

    /** 最大对话条目数。 */
    private val maxConversations = 1000

    /** 最大经验条目数。 */
    private val maxExperiences = 500

    // ===== 对话记忆 =====

    /**
     * 存储对话消息。
     *
     * @param role 角色（user/assistant/system）
     * @param content 消息内容
     * @param tags 标签
     * @param metadata 附加信息
     */
    fun storeConversation(
        role: String,
        content: String,
        tags: Set<String> = emptySet(),
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.CONVERSATION,
            content = content,
            importance = if (role == "user") 0.7f else 0.5f,
            source = role,
            tags = tags + setOf("normal_agent", "conversation"),
            metadata = metadata + mapOf("role" to role)
        )
        conversationStore.store(entry)
        trimConversations()
        return entry.id
    }

    /**
     * 获取最近对话。
     *
     * @param limit 条数
     */
    fun getRecentConversations(limit: Int = 20): List<MemoryEntry> {
        return conversationStore.getByType(MemoryType.CONVERSATION).take(limit)
    }

    /**
     * 搜索对话。
     */
    fun searchConversations(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return conversationStore.search(keyword, limit).filter { it.type == MemoryType.CONVERSATION }
    }

    /**
     * 获取对话条数。
     */
    fun getConversationCount(): Int = conversationStore.count()

    // ===== 学习/经验记忆 =====

    /**
     * 存储经验。
     *
     * @param content 经验内容
     * @param importance 重要性 (0..1)
     * @param tags 标签
     * @param success 是否为成功经验
     * @param taskType 任务类型
     * @param metadata 附加信息
     */
    fun storeExperience(
        content: String,
        importance: Float = 0.5f,
        tags: Set<String> = emptySet(),
        success: Boolean = true,
        taskType: String = "",
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.EXPERIENCE,
            content = content,
            importance = importance,
            confidence = if (success) 0.9f else 0.7f,
            source = "normal_agent",
            tags = tags + setOf("normal_agent", "experience", if (success) "success" else "failure"),
            metadata = metadata + mapOf(
                "success" to success,
                "taskType" to taskType
            )
        )
        experienceStore.store(entry)
        trimExperiences()
        return entry.id
    }

    /**
     * 搜索经验。
     */
    fun searchExperiences(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return experienceStore.search(keyword, limit)
    }

    /**
     * 获取成功经验。
     */
    fun getSuccessExperiences(limit: Int = 20): List<MemoryEntry> {
        return experienceStore.query(MemoryQuery(
            type = MemoryType.EXPERIENCE,
            tags = setOf("success"),
            limit = limit
        ))
    }

    /**
     * 获取失败教训。
     */
    fun getFailureExperiences(limit: Int = 20): List<MemoryEntry> {
        return experienceStore.query(MemoryQuery(
            type = MemoryType.EXPERIENCE,
            tags = setOf("failure"),
            limit = limit
        ))
    }

    /**
     * 按任务类型获取经验。
     */
    fun getExperiencesByTaskType(taskType: String, limit: Int = 20): List<MemoryEntry> {
        return experienceStore.getAll()
            .filter { it.metadata["taskType"] == taskType }
            .take(limit)
    }

    /**
     * 获取最重要的经验。
     */
    fun getTopExperiences(limit: Int = 10): List<MemoryEntry> {
        return experienceStore.query(MemoryQuery(
            type = MemoryType.EXPERIENCE,
            limit = limit
        ))
    }

    /**
     * 获取经验条数。
     */
    fun getExperienceCount(): Int = experienceStore.count()

    // ===== 通用操作 =====

    /**
     * 清空所有记忆。
     */
    fun clear() {
        conversationStore.clear()
        experienceStore.clear()
    }

    /**
     * 获取统计。
     */
    fun getStats(): MemoryModuleStats {
        return MemoryModuleStats(
            totalEntries = conversationStore.count() + experienceStore.count(),
            conversationCount = conversationStore.count(),
            experienceCount = experienceStore.count(),
            oldestTimestamp = (conversationStore.getAll().minOfOrNull { it.timestamp } ?: 0L)
                .coerceAtMost(experienceStore.getAll().minOfOrNull { it.timestamp } ?: Long.MAX_VALUE),
            newestTimestamp = (conversationStore.getAll().maxOfOrNull { it.timestamp } ?: 0L)
                .coerceAtLeast(experienceStore.getAll().maxOfOrNull { it.timestamp } ?: 0L)
        )
    }

    private fun trimConversations() {
        val all = conversationStore.getByType(MemoryType.CONVERSATION)
        if (all.size > maxConversations) {
            // 删除最旧的
            all.takeLast(all.size - maxConversations).forEach { conversationStore.remove(it.id) }
        }
    }

    private fun trimExperiences() {
        val all = experienceStore.getAll()
        if (all.size > maxExperiences) {
            // 按重要性排序，删除最不重要的
            all.sortedBy { it.importance }
                .take(all.size - maxExperiences)
                .forEach { experienceStore.remove(it.id) }
        }
    }
}
