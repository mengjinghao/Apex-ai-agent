package com.apex.agent.kernel.burst.enhanced.pipeline.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B36: 流水线监控器
 *
 * 实时追踪流水线状态：
 * - 步骤级进度
 * - 实时指标
 * - 瓶颈检测
 * - 异常告警
 */
class PipelineMonitor {

    data class PipelineStatus(
        val pipelineId: String,
        val name: String,
        val state: PipelineState,
        val currentStep: String?,
        val currentStepIndex: Int,
        val totalSteps: Int,
        val progress: Float,
        val startedAt: Long,
        val elapsedMs: Long,
        val estimatedRemainingMs: Long?,
        val stepDurations: Map<String, Long>,
        val errors: List<String>
    )

    enum class PipelineState { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

    data class StepStatus(
        val stepId: String,
        val state: StepState,
        val startedAt: Long?,
        val completedAt: Long?,
        val durationMs: Long?,
        val retryCount: Int,
        val error: String?
    )

    enum class StepState { WAITING, RUNNING, SUCCESS, FAILED, SKIPPED }

    data class MonitoringAlert(
        val type: AlertType,
        val severity: AlertSeverity,
        val pipelineId: String,
        val stepId: String?,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class AlertType { SLOW_STEP, HIGH_ERROR_RATE, STUCK, RESOURCE_WARNING }
    enum class AlertSeverity { INFO, WARNING, CRITICAL }

    private val pipelineStatuses = ConcurrentHashMap<String, PipelineStatus>()
    private val stepStatuses = ConcurrentHashMap<String, ConcurrentHashMap<String, StepStatus>>()
    private val alerts = mutableListOf<MonitoringAlert>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun onPipelineStarted(pipelineId: String, name: String, totalSteps: Int) {
        pipelineStatuses[pipelineId] = PipelineStatus(
            pipelineId = pipelineId, name = name,
            state = PipelineState.RUNNING, currentStep = null,
            currentStepIndex = 0, totalSteps = totalSteps,
            progress = 0f, startedAt = System.currentTimeMillis(),
            elapsedMs = 0, estimatedRemainingMs = null,
            stepDurations = emptyMap(), errors = emptyList()
        )
        _activeCount.value = pipelineStatuses.values.count { it.state == PipelineState.RUNNING }
    }

    fun onStepStarted(pipelineId: String, stepId: String, stepIndex: Int) {
        val steps = stepStatuses.computeIfAbsent(pipelineId) { ConcurrentHashMap() }
        steps[stepId] = StepStatus(stepId, StepState.RUNNING, System.currentTimeMillis(), null, null, 0, null)

        pipelineStatuses[pipelineId]?.let { status ->
            pipelineStatuses[pipelineId] = status.copy(
                currentStep = stepId, currentStepIndex = stepIndex,
                progress = stepIndex.toFloat() / status.totalSteps
            )
        }
    }

    fun onStepCompleted(pipelineId: String, stepId: String, success: Boolean, durationMs: Long) {
        val steps = stepStatuses[pipelineId] ?: return
        val step = steps[stepId] ?: return
        steps[stepId] = step.copy(
            state = if (success) StepState.SUCCESS else StepState.FAILED,
            completedAt = System.currentTimeMillis(),
            durationMs = durationMs
        )

        // 检查瓶颈
        if (durationMs > 30_000) {
            alerts.add(MonitoringAlert(
                AlertType.SLOW_STEP, AlertSeverity.WARNING,
                pipelineId, stepId, "步骤 $stepId 耗时 ${durationMs}ms，可能存在瓶颈"
            ))
        }

        pipelineStatuses[pipelineId]?.let { status ->
            val newDurations = status.stepDurations + (stepId to durationMs)
            val errors = if (!success) status.errors + "步骤 $stepId 失败" else status.errors
            pipelineStatuses[pipelineId] = status.copy(
                stepDurations = newDurations, errors = errors
            )
        }
    }

    fun onPipelineCompleted(pipelineId: String, success: Boolean) {
        pipelineStatuses[pipelineId]?.let { status ->
            pipelineStatuses[pipelineId] = status.copy(
                state = if (success) PipelineState.COMPLETED else PipelineState.FAILED,
                progress = 1f,
                elapsedMs = System.currentTimeMillis() - status.startedAt
            )
        }
        _activeCount.value = pipelineStatuses.values.count { it.state == PipelineState.RUNNING }
    }

    fun getStatus(pipelineId: String): PipelineStatus? = pipelineStatuses[pipelineId]
    fun getStepStatuses(pipelineId: String): List<StepStatus> =
        stepStatuses[pipelineId]?.values?.toList() ?: emptyList()
    fun getAlerts(): List<MonitoringAlert> = alerts.toList()
    fun getAllStatuses(): List<PipelineStatus> = pipelineStatuses.values.toList()

    fun generateDashboard(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 流水线监控仪表盘 ═══")
        sb.appendLine("活跃: ${_activeCount.value} | 总计: ${pipelineStatuses.size}")
        sb.appendLine()
        pipelineStatuses.values.take(10).forEach { status ->
            val progressBar = "█".repeat((status.progress * 20).toInt()) + "░".repeat(20 - (status.progress * 20).toInt())
            val stateIcon = when (status.state) {
                PipelineState.RUNNING -> "▶"
                PipelineState.COMPLETED -> "✓"
                PipelineState.FAILED -> "✗"
                PipelineState.PAUSED -> "⏸"
                PipelineState.CANCELLED -> "⊘"
                PipelineState.PENDING -> "○"
            }
            sb.appendLine("$stateIcon ${status.name} [$progressBar] ${(status.progress * 100).toInt()}%")
            if (status.currentStep != null) {
                sb.appendLine("  当前: ${status.currentStep} (${status.currentStepIndex + 1}/${status.totalSteps})")
            }
            if (status.errors.isNotEmpty()) {
                sb.appendLine("  ⚠ 错误: ${status.errors.size}")
            }
        }
        if (alerts.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("告警 (${alerts.size}):")
            alerts.takeLast(5).forEach { alert ->
                sb.appendLine("  [${alert.severity}] ${alert.message}")
            }
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
