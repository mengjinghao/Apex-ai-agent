package com.apex.agent.kernel.burst.enhanced

import com.apex.agent.kernel.burst.enhanced.battle.BattleRecorder
import com.apex.agent.kernel.burst.enhanced.circuit.CircuitBreakerManager
import com.apex.agent.kernel.burst.enhanced.compression.ContextCompressor
import com.apex.agent.kernel.burst.enhanced.difficulty.DifficultyAdapter
import com.apex.agent.kernel.burst.enhanced.evolution.EvolutionEngine
import com.apex.agent.kernel.burst.enhanced.learning.FailureLearner
import com.apex.agent.kernel.burst.enhanced.ensemble.ModelEnsemble
import com.apex.agent.kernel.burst.enhanced.preemption.PreemptiveScheduler
import com.apex.agent.kernel.burst.enhanced.quota.QuotaManager
import com.apex.agent.kernel.burst.enhanced.rage.RageMeter
import com.apex.agent.kernel.burst.enhanced.reward.RewardSystem
import com.apex.agent.kernel.burst.enhanced.risk.RiskAssessor
import com.apex.agent.kernel.burst.enhanced.strategy.StrategyHotSwapper
import com.apex.agent.kernel.burst.enhanced.timetravel.TimeTravelDebugger
import com.apex.agent.kernel.burst.enhanced.universe.ParallelUniverseExplorer

/**
 * 狂暴模式增强编排器
 *
 * 整合 15 项增强能力，提供统一的狂暴模式增强入口
 *
 * B1. 暴怒值/能量系统 - RageMeter
 * B2. 战斗日志与回放 - BattleRecorder
 * B3. 失败学习系统 - FailureLearner
 * B4. 任务优先级抢占 - PreemptiveScheduler
 * B5. 多阶段进化 - EvolutionEngine
 * B6. 并行宇宙探索 - ParallelUniverseExplorer
 * B7. 实时策略热切换 - StrategyHotSwapper
 * B8. 自适应难度调节 - DifficultyAdapter
 * B9. 资源配额管理 - QuotaManager
 * B10. 时间旅行调试 - TimeTravelDebugger
 * B11. 智能断流恢复 - CircuitBreakerManager
 * B12. 多模型混合推理 - ModelEnsemble
 * B13. 上下文压缩策略库 - ContextCompressor
 * B14. 风险评估与降级 - RiskAssessor
 * B15. 战利品/奖励系统 - RewardSystem
 */
class BurstEnhancementOrchestrator(
    val config: EnhancementConfig = EnhancementConfig()
) {

    data class EnhancementConfig(
        val enableRage: Boolean = true,
        val enableBattleLog: Boolean = true,
        val enableFailureLearning: Boolean = true,
        val enablePreemption: Boolean = true,
        val enableEvolution: Boolean = true,
        val enableParallelUniverse: Boolean = true,
        val enableStrategySwitch: Boolean = true,
        val enableDifficulty: Boolean = true,
        val enableQuota: Boolean = true,
        val enableTimeTravel: Boolean = true,
        val enableCircuitBreaker: Boolean = true,
        val enableModelEnsemble: Boolean = true,
        val enableCompression: Boolean = true,
        val enableRiskAssessment: Boolean = true,
        val enableReward: Boolean = true
    )

    // B1: 暴怒值系统
    val rageMeter = if (config.enableRage) RageMeter() else null
    // B2: 战斗日志
    val battleRecorder = if (config.enableBattleLog) BattleRecorder() else null
    // B3: 失败学习
    val failureLearner = if (config.enableFailureLearning) FailureLearner() else null
    // B4: 优先级抢占
    val preemptiveScheduler = if (config.enablePreemption) PreemptiveScheduler() else null
    // B5: 进化引擎
    val evolutionEngine = if (config.enableEvolution) EvolutionEngine() else null
    // B6: 并行宇宙
    val universeExplorer = if (config.enableParallelUniverse) ParallelUniverseExplorer() else null
    // B7: 策略热切换
    val strategySwapper = if (config.enableStrategySwitch) StrategyHotSwapper() else null
    // B8: 自适应难度
    val difficultyAdapter = if (config.enableDifficulty) DifficultyAdapter() else null
    // B9: 资源配额
    val quotaManager = if (config.enableQuota) QuotaManager() else null
    // B10: 时间旅行
    val timeTravelDebugger = battleRecorder?.let { if (config.enableTimeTravel) TimeTravelDebugger(it) else null }
    // B11: 断路器
    val circuitBreaker = if (config.enableCircuitBreaker) CircuitBreakerManager() else null
    // B12: 多模型
    val modelEnsemble = if (config.enableModelEnsemble) ModelEnsemble() else null
    // B13: 上下文压缩
    val contextCompressor = if (config.enableCompression) ContextCompressor() else null
    // B14: 风险评估
    val riskAssessor = if (config.enableRiskAssessment) RiskAssessor() else null
    // B15: 奖励系统
    val rewardSystem = if (config.enableReward) RewardSystem() else null

    /**
     * 任务执行前：风险评估 + 失败学习注入 + 配额获取
     */
    fun beforeTaskExecution(
        taskId: String,
        skillId: String,
        input: String,
        taskContext: risk.TaskContext? = null
    ): BeforeTaskResult {
        val injections = mutableListOf<String>()

        // B14: 风险评估
        val riskAssessment = taskContext?.let { riskAssessor?.assess(it) }

        // B3: 失败学习提示
        val avoidancePrompt = failureLearner?.generateAvoidancePrompt(taskId, skillId, input)
        if (avoidancePrompt?.isNotBlank() == true) {
            injections.add(avoidancePrompt)
        }

        // B1: 暴怒增益
        rageMeter?.currentBoost()?.let { boost ->
            if (boost.concurrencyMultiplier > 1.0f) {
                injections.add("[狂暴增益] 并发×${boost.concurrencyMultiplier} 重试×${boost.retryMultiplier}")
            }
        }

        // B8: 难度配置
        difficultyAdapter?.let { adapter ->
            val skillChain = adapter.selectSkillChain()
            if (skillChain.size > 1) {
                injections.add("[难度: ${adapter.currentDifficulty.value}] Skill链: $skillChain")
            }
        }

        // B2: 战斗日志开始记录
        val startFrame = battleRecorder?.let { recorder ->
            rageMeter?.let { rage ->
                recorder.recordStart(
                    taskId = taskId, skillId = skillId, skillName = skillId,
                    input = input, rage = rage.rage.value,
                    berserkState = rage.state.value.name,
                    strategy = strategySwapper?.currentStrategy?.value?.name ?: "ADAPTIVE"
                )
            }
        }

        return BeforeTaskResult(
            riskAssessment = riskAssessment,
            avoidancePrompt = avoidancePrompt,
            injections = injections,
            startFrame = startFrame
        )
    }

    /**
     * 任务执行后：记录结果 + 更新暴怒 + 更新难度 + 奖励
     */
    fun afterTaskExecution(
        taskId: String,
        skillId: String,
        input: String,
        output: String,
        success: Boolean,
        durationMs: Long,
        error: Throwable? = null,
        startFrame: battle.BattleRecorder.BattleFrame? = null,
        complexity: Int = 1,
        quality: Float = 1.0f
    ) {
        // B1: 更新暴怒
        rageMeter?.let { rage ->
            if (success) rage.onTaskSucceeded(quality)
            else rage.onTaskFailed(
                if (error?.message?.contains("timeout", true) == true) RageMeter.FailureSeverity.MODERATE
                else RageMeter.FailureSeverity.MAJOR,
                error?.message ?: ""
            )
        }

        // B2: 记录战斗帧
        battleRecorder?.let { recorder ->
            startFrame?.let { frame ->
                rageMeter?.let { rage ->
                    recorder.recordEnd(
                        frame,
                        if (success) battle.BattleRecorder.FrameState.SUCCESS else battle.BattleRecorder.FrameState.FAILED,
                        output, rage.rage.value, rage.state.value.name
                    )
                }
            }
        }

        // B3: 记录失败
        if (!success && error != null) {
            failureLearner?.record(taskId, skillId, input, error)
        }

        // B7: 策略统计
        strategySwapper?.recordExecution(
            strategySwapper.currentStrategy.value, success, durationMs
        )

        // B8: 更新难度
        difficultyAdapter?.onTaskCompleted(taskId, success, complexity, durationMs)

        // B15: 奖励系统
        rewardSystem?.let { reward ->
            if (success) reward.onTaskSuccess(quality, complexity)
            else reward.onTaskFailure()
        }

        // B9: 配额释放（由调用方通过 lease 释放）
    }

    /**
     * 生成全面状态报告
     */
    fun generateFullReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════")
        sb.appendLine("      狂暴模式增强状态报告")
        sb.appendLine("═══════════════════════════════════")
        sb.appendLine()

        // B1: 暴怒值
        rageMeter?.let { sb.appendLine(it.getStatusSummary()); sb.appendLine() }

        // B7: 策略
        strategySwapper?.let {
            sb.appendLine("═══ 执行策略 ═══")
            sb.appendLine("当前: ${it.currentStrategy.value}")
            sb.appendLine("切换次数: ${it.getSwitchHistory().size}")
            sb.appendLine()
        }

        // B8: 难度
        difficultyAdapter?.let {
            sb.appendLine("═══ 难度等级 ═══")
            sb.appendLine("当前: ${it.currentDifficulty.value}")
            val stats = it.getStats()
            sb.appendLine("成功率: ${(stats.successRate * 100).toInt()}%")
            sb.appendLine("总任务: ${stats.totalTasks}")
            sb.appendLine()
        }

        // B5: 进化
        evolutionEngine?.let {
            sb.appendLine("═══ 进化引擎 ═══")
            sb.appendLine("代数: ${it.generation.value}")
            sb.appendLine("阶段: ${it.stage.value}")
            it.stats.value.let { s ->
                sb.appendLine("最佳适应度: ${s.bestFitness}")
                sb.appendLine("平均适应度: ${s.avgFitness}")
            }
            sb.appendLine()
        }

        // B2: 战斗日志
        battleRecorder?.let {
            sb.appendLine("═══ 战斗日志 ═══")
            sb.appendLine("总帧数: ${it.frameCount()}")
            val stats = it.getGlobalStats()
            sb.appendLine("成功率: ${(stats.successRate * 100).toInt()}%")
            sb.appendLine("狂暴时段: ${stats.berserkPeriods.size}")
            sb.appendLine()
        }

        // B3: 失败学习
        failureLearner?.let {
            sb.appendLine("═══ 失败学习 ═══")
            val stats = it.getStats()
            sb.appendLine("总失败: ${stats.totalFailures}")
            sb.appendLine("失败模式: ${stats.totalPatterns}")
            sb.appendLine()
        }

        // B11: 断路器
        circuitBreaker?.let {
            sb.appendLine("═══ 断路器 ═══")
            val circuits = it.getAllCircuits()
            sb.appendLine("监控数: ${circuits.size}")
            val openCount = circuits.count { it.state == circuit.CircuitBreakerManager.CircuitState.OPEN }
            if (openCount > 0) sb.appendLine("⚠️ 开启中: $openCount")
            sb.appendLine()
        }

        // B9: 配额
        quotaManager?.let {
            sb.appendLine("═══ 资源配额 ═══")
            val status = it.checkQuota()
            status.percentages.forEach { (k, v) ->
                val bar = "█".repeat((v * 10).toInt().coerceAtMost(10)) + "░".repeat(10 - (v * 10).toInt().coerceAtMost(10))
                sb.appendLine("  ${k.padEnd(15)} $bar ${(v * 100).toInt()}%")
            }
            sb.appendLine()
        }

        // B14: 风险
        riskAssessor?.let {
            sb.appendLine("═══ 风险评估 ═══")
            val stats = it.getStats()
            sb.appendLine("总评估: ${stats.totalAssessments}")
            sb.appendLine("平均风险: ${stats.avgScore}")
            sb.appendLine("高风险: ${stats.highRiskCount}")
            sb.appendLine()
        }

        // B15: 奖励
        rewardSystem?.let {
            sb.appendLine(it.generateReport())
        }

        sb.appendLine("═══════════════════════════════════")
        return sb.toString()
    }

    /**
     * 关闭所有
     */
    fun shutdown() {
        rageMeter?.shutdown()
    }

    data class BeforeTaskResult(
        val riskAssessment: risk.RiskAssessment?,
        val avoidancePrompt: String?,
        val injections: List<String>,
        val startFrame: battle.BattleRecorder.BattleFrame?
    )
}
