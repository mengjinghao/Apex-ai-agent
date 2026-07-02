package com.apex.agent.kernel.burst.enhanced.tagger

import java.util.concurrent.ConcurrentHashMap

/**
 * B47: 执行标签系统
 *
 * 为执行打标签，便于过滤/搜索/分析：
 * - 自动标签（基于任务特征）
 * - 用户标签
 * - 系统标签
 * - 标签查询
 */
class ExecutionTagger {

    data class ExecutionTags(
        val executionId: String,
        val tags: Set<String>,
        val autoTags: Set<String>,
        val userTags: Set<String>,
        val systemTags: Set<String>,
        val taggedAt: Long = System.currentTimeMillis()
    )

    data class TagInfo(
        val tag: String,
        val count: Int,
        val lastUsed: Long,
        val category: TagCategory
    )

    enum class TagCategory { AUTO, USER, SYSTEM }

    private val executionTags = ConcurrentHashMap<String, ExecutionTags>()
    private val tagIndex = ConcurrentHashMap<String, MutableSet<String>>()  // tag -> [executionIds]
    private val tagStats = ConcurrentHashMap<String, TagInfo>()

    /**
     * 标记执行
     */
    fun tag(executionId: String, tags: Set<String>, autoTags: Set<String> = emptySet(), userTags: Set<String> = emptySet(), systemTags: Set<String> = emptySet()) {
        val all = tags + autoTags + userTags + systemTags
        val execTags = ExecutionTags(executionId, all, autoTags, userTags, systemTags)
        executionTags[executionId] = execTags

        // 更新索引
        for (tag in all) {
            tagIndex.computeIfAbsent(tag) { mutableSetOf() }.add(executionId)
            val current = tagStats[tag]
            tagStats[tag] = TagInfo(tag, (current?.count ?: 0) + 1, System.currentTimeMillis(),
                when {
                    tag in autoTags -> TagCategory.AUTO
                    tag in userTags -> TagCategory.USER
                    tag in systemTags -> TagCategory.SYSTEM
                    else -> TagCategory.AUTO
                })
        }
    }

    /**
     * 添加标签
     */
    fun addTag(executionId: String, tag: String, category: TagCategory = TagCategory.USER) {
        val current = executionTags[executionId] ?: ExecutionTags(executionId, emptySet(), emptySet(), emptySet(), emptySet())
        val updated = when (category) {
            TagCategory.AUTO -> current.copy(autoTags = current.autoTags + tag, tags = current.tags + tag)
            TagCategory.USER -> current.copy(userTags = current.userTags + tag, tags = current.tags + tag)
            TagCategory.SYSTEM -> current.copy(systemTags = current.systemTags + tag, tags = current.tags + tag)
        }
        executionTags[executionId] = updated
        tagIndex.computeIfAbsent(tag) { mutableSetOf() }.add(executionId)
    }

    /**
     * 移除标签
     */
    fun removeTag(executionId: String, tag: String) {
        val current = executionTags[executionId] ?: return
        val updated = current.copy(
            tags = current.tags - tag,
            autoTags = current.autoTags - tag,
            userTags = current.userTags - tag,
            systemTags = current.systemTags - tag
        )
        executionTags[executionId] = updated
        tagIndex[tag]?.remove(executionId)
    }

    /**
     * 获取执行的标签
     */
    fun getTags(executionId: String): Set<String> = executionTags[executionId]?.tags ?: emptySet()

    /**
     * 按标签查询
     */
    fun findByTag(tag: String): List<String> = tagIndex[tag]?.toList() ?: emptyList()

    /**
     * 按多标签查询（AND）
     */
    fun findByTags(tags: Set<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        var result = tagIndex[tags.first()]?.toSet() ?: emptySet()
        for (tag in tags.drop(1)) {
            result = result.intersect(tagIndex[tag] ?: emptySet())
            if (result.isEmpty()) break
        }
        return result.toList()
    }

    /**
     * 按标签查询（OR）
     */
    fun findByAnyTag(tags: Set<String>): List<String> {
        return tags.flatMap { tagIndex[it] ?: emptySet() }.distinct()
    }

    /**
     * 自动生成标签
     */
    fun autoGenerateTags(taskDescription: String, skillId: String, success: Boolean, durationMs: Long): Set<String> {
        val tags = mutableSetOf<String>()
        tags.add("skill:$skillId")
        tags.add(if (success) "success" else "failure")
        tags.add("duration:${when { durationMs < 1000 -> "fast"; durationMs < 10000 -> "normal"; else -> "slow" }}")
        if (taskDescription.contains("代码", true) || taskDescription.contains("code", true)) tags.add("domain:code")
        if (taskDescription.contains("翻译", true) || taskDescription.contains("translate", true)) tags.add("domain:translation")
        if (taskDescription.contains("分析", true) || taskDescription.contains("analyze", true)) tags.add("domain:analysis")
        if (taskDescription.contains("搜索", true) || taskDescription.contains("search", true)) tags.add("domain:search")
        if (taskDescription.contains("调试", true) || taskDescription.contains("debug", true)) tags.add("domain:debug")
        if (taskDescription.length > 1000) tags.add("long-input")
        if (taskDescription.length < 50) tags.add("short-input")
        return tags
    }

    /**
     * 获取热门标签
     */
    fun getPopularTags(limit: Int = 20): List<TagInfo> {
        return tagStats.values.sortedByDescending { it.count }.take(limit).toList()
    }

    /**
     * 获取标签统计
     */
    fun getStats(): TaggerStats {
        return TaggerStats(
            totalExecutions = executionTags.size,
            totalUniqueTags = tagStats.size,
            totalTagAssignments = tagStats.values.sumOf { it.count },
            tagsByCategory = tagStats.values.groupingBy { it.category }.eachCount()
        )
    }

    data class TaggerStats(
        val totalExecutions: Int,
        val totalUniqueTags: Int,
        val totalTagAssignments: Int,
        val tagsByCategory: Map<TagCategory, Int>
    )

    fun clear(executionId: String) {
        val tags = executionTags.remove(executionId) ?: return
        tags.tags.forEach { tag -> tagIndex[tag]?.remove(executionId) }
    }

    fun clearAll() {
        executionTags.clear()
        tagIndex.clear()
        tagStats.clear()
    }
}
