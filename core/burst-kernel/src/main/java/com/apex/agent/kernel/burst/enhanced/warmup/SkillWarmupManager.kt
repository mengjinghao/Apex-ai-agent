package com.apex.agent.kernel.burst.enhanced.warmup

import java.util.concurrent.ConcurrentHashMap

/**
 * B43: 技能预热/冷却管理器
 *
 * 预测即将使用的技能并提前初始化：
 * - 预热（提前初始化资源）
 * - 冷却（释放不用的资源）
 * - 使用频率追踪
 * - 预测性预热
 */
class SkillWarmupManager(
    private val warmupThreshold: Int = 3,       // 使用次数达到阈值后预热
    private val cooldownThresholdMs: Long = 300_000L,  // 5 分钟未使用则冷却
    private val maxWarmSkills: Int = 10
) {

    enum class SkillState { COLD, WARMING, WARM, COOLING }

    data class SkillWarmupState(
        val skillId: String,
        val state: SkillState,
        val useCount: Int,
        val lastUsedAt: Long,
        val warmedAt: Long?,
        val warmupDurationMs: Long?,
        val predictedNextUse: Long?
    )

    data class WarmupResult(
        val skillId: String,
        val success: Boolean,
        val durationMs: Long,
        val fromState: SkillState,
        val toState: SkillState
    )

    fun interface WarmupHandler {
        suspend fun warmup(skillId: String): Boolean
    }

    fun interface CooldownHandler {
        fun cooldown(skillId: String)
    }

    private val states = ConcurrentHashMap<String, SkillWarmupState>()
    private val usageHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private var warmupHandler: WarmupHandler? = null
    private var cooldownHandler: CooldownHandler? = null

    fun setHandlers(warmup: WarmupHandler, cooldown: CooldownHandler) {
        warmupHandler = warmup
        cooldownHandler = cooldown
    }

    /**
     * 记录技能使用
     */
    fun recordUsage(skillId: String) {
        val now = System.currentTimeMillis()
        val history = usageHistory.computeIfAbsent(skillId) { mutableListOf() }
        history.add(now)
        while (history.size > 100) history.removeAt(0)

        val current = states[skillId]
        states[skillId] = SkillWarmupState(
            skillId = skillId,
            state = current?.state ?: SkillState.COLD,
            useCount = (current?.useCount ?: 0) + 1,
            lastUsedAt = now,
            warmedAt = current?.warmedAt,
            warmupDurationMs = current?.warmupDurationMs,
            predictedNextUse = predictNextUse(skillId)
        )
    }

    /**
     * 预热技能
     */
    suspend fun warmup(skillId: String): WarmupResult {
        val current = states[skillId] ?: SkillWarmupState(
            skillId, SkillState.COLD, 0, System.currentTimeMillis(), null, null, null
        )
        val fromState = current.state
        val start = System.currentTimeMillis()

        val success = try {
            warmupHandler?.warmup(skillId) ?: true
        } catch (e: Exception) { false }

        val duration = System.currentTimeMillis() - start
        val newState = if (success) SkillState.WARM else SkillState.COLD

        states[skillId] = current.copy(
            state = newState,
            warmedAt = System.currentTimeMillis(),
            warmupDurationMs = duration
        )

        return WarmupResult(skillId, success, duration, fromState, newState)
    }

    /**
     * 冷却技能
     */
    fun cooldown(skillId: String) {
        val current = states[skillId] ?: return
        if (current.state == SkillState.WARM) {
            cooldownHandler?.cooldown(skillId)
            states[skillId] = current.copy(state = SkillState.COLD)
        }
    }

    /**
     * 检查并执行冷却（定时调用）
     */
    fun checkCooldowns() {
        val now = System.currentTimeMillis()
        var warmCount = states.values.count { it.state == SkillState.WARM }

        // 冷却超时的技能
        val toCooldown = states.values
            .filter { it.state == SkillState.WARM && now - it.lastUsedAt > cooldownThresholdMs }
            .sortedBy { it.lastUsedAt }

        for (skill in toCooldown) {
            if (warmCount <= maxWarmSkills) break
            cooldown(skill.skillId)
            warmCount--
        }
    }

    /**
     * 预测性预热
     */
    suspend fun predictiveWarmup(): List<WarmupResult> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<WarmupResult>()

        // 找出预测即将使用但还未预热的技能
        val candidates = states.values
            .filter { it.state == SkillState.COLD && it.useCount >= warmupThreshold }
            .filter { it.predictedNextUse != null && it.predictedNextUse!! - now < 60_000 }
            .sortedBy { it.predictedNextUse }

        for (candidate in candidates.take(3)) {
            results.add(warmup(candidate.skillId))
        }

        return results
    }

    /**
     * 预测下次使用时间
     */
    private fun predictNextUse(skillId: String): Long? {
        val history = usageHistory[skillId] ?: return null
        if (history.size < 3) return null

        // 计算平均间隔
        val intervals = history.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average().toLong()
        val lastUse = history.last()

        return lastUse + avgInterval
    }

    fun getState(skillId: String): SkillWarmupState? = states[skillId]
    fun getAllStates(): List<SkillWarmupState> = states.values.toList()
    fun getWarmSkills(): List<String> = states.values.filter { it.state == SkillState.WARM }.map { it.skillId }

    fun getStats(): WarmupStats {
        return WarmupStats(
            totalTracked = states.size,
            warmCount = states.values.count { it.state == SkillState.WARM },
            coldCount = states.values.count { it.state == SkillState.COLD },
            avgUseCount = if (states.isNotEmpty()) states.values.map { it.useCount }.average().toInt() else 0
        )
    }

    data class WarmupStats(
        val totalTracked: Int,
        val warmCount: Int,
        val coldCount: Int,
        val avgUseCount: Int
    )
}
