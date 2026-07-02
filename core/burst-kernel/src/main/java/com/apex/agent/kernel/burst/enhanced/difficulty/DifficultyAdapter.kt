package com.apex.agent.kernel.burst.enhanced.difficulty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * B8: 自适应难度调节（Adaptive Difficulty）
 *
 * 根据用户历史任务成功率自动调节任务难度档位：
 * - 成功率高 → 自动启用更复杂 Skill 组合
 * - 成功率低 → 降级到单 Skill 简单模式
 *
 * 难度档位：EASY / NORMAL / HARD / NIGHTMARE
 */
class DifficultyAdapter(
    private val windowSize: Int = 20,              // 滑动窗口大小
    private val adjustIntervalMs: Long = 60_000L,  // 调整间隔
    private val upgradeThreshold: Float = 0.85f,   // 升级阈值
    private val downgradeThreshold: Float = 0.5f   // 降级阈值
) {

    enum class Difficulty {
        EASY,       // 简单：单 Skill
        NORMAL,     // 普通：双 Skill 串行
        HARD,       // 困难：三 Skill 链
        NIGHTMARE   // 噩梦：并行宇宙
    }

    /**
     * 难度配置
     */
    data class DifficultyConfig(
        val difficulty: Difficulty,
        val skillChain: List<String>,
        val maxConcurrency: Int,
        val enableSpeculative: Boolean,
        val enableParallelUniverse: Boolean,
        val description: String
    )

    /**
     * 任务记录
     */
    data class TaskRecord(
        val taskId: String,
        val difficulty: Difficulty,
        val success: Boolean,
        val complexity: Int,       // 1-5
        val durationMs: Long,
        val timestamp: Long
    )

    /**
     * 难度调整事件
     */
    data class DifficultyChangeEvent(
        val from: Difficulty,
        val to: Difficulty,
        val reason: String,
        val successRate: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ============ 状态 ============

    private val _currentDifficulty = MutableStateFlow(Difficulty.NORMAL)
    val currentDifficulty: StateFlow<Difficulty> = _currentDifficulty.asStateFlow()

    private val _currentConfig = MutableStateFlow(getConfig(Difficulty.NORMAL))
    val currentConfig: StateFlow<DifficultyConfig> = _currentConfig.asStateFlow()

    private val _changeEvents = MutableStateFlow<List<DifficultyChangeEvent>>(emptyList())
    val changeEvents: StateFlow<List<DifficultyChangeEvent>> = _changeEvents.asStateFlow()

    private val taskHistory = ConcurrentLinkedQueue<TaskRecord>()
    private val recentChanges = mutableListOf<DifficultyChangeEvent>()
    private var lastAdjustTime = 0L

    // ============ 公共 API ============

    /**
     * 记录任务完成
     */
    fun onTaskCompleted(
        taskId: String,
        success: Boolean,
        complexity: Int,
        durationMs: Long
    ) {
        val record = TaskRecord(
            taskId = taskId,
            difficulty = _currentDifficulty.value,
            success = success,
            complexity = complexity,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )
        taskHistory.add(record)
        while (taskHistory.size > windowSize * 2) taskHistory.poll()

        // 检查是否需要调整
        checkAdjustment()
    }

    /**
     * 选择当前难度对应的 Skill 链
     */
    fun selectSkillChain(): List<String> = _currentConfig.value.skillChain

    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): DifficultyConfig = _currentConfig.value

    /**
     * 手动设置难度
     */
    fun setDifficulty(difficulty: Difficulty, reason: String = "手动设置") {
        val old = _currentDifficulty.value
        if (old == difficulty) return
        _currentDifficulty.value = difficulty
        _currentConfig.value = getConfig(difficulty)
        recordChange(old, difficulty, reason, computeSuccessRate())
    }

    /**
     * 获取难度统计
     */
    fun getStats(): DifficultyStats {
        val records = taskHistory.toList()
        val byDifficulty = records.groupBy { it.difficulty }
        return DifficultyStats(
            currentDifficulty = _currentDifficulty.value,
            totalTasks = records.size,
            successRate = computeSuccessRate(),
            successRateByDifficulty = byDifficulty.mapValues { (_, tasks) ->
                if (tasks.isNotEmpty()) tasks.count { it.success }.toFloat() / tasks.size else 0f
            },
            avgDurationByDifficulty = byDifficulty.mapValues { (_, tasks) ->
                if (tasks.isNotEmpty()) tasks.map { it.durationMs }.average().toLong() else 0L
            },
            changeCount = recentChanges.size
        )
    }

    data class DifficultyStats(
        val currentDifficulty: Difficulty,
        val totalTasks: Int,
        val successRate: Float,
        val successRateByDifficulty: Map<Difficulty, Float>,
        val avgDurationByDifficulty: Map<Difficulty, Long>,
        val changeCount: Int
    )

    /**
     * 重置
     */
    fun reset() {
        taskHistory.clear()
        recentChanges.clear()
        _currentDifficulty.value = Difficulty.NORMAL
        _currentConfig.value = getConfig(Difficulty.NORMAL)
    }

    // ============ 内部方法 ============

    private fun checkAdjustment() {
        val now = System.currentTimeMillis()
        if (now - lastAdjustTime < adjustIntervalMs) return
        if (taskHistory.size < windowSize) return

        lastAdjustTime = now
        val successRate = computeSuccessRate()
        val current = _currentDifficulty.value

        val newDifficulty = when {
            successRate > upgradeThreshold -> upgrade(current)
            successRate < downgradeThreshold -> downgrade(current)
            else -> null
        }

        if (newDifficulty != null && newDifficulty != current) {
            val reason = if (newDifficulty.ordinal > current.ordinal) {
                "成功率 ${successRate} > $upgradeThreshold，升级难度"
            } else {
                "成功率 ${successRate} < $downgradeThreshold，降级难度"
            }
            _currentDifficulty.value = newDifficulty
            _currentConfig.value = getConfig(newDifficulty)
            recordChange(current, newDifficulty, reason, successRate)
        }
    }

    private fun computeSuccessRate(): Float {
        val recent = taskHistory.toList().takeLast(windowSize)
        if (recent.isEmpty()) return 1.0f
        return recent.count { it.success }.toFloat() / recent.size
    }

    private fun upgrade(current: Difficulty): Difficulty {
        return when (current) {
            Difficulty.EASY -> Difficulty.NORMAL
            Difficulty.NORMAL -> Difficulty.HARD
            Difficulty.HARD -> Difficulty.NIGHTMARE
            Difficulty.NIGHTMARE -> Difficulty.NIGHTMARE
        }
    }

    private fun downgrade(current: Difficulty): Difficulty {
        return when (current) {
            Difficulty.NIGHTMARE -> Difficulty.HARD
            Difficulty.HARD -> Difficulty.NORMAL
            Difficulty.NORMAL -> Difficulty.EASY
            Difficulty.EASY -> Difficulty.EASY
        }
    }

    private fun getConfig(difficulty: Difficulty): DifficultyConfig {
        return when (difficulty) {
            Difficulty.EASY -> DifficultyConfig(
                difficulty = difficulty,
                skillChain = listOf("reasoning.react"),
                maxConcurrency = 2,
                enableSpeculative = false,
                enableParallelUniverse = false,
                description = "简单模式：单 Skill，低并发"
            )
            Difficulty.NORMAL -> DifficultyConfig(
                difficulty = difficulty,
                skillChain = listOf("reasoning.chain-of-thought", "reasoning.react"),
                maxConcurrency = 4,
                enableSpeculative = false,
                enableParallelUniverse = false,
                description = "普通模式：双 Skill 串行，中并发"
            )
            Difficulty.HARD -> DifficultyConfig(
                difficulty = difficulty,
                skillChain = listOf("reasoning.tree-of-thoughts", "red_blue_adversarial", "self_correction"),
                maxConcurrency = 6,
                enableSpeculative = true,
                enableParallelUniverse = false,
                description = "困难模式：三 Skill 链，高并发，推测执行"
            )
            Difficulty.NIGHTMARE -> DifficultyConfig(
                difficulty = difficulty,
                skillChain = listOf("reasoning.tree-of-thoughts", "extreme_reasoning", "red_blue_adversarial", "reasoning.reflexion"),
                maxConcurrency = 8,
                enableSpeculative = true,
                enableParallelUniverse = true,
                description = "噩梦模式：四 Skill 并行宇宙，全速执行"
            )
        }
    }

    private fun recordChange(from: Difficulty, to: Difficulty, reason: String, successRate: Float) {
        val event = DifficultyChangeEvent(from, to, reason, successRate)
        recentChanges.add(event)
        while (recentChanges.size > 50) recentChanges.removeAt(0)
        _changeEvents.value = recentChanges.toList()
    }
}
