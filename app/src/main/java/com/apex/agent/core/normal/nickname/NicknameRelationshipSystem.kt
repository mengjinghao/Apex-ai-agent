package com.apex.agent.core.normal.nickname

import java.util.concurrent.ConcurrentHashMap

/**
 * F45: 用户昵称与关系记忆（Nickname & Relationship Memory）
 *
 * 记住用户的昵称、关系、共同记忆：
 * - 昵称管理（用户自定义/AI 建议昵称）
 * - 关系记忆（朋友/家人/同事等）
 * - 共同回忆（重要事件/对话片段）
 * - 称呼偏好（正式/亲切）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不建立用户关系
 * - 狂暴不关心用户
 * - 本功能让单 Agent **有专属感、有羁绊**
 */

data class UserNickname(
    val userId: String,
    val nickname: String,
    val origin: NicknameOrigin,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usageCount: Int = 0,
    val preferredFormality: FormalityLevel = FormalityLevel.FRIENDLY
)

enum class NicknameOrigin {
    USER_SET,           // 用户自定义
    AI_SUGGESTED,       // AI 建议
    DERIVED_FROM_NAME,  // 从真名推导
    INSIDE_JOKE,        // 内部梗
    RANDOM              // 随机
}

enum class FormalityLevel {
    FORMAL,      // 正式（X 先生/女士）
    POLITE,      // 礼貌（您）
    FRIENDLY,    // 友好（你）
    INTIMATE,    // 亲切（昵称）
    CASUAL       // 随意（兄弟/姐妹）
}

data class Relationship(
    val userId: String,
    val type: RelationshipType,
    val description: String,
    val establishedAt: Long,
    val milestones: List<RelationshipMilestone>
)

enum class RelationshipType {
    NEW_USER,         // 新用户
    ACQUAINTANCE,     // 熟人
    FRIEND,           // 朋友
    CLOSE_FRIEND,     // 密友
    CONFIDANT,        // 知己
    MENTOR,           // 师长
    STUDENT,          // 学生
    COLLEAGUE,        // 同事
    FAMILY,           // 家人
    PARTNER           // 伴侣
}

data class RelationshipMilestone(
    val id: String,
    val title: String,
    val description: String,
    val timestamp: Long,
    val type: MilestoneType,
    val memory: String? = null  // 关联的对话记忆
)

enum class MilestoneType {
    FIRST_MEETING,       // 初次见面
    FIRST_NAME_USAGE,    // 第一次称呼名字
    INSIDE_JOKE_FORMED,  // 形成内部梗
    TRUST_MILESTONE,     // 信任里程碑
    SHARED_SECRET,       // 共享秘密
    HELPED_WITH,         // 帮助过的事
    CELEBRATION          // 共同庆祝
}

data class SharedMemory(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val timestamp: Long,
    val emotion: String?,
    val category: MemoryCategory,
    val importance: Int  // 1-5
)

enum class MemoryCategory {
    FUNNY,        // 搞笑
    TOUCHING,     // 感动
    IMPORTANT,    // 重要
    PERSONAL,     // 个人
    GOAL,         // 目标
    FEAR,         // 担忧
    DREAM         // 梦想
}

class NicknameRelationshipSystem {

    private val nicknames = ConcurrentHashMap<String, UserNickname>()
    private val relationships = ConcurrentHashMap<String, Relationship>()
    private val memories = ConcurrentHashMap<String, MutableList<SharedMemory>>()

    fun setNickname(userId: String, nickname: String, origin: NicknameOrigin = NicknameOrigin.USER_SET): UserNickname {
        val existing = nicknames[userId]
        val n = UserNickname(
            userId = userId, nickname = nickname, origin = origin,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            usageCount = (existing?.usageCount ?: 0) + 1,
            preferredFormality = existing?.preferredFormality ?: FormalityLevel.FRIENDLY
        )
        nicknames[userId] = n
        return n
    }

    fun getNickname(userId: String): UserNickname? = nicknames[userId]

    fun suggestNicknames(realName: String? = null, interests: List<String> = emptyList()): List<String> {
        val suggestions = mutableListOf<String>()
        if (realName != null) {
            // 从真名推导
            if (realName.length >= 2) {
                suggestions.add(realName.take(1) + "小" + realName.substring(1, 2))
                suggestions.add("小" + realName.take(1))
                suggestions.add("阿" + realName.take(1))
            }
        }
        // 基于兴趣
        val interestNicknames = mapOf(
            "编程" to listOf("代码侠", "Bug 猎手", "极客"),
            "音乐" to listOf("音律使者", "旋律精灵"),
            "读书" to listOf("书虫", "墨客"),
            "游戏" to listOf("玩家", "电竞达人"),
            "运动" to listOf("运动健将", "活力派")
        )
        interests.forEach { interest ->
            interestNicknames[interest]?.let { suggestions.addAll(it) }
        }
        // 通用建议
        if (suggestions.isEmpty()) {
            suggestions.addAll(listOf("朋友", "小伙伴", "探索者", "思考者"))
        }
        return suggestions.distinct().take(5)
    }

    fun updateFormality(userId: String, level: FormalityLevel): UserNickname? {
        val n = nicknames[userId] ?: return null
        val updated = n.copy(preferredFormality = level)
        nicknames[userId] = updated
        return updated
    }

    fun setRelationship(userId: String, type: RelationshipType, description: String = ""): Relationship {
        val existing = relationships[userId]
        val r = Relationship(
            userId = userId, type = type, description = description,
            establishedAt = existing?.establishedAt ?: System.currentTimeMillis(),
            milestones = existing?.milestones ?: listOf(RelationshipMilestone(
                "m_${System.currentTimeMillis()}", "初次见面", "我们的第一次对话",
                System.currentTimeMillis(), MilestoneType.FIRST_MEETING
            ))
        )
        relationships[userId] = r
        return r
    }

    fun getRelationship(userId: String): Relationship? = relationships[userId]

    fun addMilestone(userId: String, title: String, description: String, type: MilestoneType, memory: String? = null): Relationship? {
        val r = relationships[userId] ?: return null
        val milestone = RelationshipMilestone(
            "m_${System.currentTimeMillis()}", title, description,
            System.currentTimeMillis(), type, memory
        )
        val updated = r.copy(milestones = r.milestones + milestone)
        relationships[userId] = updated
        return updated
    }

    fun recordMemory(userId: String, title: String, description: String, category: MemoryCategory, emotion: String? = null, importance: Int = 3): SharedMemory {
        val memory = SharedMemory(
            "mem_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            userId, title, description, System.currentTimeMillis(), emotion, category, importance
        )
        memories.computeIfAbsent(userId) { mutableListOf() }.add(memory)
        return memory
    }

    fun getMemories(userId: String, category: MemoryCategory? = null): List<SharedMemory> {
        val userMemories = memories[userId] ?: return emptyList()
        return if (category != null) userMemories.filter { it.category == category }.sortedByDescending { it.importance }
               else userMemories.sortedByDescending { it.timestamp }
    }

    fun generateAddress(userId: String): String {
        val nickname = nicknames[userId]
        val relationship = relationships[userId]

        return when {
            nickname == null -> when (relationship?.type) {
                RelationshipType.FAMILY -> "家人"
                RelationshipType.MENTOR -> "老师"
                RelationshipType.PARTNER -> "亲爱的"
                else -> "你"
            }
            nickname.preferredFormality == FormalityLevel.FORMAL -> "${nickname.nickname}先生/女士"
            nickname.preferredFormality == FormalityLevel.POLITE -> "您"
            else -> nickname.nickname
        }
    }

    fun generateRelationshipPrompt(userId: String): String {
        val nickname = nicknames[userId]
        val relationship = relationships[userId]
        val memories = memories[userId]

        if (nickname == null && relationship == null) return ""

        val sb = StringBuilder()
        sb.append("[关系记忆]")

        if (nickname != null) {
            sb.append(" 称呼: ${nickname.nickname} (${nickname.preferredFormality})")
        }

        if (relationship != null) {
            sb.append(" | 关系: ${relationship.type}")
            if (relationship.milestones.isNotEmpty()) {
                sb.append(" | 里程碑: ${relationship.milestones.size} 个")
                val lastMilestone = relationship.milestones.last()
                sb.append(" | 最近: ${lastMilestone.title}")
            }
        }

        if (memories != null && memories.isNotEmpty()) {
            sb.append(" | 共同记忆: ${memories.size} 条")
            val important = memories.filter { it.importance >= 4 }
            if (important.isNotEmpty()) {
                sb.append(" | 重要记忆: ${important.first().title}")
            }
        }

        return sb.toString()
    }

    fun forgetUser(userId: String) {
        nicknames.remove(userId)
        relationships.remove(userId)
        memories.remove(userId)
    }

    fun getUserProfile(userId: String): UserProfileSummary {
        return UserProfileSummary(
            nickname = nicknames[userId],
            relationship = relationships[userId],
            memoryCount = memories[userId]?.size ?: 0,
            milestoneCount = relationships[userId]?.milestones?.size ?: 0
        )
    }

    data class UserProfileSummary(
        val nickname: UserNickname?,
        val relationship: Relationship?,
        val memoryCount: Int,
        val milestoneCount: Int
    )
}
