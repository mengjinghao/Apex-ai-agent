package com.apex.agent.kernel.burst.enhanced.reward

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B15: 战利品/奖励系统（Loot & Reward System）
 *
 * 游戏化设计，提升用户参与度：
 * - 任务成功掉落"战利品"（新 Skill 解锁/配置升级/配额增加）
 * - 失败扣除"生命值"
 * - 失败 3 次进入"狂暴冷却"
 */
class RewardSystem {

    enum class LootType {
        SKILL_UNLOCK,       // 解锁新 Skill
        CONFIG_BOOST,       // 配置升级
        QUOTA_INCREASE,     // 配额增加
        STAGE_UP,           // 演化阶段提升
        RAGE_CAP_INCREASE,  // 暴怒上限提升
        ENERGY_BOOST        // 能量恢复
    }

    enum class Rarity { COMMON, RARE, EPIC, LEGENDARY }

    data class Loot(
        val id: String,
        val type: LootType,
        val payload: Map<String, Any>,
        val rarity: Rarity,
        val description: String,
        val obtainedAt: Long = System.currentTimeMillis()
    )

    data class RewardState(
        val level: Int,
        val xp: Long,
        val xpToNextLevel: Long,
        val lives: Int,
        val maxLives: Int,
        val inventory: List<Loot>,
        val totalLootCollected: Int,
        val streak: Int,
        val bestStreak: Int,
        val isBerserkCooldown: Boolean,
        val berserkCooldownEnds: Long?
    )

    data class RewardEvent(
        val type: RewardEventType,
        val loot: Loot?,
        val xpGained: Long,
        val livesChanged: Int,
        val description: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class RewardEventType {
        TASK_SUCCESS, TASK_FAILURE,
        LOOT_GAINED, LEVEL_UP, LIFE_LOST, LIFE_RECOVERED,
        STREAK_EXTENDED, STREAK_BROKEN,
        BERSERK_COOLDOWN_START, BERSERK_COOLDOWN_END
    }

    private val _state = MutableStateFlow(RewardState(
        level = 1, xp = 0, xpToNextLevel = 100,
        lives = 3, maxLives = 3,
        inventory = emptyList(), totalLootCollected = 0,
        streak = 0, bestStreak = 0,
        isBerserkCooldown = false, berserkCooldownEnds = null
    ))
    val state: StateFlow<RewardState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<RewardEvent>>(emptyList())
    val events: StateFlow<List<RewardEvent>> = _events.asStateFlow()

    private val recentEvents = mutableListOf<RewardEvent>()
    private val availableSkills = mutableListOf<String>(
        "extreme_reasoning", "red_blue_adversarial", "parallel_universe_explorer",
        "time_travel_debugger", "auto_heal"
    )

    /**
     * 任务成功
     */
    fun onTaskSuccess(quality: Float = 1.0f, complexity: Int = 1) {
        val xpGained = (50 * quality * complexity).toLong()
        val loot = rollLoot(quality, complexity)
        val newStreak = _state.value.streak + 1

        addXp(xpGained)
        updateState(
            _state.value.copy(
                streak = newStreak,
                bestStreak = maxOf(_state.value.bestStreak, newStreak),
                lives = (_state.value.lives + if (newStreak % 5 == 0) 1 else 0).coerceAtMost(_state.value.maxLives)
            )
        )

        emitEvent(RewardEvent(
            RewardEventType.TASK_SUCCESS, loot, xpGained, 0,
            "任务成功！+${xpGained} XP${loot?.let { "，获得 ${it.description}" } ?: ""}"
        ))

        loot?.let { addLoot(it) }
    }

    /**
     * 任务失败
     */
    fun onTaskFailure() {
        val newLives = _state.value.lives - 1
        val newStreak = 0
        val cooldown = newLives <= 0

        updateState(
            _state.value.copy(
                lives = if (cooldown) 0 else newLives,
                streak = newStreak,
                isBerserkCooldown = cooldown,
                berserkCooldownEnds = if (cooldown) System.currentTimeMillis() + 5 * 60_000L else null
            )
        )

        emitEvent(RewardEvent(
            RewardEventType.TASK_FAILURE, null, 0, -1,
            if (cooldown) "生命值耗尽！进入 5 分钟狂暴冷却" else "任务失败，失去 1 条生命"
        ))
    }

    /**
     * 使用战利品
     */
    fun redeem(lootId: String): Boolean {
        val loot = _state.value.inventory.find { it.id == lootId } ?: return false
        // 应用效果（简化：从库存移除）
        updateState(_state.value.copy(inventory = _state.value.inventory - loot))
        emitEvent(RewardEvent(
            RewardEventType.LOOT_GAINED, loot, 0, 0, "使用了 ${loot.description}"
        ))
        return true
    }

    /**
     * 恢复生命值（冷却结束）
     */
    fun recoverLives() {
        val state = _state.value
        if (state.isBerserkCooldown && state.berserkCooldownEnds != null) {
            if (System.currentTimeMillis() >= state.berserkCooldownEnds) {
                updateState(state.copy(
                    lives = state.maxLives,
                    isBerserkCooldown = false,
                    berserkCooldownEnds = null
                ))
                emitEvent(RewardEvent(
                    RewardEventType.LIFE_RECOVERED, null, 0, state.maxLives,
                    "狂暴冷却结束，生命值已恢复"
                ))
            }
        }
    }

    /**
     * 生成状态报告
     */
    fun generateReport(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("═══ 战利品系统 ═══")
        sb.appendLine("等级: ${s.level} (XP: ${s.xp}/${s.xpToNextLevel})")
        sb.appendLine("生命: ${"❤️".repeat(s.lives)}${"🖤".repeat(s.maxLives - s.lives)} ($s.lives/$s.maxLives)")
        sb.appendLine("连击: ${s.streak} (最高 ${s.bestStreak})")
        sb.appendLine("战利品: ${s.inventory.size} 件 (累计 $s.totalLootCollected)")
        if (s.isBerserkCooldown) {
            val remaining = ((s.berserkCooldownEnds ?: 0) - System.currentTimeMillis()) / 1000
            sb.appendLine("⚠️ 狂暴冷却中 (${remaining}s)")
        }
        if (s.inventory.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("库存:")
            s.inventory.take(5).forEach { loot ->
                val icon = when (loot.rarity) {
                    Rarity.COMMON -> "⚪"
                    Rarity.RARE -> "🔵"
                    Rarity.EPIC -> "🟣"
                    Rarity.LEGENDARY -> "🟡"
                }
                sb.appendLine("  $icon ${loot.description} [${loot.rarity}]")
            }
        }
        sb.appendLine("═══════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun rollLoot(quality: Float, complexity: Int): Loot? {
        // 50% 概率掉落
        if (Math.random() > 0.5) return null

        val rarity = when {
            quality > 0.95f && complexity >= 4 -> Rarity.LEGENDARY
            quality > 0.9f -> Rarity.EPIC
            quality > 0.7f -> Rarity.RARE
            else -> Rarity.COMMON
        }

        val type = LootType.values().random()
        val description = when (type) {
            LootType.SKILL_UNLOCK -> "解锁技能: ${availableSkills.random()}"
            LootType.CONFIG_BOOST -> "配置升级: ${listOf("并发+1", "超时+10s", "重试+1").random()}"
            LootType.QUOTA_INCREASE -> "配额增加: ${listOf("Token+1000", "调用+10", "并发+1").random()}"
            LootType.STAGE_UP -> "演化阶段提升"
            LootType.RAGE_CAP_INCREASE -> "暴怒上限+10"
            LootType.ENERGY_BOOST -> "能量恢复+200"
        }

        return Loot(
            id = "loot_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            type = type,
            payload = mapOf("rarity" to rarity.name),
            rarity = rarity,
            description = description
        )
    }

    private fun addLoot(loot: Loot) {
        updateState(_state.value.copy(
            inventory = _state.value.inventory + loot,
            totalLootCollected = _state.value.totalLootCollected + 1
        ))
        emitEvent(RewardEvent(
            RewardEventType.LOOT_GAINED, loot, 0, 0,
            "获得 [${loot.rarity}] ${loot.description}"
        ))
    }

    private fun addXp(amount: Long) {
        val state = _state.value
        var newXP = state.xp + amount
        var newLevel = state.level
        var xpToNext = state.xpToNextLevel

        while (newXP >= xpToNext) {
            newXP -= xpToNext
            newLevel++
            xpToNext = (xpToNext * 1.5).toLong()
            emitEvent(RewardEvent(
                RewardEventType.LEVEL_UP, null, 0, 0,
                "升级！达到 Lv.$newLevel"
            ))
        }

        updateState(_state.value.copy(level = newLevel, xp = newXP, xpToNextLevel = xpToNext))
    }

    private fun updateState(newState: RewardState) {
        _state.value = newState
    }

    private fun emitEvent(event: RewardEvent) {
        recentEvents.add(event)
        while (recentEvents.size > 50) recentEvents.removeAt(0)
        _events.value = recentEvents.toList()
    }
}
