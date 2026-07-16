package com.apex.agent.core.normal.achievement

import java.util.concurrent.ConcurrentHashMap

/**
 * F35: 对话成就系统（Achievement System）
 *
 * 游戏化对话体验：
 * - 徽章（Badge）：达成特定里程碑
 * - 等级（Level）：基于经验值升级
 * - 连击（Streak）：连续对话/工具调用/任务完成
 * - 挑战（Challenge）：限时/条件任务
 * - 排行榜（Leaderboard）：与其他用户对比（占位）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不游戏化用户
 * - 狂暴不关心用户成长
 * - 本功能让**单 Agent 使用有乐趣、有目标**
 */

/**
 * 成就类型
 */
enum class AchievementType {
    BADGE,       // 徽章
    LEVEL,       // 等级
    STREAK,      // 连击
    CHALLENGE,   // 挑战
    MILESTONE    // 里程碑
}

/**
 * 徽章定义
 */
data class Badge(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val icon: String,
    val rarity: BadgeRarity,
    val category: BadgeCategory,
    val requirement: AchievementRequirement,
    val points: Int
)

enum class BadgeRarity {
    COMMON,      // 普通
    UNCOMMON,    // 不常见
    RARE,        // 稀有
    EPIC,        // 史诗
    LEGENDARY    // 传说
}

enum class BadgeCategory {
    CONVERSATION,   // 对话类
    TOOL,            // 工具使用
    LEARNING,        // 学习类
    CREATIVE,        // 创意类
    SOCIAL,          // 社交类
    EXPLORATION,     // 探索类
    SPECIAL          // 特殊
}

/**
 * 成就要求
 */
sealed class AchievementRequirement {
    data class Count(val metric: String, val target: Int) : AchievementRequirement()
    data class Streak(val metric: String, val target: Int) : AchievementRequirement()
    data class Specific(val condition: String) : AchievementRequirement()
}

/**
 * 用户成就状态
 */
data class UserAchievement(
    val userId: String,
    val level: Int,
    val totalXP: Long,
    val currentLevelXP: Long,
    val nextLevelXP: Long,
    val badges: List<EarnedBadge>,
    val activeStreaks: Map<String, StreakInfo>,
    val completedChallenges: List<String>,
    val rank: Int? = null
)

data class EarnedBadge(
    val badgeId: String,
    val earnedAt: Long,
    val progress: Float = 1.0f
)

data class StreakInfo(
    val metric: String,
    val current: Int,
    val best: Int,
    val lastUpdate: Long
)

/**
 * 挑战
 */
data class Challenge(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val type: ChallengeType,
    val requirement: AchievementRequirement,
    val rewardXP: Int,
    val rewardBadge: String? = null,
    val deadline: Long? = null,
    val difficulty: Int = 1  // 1-5
)

enum class ChallengeType {
    DAILY,      // 每日
    WEEKLY,     // 每周
    ONE_TIME,   // 一次性
    EVENT       // 活动
}

/**
 * 成就事件
 */
sealed class AchievementEvent {
    data class BadgeEarned(val badge: Badge) : AchievementEvent()
    data class LevelUp(val newLevel: Int) : AchievementEvent()
    data class StreakExtended(val metric: String, val count: Int) : AchievementEvent()
    data class StreakBroken(val metric: String, val was: Int) : AchievementEvent()
    data class ChallengeCompleted(val challenge: Challenge) : AchievementEvent()
}

/**
 * 成就系统
 */
class AchievementSystem {

    private val userStates = ConcurrentHashMap<String, UserAchievement>()
    private val badges = ConcurrentHashMap<String, Badge>()
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val metrics = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()  // userId -> (metric -> value)

    init {
        registerBuiltinBadges()
        registerBuiltinChallenges()
    }

    /**
     * 记录指标
     */
    fun recordMetric(userId: String, metric: String, value: Long = 1) {
        val userMetrics = metrics.computeIfAbsent(userId) { ConcurrentHashMap() }
        userMetrics[metric] = (userMetrics[metric] ?: 0) + value

        // 更新连击
        updateStreak(userId, metric)

        // 检查徽章
        checkBadges(userId)

        // 检查挑战
        checkChallenges(userId)

        // 更新等级
        addXP(userId, value.toInt())
    }

    /**
     * 获取用户状态
     */
    fun getUserState(userId: String): UserAchievement {
        return userStates[userId] ?: initUser(userId)
    }

    /**
     * 获取所有徽章
     */
    fun listBadges(category: BadgeCategory? = null): List<Badge> {
        return badges.values
            .filter { category == null || it.category == category }
            .sortedByDescending { it.rarity.ordinal }
            .toList()
    }

    /**
     * 获取活跃挑战
     */
    fun listActiveChallenges(): List<Challenge> {
        val now = System.currentTimeMillis()
        return challenges.values
            .filter { it.deadline == null || it.deadline > now }
            .sortedBy { it.difficulty }
            .toList()
    }

    /**
     * 获取用户未获得的徽章进度
     */
    fun getBadgeProgress(userId: String): List<BadgeProgress> {
        val state = getUserState(userId)
        val earned = state.badges.map { it.badgeId }.toSet()
        return badges.values
            .filter { it.id !in earned }
            .map { badge ->
                BadgeProgress(
                    badge = badge,
                    progress = computeProgress(userId, badge.requirement),
                    earned = false
                )
            }
            .sortedByDescending { it.progress }
    }

    data class BadgeProgress(
        val badge: Badge,
        val progress: Float,
        val earned: Boolean
    )

    /**
     * 生成成就报告
     */
    fun generateReport(userId: String): String {
        val state = getUserState(userId)
        val sb = StringBuilder()
        sb.appendLine("═══ 成就系统 ═══")
        sb.appendLine("等级: ${state.level} (XP: ${state.currentLevelXP}/${state.nextLevelXP})")
        sb.appendLine("总经验: ${state.totalXP}")
        sb.appendLine("徽章: ${state.badges.size}/${badges.size}")
        sb.appendLine()

        // 徽章展示
        if (state.badges.isNotEmpty()) {
            sb.appendLine("已获得徽章:")
            state.badges.take(10).forEach { eb ->
                val badge = badges[eb.badgeId]
                if (badge != null) {
                    sb.appendLine("  ${badge.icon} ${badge.displayName} [${badge.rarity}]")
                }
            }
            sb.appendLine()
        }

        // 连击
        if (state.activeStreaks.isNotEmpty()) {
            sb.appendLine("连击:")
            state.activeStreaks.forEach { (metric, info) ->
                sb.appendLine("  $metric: ${info.current} 连击 (最高 ${info.best})")
            }
            sb.appendLine()
        }

        // 进度最高的未获得徽章
        val progress = getBadgeProgress(userId).take(3)
        if (progress.isNotEmpty()) {
            sb.appendLine("即将获得:")
            progress.forEach { p ->
                sb.appendLine("  ${p.badge.icon} ${p.badge.displayName}: ${(p.progress * 100).toInt()}%")
            }
        }

        sb.appendLine("═══════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun initUser(userId: String): UserAchievement {
        val state = UserAchievement(
            userId = userId,
            level = 1,
            totalXP = 0,
            currentLevelXP = 0,
            nextLevelXP = 100,
            badges = emptyList(),
            activeStreaks = emptyMap(),
            completedChallenges = emptyList()
        )
        userStates[userId] = state
        return state
    }

    private fun addXP(userId: String, xp: Int) {
        val state = userStates[userId] ?: initUser(userId)
        var newTotal = state.totalXP + xp
        var newCurrent = state.currentLevelXP + xp
        var newLevel = state.level
        var nextXP = state.nextLevelXP

        while (newCurrent >= nextXP) {
            newCurrent -= nextXP
            newLevel++
            nextXP = (nextXP * 1.5).toLong()
        }

        userStates[userId] = state.copy(
            totalXP = newTotal,
            currentLevelXP = newCurrent,
            nextLevelXP = nextXP,
            level = newLevel
        )
    }

    private fun updateStreak(userId: String, metric: String) {
        val state = userStates[userId] ?: initUser(userId)
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60_000L

        val current = state.activeStreaks[metric]
        val updated = if (current != null) {
            if (now - current.lastUpdate < dayMs) {
                // 同一天，不增加
                current
            } else if (now - current.lastUpdate < 2 * dayMs) {
                // 连续，+1
                current.copy(current = current.current + 1, best = maxOf(current.best, current.current + 1), lastUpdate = now)
            } else {
                // 断了，重置
                current.copy(current = 1, lastUpdate = now)
            }
        } else {
            StreakInfo(metric, 1, 1, now)
        }

        userStates[userId] = state.copy(activeStreaks = state.activeStreaks + (metric to updated))
    }

    private fun checkBadges(userId: String) {
        val state = userStates[userId] ?: return
        val earned = state.badges.map { it.badgeId }.toSet()

        for (badge in badges.values) {
            if (badge.id in earned) continue
            if (computeProgress(userId, badge.requirement) >= 1.0f) {
                val newBadge = EarnedBadge(badge.id, System.currentTimeMillis())
                userStates[userId] = state.copy(
                    badges = state.badges + newBadge
                )
                addXP(userId, badge.points)
            }
        }
    }

    private fun checkChallenges(userId: String) {
        val state = userStates[userId] ?: return
        val completed = state.completedChallenges.toMutableList()

        for (challenge in challenges.values) {
            if (challenge.id in completed) continue
            if (challenge.deadline != null && challenge.deadline < System.currentTimeMillis()) continue

            if (computeProgress(userId, challenge.requirement) >= 1.0f) {
                completed.add(challenge.id)
                addXP(userId, challenge.rewardXP)
            }
        }

        userStates[userId] = state.copy(completedChallenges = completed)
    }

    private fun computeProgress(userId: String, req: AchievementRequirement): Float {
        val userMetrics = metrics[userId] ?: return 0f
        return when (req) {
            is AchievementRequirement.Count -> {
                val current = userMetrics[req.metric] ?: 0
                (current.toFloat() / req.target).coerceIn(0f, 1f)
            }
            is AchievementRequirement.Streak -> {
                val state = userStates[userId]
                val streak = state?.activeStreaks?.get(req.metric)
                if (streak != null) (streak.current.toFloat() / req.target).coerceIn(0f, 1f) else 0f
            }
            is AchievementRequirement.Composite -> {
                val progresses = req.requirements.map { computeProgress(userId, it) }
                if (req.allRequired) progresses.minOrNull() ?: 0f
                else progresses.maxOrNull() ?: 0f
            }
            is AchievementRequirement.Specific -> 0f  // 需手动触发
        }
    }

    // ============ 预置徽章 ============

    private fun registerBuiltinBadges() {
        // 对话类
        badges["badge_first_chat"] = Badge("badge_first_chat", "first_chat", "初次对话", "完成第一次对话", "💬", BadgeRarity.COMMON, BadgeCategory.CONVERSATION, AchievementRequirement.Count("messages", 1), 10)
        badges["badge_chatter"] = Badge("badge_chatter", "chatter", "话痨", "发送 100 条消息", "🗣️", BadgeRarity.UNCOMMON, BadgeCategory.CONVERSATION, AchievementRequirement.Count("messages", 100), 50)
        badges["badge_talkative"] = Badge("badge_talkative", "talkative", "滔滔不绝", "发送 1000 条消息", "🌊", BadgeRarity.RARE, BadgeCategory.CONVERSATION, AchievementRequirement.Count("messages", 1000), 200)
        badges["badge_conversation_master"] = Badge("badge_conversation_master", "master", "对话大师", "发送 10000 条消息", "👑", BadgeRarity.LEGENDARY, BadgeCategory.CONVERSATION, AchievementRequirement.Count("messages", 10000), 1000)

        // 连击类
        badges["badge_streak_7"] = Badge("badge_streak_7", "streak7", "一周坚持", "连续 7 天对话", "🔥", BadgeRarity.UNCOMMON, BadgeCategory.CONVERSATION, AchievementRequirement.Streak("daily_chat", 7), 100)
        badges["badge_streak_30"] = Badge("badge_streak_30", "streak30", "月度坚持", "连续 30 天对话", "⚡", BadgeRarity.RARE, BadgeCategory.CONVERSATION, AchievementRequirement.Streak("daily_chat", 30), 500)
        badges["badge_streak_100"] = Badge("badge_streak_100", "streak100", "百日传奇", "连续 100 天对话", "💯", BadgeRarity.LEGENDARY, BadgeCategory.CONVERSATION, AchievementRequirement.Streak("daily_chat", 100), 2000)

        // 工具类
        badges["badge_first_tool"] = Badge("badge_first_tool", "first_tool", "工具初体验", "第一次使用工具", "🔧", BadgeRarity.COMMON, BadgeCategory.TOOL, AchievementRequirement.Count("tool_calls", 1), 10)
        badges["badge_tool_user"] = Badge("badge_tool_user", "tool_user", "工具使用者", "使用工具 50 次", "🛠️", BadgeRarity.UNCOMMON, BadgeCategory.TOOL, AchievementRequirement.Count("tool_calls", 50), 50)
        badges["badge_power_user"] = Badge("badge_power_user", "power_user", "高级用户", "使用工具 500 次", "⚙️", BadgeRarity.RARE, BadgeCategory.TOOL, AchievementRequirement.Count("tool_calls", 500), 200)

        // 学习类
        badges["badge_learner"] = Badge("badge_learner", "learner", "学习者", "完成 10 次学习场景对话", "📚", BadgeRarity.UNCOMMON, BadgeCategory.LEARNING, AchievementRequirement.Count("learning_sessions", 10), 100)
        badges["badge_scholar"] = Badge("badge_scholar", "scholar", "学者", "完成 100 次学习场景对话", "🎓", BadgeRarity.EPIC, BadgeCategory.LEARNING, AchievementRequirement.Count("learning_sessions", 100), 500)

        // 创意类
        badges["badge_creative"] = Badge("badge_creative", "creative", "创意萌芽", "使用创意工坊 1 次", "🌱", BadgeRarity.COMMON, BadgeCategory.CREATIVE, AchievementRequirement.Count("creative_writes", 1), 20)
        badges["badge_author"] = Badge("badge_author", "author", "作家", "创作 10 篇作品", "✍️", BadgeRarity.RARE, BadgeCategory.CREATIVE, AchievementRequirement.Count("creative_writes", 10), 200)

        // 玩梗
        badges["badge_meme_lord"] = Badge("badge_meme_lord", "meme_lord", "梗王", "使用 100 个梗", "🤣", BadgeRarity.EPIC, BadgeCategory.SPECIAL, AchievementRequirement.Count("memes_used", 100), 300)

        // 探索类
        badges["badge_explorer"] = Badge("badge_explorer", "explorer", "探索者", "尝试 5 种不同场景", "🧭", BadgeRarity.UNCOMMON, BadgeCategory.EXPLORATION, AchievementRequirement.Count("scenes_tried", 5), 50)
        badges["badge_gamer"] = Badge("badge_gamer", "gamer", "玩家", "完成 10 局游戏", "🎮", BadgeRarity.UNCOMMON, BadgeCategory.SPECIAL, AchievementRequirement.Count("games_played", 10), 100)

        // 特殊
        badges["badge_night_owl"] = Badge("badge_night_owl", "night_owl", "夜猫子", "凌晨 2 点后对话", "🦉", BadgeRarity.RARE, BadgeCategory.SPECIAL, AchievementRequirement.Specific("after_2am"), 100)
        badges["badge_early_bird"] = Badge("badge_early_bird", "early_bird", "早起鸟", "早上 6 点前对话", "🐦", BadgeRarity.RARE, BadgeCategory.SPECIAL, AchievementRequirement.Specific("before_6am"), 100)
    }

    private fun registerBuiltinChallenges() {
        challenges["challenge_daily_10"] = Challenge(
            "challenge_daily_10", "今日十连", "今天发送 10 条消息", "📅",
            ChallengeType.DAILY, AchievementRequirement.Count("daily_messages", 10), 50, "badge_chatter"
        )
        challenges["challenge_weekly_tools"] = Challenge(
            "challenge_weekly_tools", "本周工具达人", "本周使用 30 次工具", "🔧",
            ChallengeType.WEEKLY, AchievementRequirement.Count("weekly_tool_calls", 30), 100
        )
        challenges["challenge_first_game"] = Challenge(
            "challenge_first_game", "初次游戏", "完成第一局对话游戏", "🎲",
            ChallengeType.ONE_TIME, AchievementRequirement.Count("games_played", 1), 50, "badge_gamer"
        )
    }
}
