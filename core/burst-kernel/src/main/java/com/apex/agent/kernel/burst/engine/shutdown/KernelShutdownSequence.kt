package com.apex.agent.kernel.burst.engine.shutdown

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * E5: 内核关机序列
 *
 * 优雅关机，确保资源正确释放：
 * - 5 个关机阶段
 * - 超时强制关机
 * - 资源释放追踪
 * - 关机进度
 */
class KernelShutdownSequence(
    private val gracefulTimeoutMs: Long = 30_000L,
    private val forceTimeoutMs: Long = 5_000L
) {

    enum class ShutdownPhase(val order: Int, val description: String) {
        STOP_ACCEPTING(0, "停止接受新任务"),
        DRAIN_QUEUE(1, "排空任务队列"),
        SAVE_STATE(2, "保存状态"),
        RELEASE_RESOURCES(3, "释放资源"),
        COMPLETE(4, "关机完成")
    }

    data class ShutdownResult(
        val phase: ShutdownPhase,
        val success: Boolean,
        val durationMs: Long,
        val message: String,
        val resourcesReleased: List<String>
    )

    data class ShutdownProgress(
        val currentPhase: ShutdownPhase,
        val completedPhases: List<ShutdownResult>,
        val totalPhases: Int,
        val progress: Float,
        val isComplete: Boolean,
        val isForced: Boolean,
        val pendingTasks: Int
    )

    private val phaseHandlers = ConcurrentHashMap<ShutdownPhase, suspend (Long) -> ShutdownResult>()
    private val completedResults = mutableListOf<ShutdownResult>()
    private val _progress = MutableStateFlow(ShutdownProgress(ShutdownPhase.STOP_ACCEPTING, emptyList(), 5, 0f, false, false, 0))
    val progress: StateFlow<ShutdownProgress> = _progress.asStateFlow()
    private val activeTaskCount = AtomicInteger(0)

    fun registerPhaseHandler(phase: ShutdownPhase, handler: suspend (timeoutMs: Long) -> ShutdownResult) {
        phaseHandlers[phase] = handler
    }

    fun setActiveTaskCount(count: Int) {
        activeTaskCount.set(count)
    }

    /**
     * 执行优雅关机
     */
    suspend fun shutdown(force: Boolean = false): Boolean {
        val totalStart = System.currentTimeMillis()
        completedResults.clear()

        for (phase in ShutdownPhase.values()) {
            val phaseTimeout = if (force) forceTimeoutMs else gracefulTimeoutMs / ShutdownPhase.values().size
            _progress.value = ShutdownProgress(phase, completedResults.toList(), 5,
                phase.order.toFloat() / 5, false, force, activeTaskCount.get())

            val handler = phaseHandlers[phase]
            val result = if (handler != null) {
                try { handler(phaseTimeout) } catch (e: Exception) {
                    ShutdownResult(phase, false, 0, "异常: ${e.message}", emptyList())
                }
            } else {
                ShutdownResult(phase, true, 0, "${phase.description}（无处理器）", emptyList())
            }

            completedResults.add(result)
            _progress.value = ShutdownProgress(phase, completedResults.toList(), 5,
                (phase.order + 1).toFloat() / 5, phase == ShutdownPhase.COMPLETE, force, activeTaskCount.get())

            if (!result.success && !force) {
                // 优雅关机失败，切换到强制
                return shutdown(force = true)
            }
        }

        _progress.value = _progress.value.copy(isComplete = true)
        return true
    }

    fun getResults(): List<ShutdownResult> = completedResults.toList()

    fun generateShutdownReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 内核关机报告 ═══")
        sb.appendLine("强制: ${_progress.value.isForced}")
        sb.appendLine()
        completedResults.forEach { r ->
            val icon = if (r.success) "✓" else "✗"
            sb.appendLine("  $icon ${r.phase.description} (${r.durationMs}ms)")
            if (r.resourcesReleased.isNotEmpty()) {
                r.resourcesReleased.forEach { sb.appendLine("       释放: $it") }
            }
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
