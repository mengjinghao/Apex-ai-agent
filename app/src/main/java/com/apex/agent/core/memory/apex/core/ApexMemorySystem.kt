package com.apex.agent.core.memory.apex.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Apex 记忆系统 — 5 大模块架构。
 *
 * 将记忆系统分为 5 个独立模块，各司其职：
 *
 * 1. **普通 Agent 记忆** (NormalAgentMemory) — 单 Agent 模式的记忆
 *    - 对话记忆：用户与 Agent 的对话历史
 *    - 学习记忆：任务执行中的重要经验和教训
 *
 * 2. **多 Agent 记忆** (MultiAgentMemory) — 多 Agent 协作模式的记忆
 *    - 对话记忆：Agent 间的协作对话
 *    - 学习记忆：协作中积累的经验
 *
 * 3. **狂暴模式记忆** (BurstModeMemory) — 狂暴模式的记忆
 *    - 对话记忆：狂暴模式下的任务对话
 *    - 学习记忆：推理策略/执行经验/GA 演化结果
 *
 * 4. **工具 Skills 记忆** (SkillToolMemory) — 3 种独立工具经验
 *    - 普通 Agent 工具经验
 *    - 多 Agent 工具经验
 *    - 狂暴模式工具经验
 *    （分开存储因为不同模式使用不同工具集）
 *
 * 5. **网页自动化记忆** (WebAutomationMemory) — 网页操作经验
 *    - 操作经验：网页操作步骤/表单填写/登录流程
 *    - 收获：爬取的数据/发现的模式
 *
 * # 与现有系统的关系
 *
 * 本系统与现有 UnifiedMemoryManager 并存：
 * - UnifiedMemoryManager 提供底层存储（分层/压缩/向量检索）
 * - ApexMemorySystem 提供上层语义分类（5 大模块）
 * - 通过 [MemoryStoreAdapter] 适配底层存储
 *
 * # 使用示例
 *
 * ```
 * val memory = ApexMemorySystem.create()
 *
 * // 普通 Agent 记忆
 * memory.normal.storeConversation("user", "分析这段代码")
 * memory.normal.storeExperience("发现空指针 bug", importance = 0.9f)
 *
 * // 多 Agent 记忆
 * memory.multiAgent.storeCollaboration("agent_1", "agent_2", "代码审查完成")
 * memory.multiAgent.storeExperience("多 Agent 辩论比单 Agent 更有效")
 *
 * // 狂暴模式记忆
 * memory.burst.storeTaskRecord("task_001", "CoT 推理成功", success = true)
 * memory.burst.storeStrategy("CoT+ToT 组合效果最佳")
 *
 * // 工具 Skills 记忆
 * memory.skills.normal.storeToolExperience("read_file", "大文件用流式读取更快")
 * memory.skills.multiAgent.storeToolExperience("parallel_search", "并行搜索提速 3 倍")
 * memory.skills.burst.storeToolExperience("racing", "read_file 竞速总是胜出")
 *
 * // 网页自动化记忆
 * memory.web.storeOperation("登录流程", listOf("输入用户名", "输入密码", "点击登录"))
 * memory.web.storeHarvest("商品数据", "发现价格模式：周末降价 10%")
 *
 * // 检索
 * val exp = memory.normal.searchExperiences("空指针")
 * val webOps = memory.web.getOperationsByDomain("taobao.com")
 * ```
 */
object ApexMemorySystem {

    private var _instance: ApexMemorySystemImpl? = null

    /**
     * 获取全局实例。
     */
    fun get(): ApexMemorySystemImpl {
        return _instance ?: synchronized(this) {
            _instance ?: ApexMemorySystemImpl().also { _instance = it }
        }
    }

    /**
     * 创建新实例（用于测试）。
     */
    fun create(): ApexMemorySystemImpl {
        return ApexMemorySystemImpl()
    }

    /**
     * 重置全局实例。
     */
    fun reset() {
        _instance?.clear()
        _instance = null
    }
}

/**
 * 记忆系统实现。
 */
class ApexMemorySystemImpl {

    /** 普通 Agent 记忆。 */
    val normal: NormalAgentMemory = NormalAgentMemory()

    /** 多 Agent 记忆。 */
    val multiAgent: MultiAgentMemory = MultiAgentMemory()

    /** 狂暴模式记忆。 */
    val burst: BurstModeMemory = BurstModeMemory()

    /** 工具 Skills 记忆。 */
    val skills: SkillToolMemory = SkillToolMemory()

    /** 网页自动化记忆。 */
    val web: WebAutomationMemory = WebAutomationMemory()

    /**
     * 清空所有记忆。
     */
    fun clear() {
        normal.clear()
        multiAgent.clear()
        burst.clear()
        skills.clear()
        web.clear()
    }

    /**
     * 获取全局统计。
     */
    fun getGlobalStats(): MemoryGlobalStats {
        return MemoryGlobalStats(
            normalStats = normal.getStats(),
            multiAgentStats = multiAgent.getStats(),
            burstStats = burst.getStats(),
            skillsStats = skills.getStats(),
            webStats = web.getStats()
        )
    }
}

/**
 * 全局记忆统计。
 */
data class MemoryGlobalStats(
    val normalStats: MemoryModuleStats,
    val multiAgentStats: MemoryModuleStats,
    val burstStats: MemoryModuleStats,
    val skillsStats: MemoryModuleStats,
    val webStats: MemoryModuleStats
) {
    val totalEntries: Int
        get() = normalStats.totalEntries + multiAgentStats.totalEntries +
                burstStats.totalEntries + skillsStats.totalEntries + webStats.totalEntries
}

/**
 * 模块统计。
 */
data class MemoryModuleStats(
    val totalEntries: Int,
    val conversationCount: Int,
    val experienceCount: Int,
    val oldestTimestamp: Long,
    val newestTimestamp: Long
)

// ===== 核心抽象 =====

/**
 * 记忆条目。
 *
 * 所有记忆模块的基础数据单元。
 *
 * @property id 唯一 ID
 * @property type 记忆类型
 * @property content 内容
 * @property importance 重要性 (0..1)
 * @property confidence 置信度 (0..1)
 * @property timestamp 时间戳
 * @property tags 标签
 * @property source 来源标识
 * @property metadata 附加元数据
 */
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoryType,
    val content: String,
    val importance: Float = 0.5f,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: Set<String> = emptySet(),
    val source: String = "",
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 记忆类型。
 */
enum class MemoryType(val displayName: String) {
    CONVERSATION("对话"),
    EXPERIENCE("经验"),
    LEARNING("学习"),
    STRATEGY("策略"),
    OPERATION("操作"),
    HARVEST("收获"),
    TOOL_USAGE("工具使用"),
    COLLABORATION("协作"),
    ERROR("错误"),
    INSIGHT("洞察")
}

/**
 * 记忆查询条件。
 */
data class MemoryQuery(
    val type: MemoryType? = null,
    val tags: Set<String> = emptySet(),
    val keyword: String? = null,
    val minImportance: Float = 0f,
    val minConfidence: Float = 0f,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val source: String? = null,
    val limit: Int = 100
)

/**
 * 基础记忆存储。
 *
 * 所有 5 大模块共享的基础存储能力。
 */
open class BaseMemoryStore {

    protected val entries = ConcurrentHashMap<String, MemoryEntry>()

    /**
     * 存储记忆。
     */
    fun store(entry: MemoryEntry): Boolean {
        entries[entry.id] = entry
        return true
    }

    /**
     * 获取单条记忆。
     */
    fun get(id: String): MemoryEntry? = entries[id]

    /**
     * 删除记忆。
     */
    fun remove(id: String): Boolean = entries.remove(id) != null

    /**
     * 查询记忆。
     */
    fun query(q: MemoryQuery): List<MemoryEntry> {
        return entries.values
            .filter { entry ->
                (q.type == null || entry.type == q.type) &&
                (q.tags.isEmpty() || entry.tags.intersect(q.tags).isNotEmpty()) &&
                (q.keyword == null || entry.content.contains(q.keyword, ignoreCase = true)) &&
                entry.importance >= q.minImportance &&
                entry.confidence >= q.minConfidence &&
                (q.startTime == null || entry.timestamp >= q.startTime) &&
                (q.endTime == null || entry.timestamp <= q.endTime) &&
                (q.source == null || entry.source == q.source)
            }
            .sortedByDescending { it.importance }
            .take(q.limit)
    }

    /**
     * 搜索记忆（关键词）。
     */
    fun search(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return query(MemoryQuery(keyword = keyword, limit = limit))
    }

    /**
     * 获取所有记忆。
     */
    fun getAll(): List<MemoryEntry> = entries.values.toList().sortedByDescending { it.timestamp }

    /**
     * 按类型获取。
     */
    fun getByType(type: MemoryType): List<MemoryEntry> {
        return entries.values.filter { it.type == type }.sortedByDescending { it.timestamp }
    }

    /**
     * 记忆数量。
     */
    fun count(): Int = entries.size

    /**
     * 清空。
     */
    fun clear() = entries.clear()

    /**
     * 获取统计。
     */
    fun getStats(conversationType: MemoryType, experienceType: MemoryType): MemoryModuleStats {
        val all = entries.values.toList()
        return MemoryModuleStats(
            totalEntries = all.size,
            conversationCount = all.count { it.type == conversationType },
            experienceCount = all.count { it.type == experienceType },
            oldestTimestamp = all.minOfOrNull { it.timestamp } ?: 0,
            newestTimestamp = all.maxOfOrNull { it.timestamp } ?: 0
        )
    }
}
