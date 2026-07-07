package com.apex.agent.kernel.burst.engine.boot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * E4: 内核启动序列
 *
 * 分阶段初始化内核，支持依赖图：
 * - 8 个启动阶段
 * - 阶段间依赖
 * - 失败回滚
 * - 启动进度追踪
 */
class KernelBootSequence {

    enum class BootPhase(val order: Int, val description: String) {
        PRE_INIT(0, "预初始化"),
        CONFIG_LOAD(1, "加载配置"),
        CORE_INIT(2, "核心组件初始化"),
        SERVICE_INIT(3, "服务层初始化"),
        PLUGIN_LOAD(4, "加载插件"),
        SKILL_REGISTER(5, "注册技能"),
        HEALTH_CHECK(6, "健康检查"),
        READY(7, "就绪");

        companion object {
            fun fromOrder(order: Int): BootPhase = values().find { it.order == order } ?: PRE_INIT
        }
    }

    data class PhaseResult(
        val phase: BootPhase,
        val success: Boolean,
        val durationMs: Long,
        val message: String,
        val error: String? = null
    )

    data class BootProgress(
        val currentPhase: BootPhase,
        val completedPhases: List<PhaseResult>,
        val totalPhases: Int,
        val progress: Float,
        val isComplete: Boolean,
        val isFailed: Boolean,
        val estimatedRemainingMs: Long?
    )

    data class BootDependency(
        val phase: BootPhase,
        val dependsOn: Set<BootPhase>
    )

    private val dependencies = mapOf(
        BootPhase.PRE_INIT to emptySet<BootPhase>(),
        BootPhase.CONFIG_LOAD to setOf(BootPhase.PRE_INIT),
        BootPhase.CORE_INIT to setOf(BootPhase.CONFIG_LOAD),
        BootPhase.SERVICE_INIT to setOf(BootPhase.CORE_INIT),
        BootPhase.PLUGIN_LOAD to setOf(BootPhase.SERVICE_INIT),
        BootPhase.SKILL_REGISTER to setOf(BootPhase.PLUGIN_LOAD),
        BootPhase.HEALTH_CHECK to setOf(BootPhase.SKILL_REGISTER),
        BootPhase.READY to setOf(BootPhase.HEALTH_CHECK)
    )

    private val phaseHandlers = ConcurrentHashMap<BootPhase, suspend () -> Boolean>()
    private val rollbackHandlers = ConcurrentHashMap<BootPhase, suspend () -> Unit>()
    private val completedResults = mutableListOf<PhaseResult>()
    private val _progress = MutableStateFlow(BootProgress(BootPhase.PRE_INIT, emptyList(), 8, 0f, false, false, null))
    val progress: StateFlow<BootProgress> = _progress.asStateFlow()

    fun registerPhaseHandler(phase: BootPhase, handler: suspend () -> Boolean) {
        phaseHandlers[phase] = handler
    }

    fun registerRollbackHandler(phase: BootPhase, handler: suspend () -> Unit) {
        rollbackHandlers[phase] = handler
    }

    /**
     * 执行启动序列
     */
    suspend fun execute(): Boolean {
        val startTotal = System.currentTimeMillis()
        completedResults.clear()

        for (phase in BootPhase.values()) {
            // 检查依赖
            val deps = dependencies[phase] ?: emptySet()
            for (dep in deps) {
                if (completedResults.none { it.phase == dep && it.success }) {
                    _progress.value = BootProgress(phase, completedResults.toList(), 8,
                        completedResults.size.toFloat() / 8, false, true, null)
                    return false
                }
            }

            _progress.value = BootProgress(phase, completedResults.toList(), 8,
                phase.order.toFloat() / 8, false, false, estimateRemaining(startTotal))

            val handler = phaseHandlers[phase]
            val phaseStart = System.currentTimeMillis()
            val success = if (handler != null) {
                try { handler() } catch (e: Exception) { false }
            } else true  // 无 handler 视为成功

            val duration = System.currentTimeMillis() - phaseStart
            val result = PhaseResult(phase, success, duration,
                if (success) "${phase.description}完成" else "${phase.description}失败",
                if (!success) "${phase.description}执行异常" else null)
            completedResults.add(result)

            _progress.value = BootProgress(phase, completedResults.toList(), 8,
                (phase.order + 1).toFloat() / 8, false, !success, estimateRemaining(startTotal))

            if (!success) {
                // 回滚
                rollback(completedResults)
                _progress.value = _progress.value.copy(isFailed = true, isComplete = true)
                return false
            }
        }

        _progress.value = _progress.value.copy(isComplete = true)
        return true
    }

    /**
     * 回滚
     */
    private suspend fun rollback(results: List<PhaseResult>) {
        results.reversed().filter { it.success }.forEach { result ->
            rollbackHandlers[result.phase]?.let { runCatching { it() } }
        }
    }

    private fun estimateRemaining(startMs: Long): Long? {
        if (completedResults.isEmpty()) return null
        val elapsed = System.currentTimeMillis() - startMs
        val phasesDone = completedResults.size
        val avgPerPhase = elapsed / phasesDone
        return (8 - phasesDone) * avgPerPhase
    }

    fun getResults(): List<PhaseResult> = completedResults.toList()

    fun generateBootReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 内核启动报告 ═══")
        sb.appendLine("总阶段: ${BootPhase.values().size}")
        sb.appendLine("已完成: ${completedResults.size}")
        sb.appendLine("状态: ${if (_progress.value.isComplete) "✓ 完成" else if (_progress.value.isFailed) "✗ 失败" else "○ 进行中"}")
        sb.appendLine()
        sb.appendLine("阶段详情:")
        completedResults.forEach { r ->
            val icon = if (r.success) "✓" else "✗"
            sb.appendLine("  $icon ${r.phase.description} (${r.durationMs}ms)")
            if (r.error != null) sb.appendLine("       错误: ${r.error}")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
