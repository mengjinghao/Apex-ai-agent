package com.apex.agent.kernel.burst.enhanced.progress

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B51: 细粒度进度追踪器
 *
 * 技能执行内部的细粒度进度追踪：
 * - 多级进度（任务→步骤→子步骤）
 * - 百分比 + 描述性进度
 * - ETA 预估
 * - 进度事件流
 */
class ProgressTracker {

    data class Progress(
        val taskId: String,
        val overallProgress: Float,       // 0-1
        val currentPhase: String,
        val currentStep: String?,
        val stepProgress: Float,          // 当前步骤的进度 0-1
        val stepsCompleted: Int,
        val totalSteps: Int,
        val etaMs: Long?,
        val message: String?,
        val subProgress: Map<String, Float> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ProgressEvent(
        val taskId: String,
        val type: ProgressEventType,
        val progress: Progress,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class ProgressEventType { STARTED, PROGRESS, PHASE_CHANGE, STEP_COMPLETED, STEP_STARTED, ETA_UPDATED, COMPLETED, FAILED }

    private val taskProgress = ConcurrentHashMap<String, Progress>()
    private val taskStartTimes = ConcurrentHashMap<String, Long>()
    private val phaseHistory = ConcurrentHashMap<String, MutableList<String>>()
    private val stepHistory = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val _events = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ProgressEvent> = _events.asSharedFlow()
    private val totalStepsMap = ConcurrentHashMap<String, Int>()

    fun startTask(taskId: String, totalSteps: Int, initialPhase: String = "初始化") {
        val now = System.currentTimeMillis()
        taskStartTimes[taskId] = now
        totalStepsMap[taskId] = totalSteps
        phaseHistory[taskId] = mutableListOf(initialPhase)
        stepHistory[taskId] = mutableListOf()
        val progress = Progress(
            taskId = taskId, overallProgress = 0f,
            currentPhase = initialPhase, currentStep = null,
            stepProgress = 0f, stepsCompleted = 0, totalSteps = totalSteps,
            etaMs = null, message = "任务开始"
        )
        taskProgress[taskId] = progress
        emitEvent(taskId, ProgressEventType.STARTED, progress)
    }

    fun startStep(taskId: String, stepId: String, message: String? = null) {
        val current = taskProgress[taskId] ?: return
        stepHistory[taskId]?.add(stepId to System.currentTimeMillis())
        val updated = current.copy(
            currentStep = stepId, stepProgress = 0f,
            message = message ?: "执行步骤: $stepId"
        )
        taskProgress[taskId] = updated
        emitEvent(taskId, ProgressEventType.STEP_STARTED, updated)
    }

    fun updateStepProgress(taskId: String, stepProgress: Float, message: String? = null) {
        val current = taskProgress[taskId] ?: return
        val stepsCompleted = current.stepsCompleted
        val totalSteps = current.totalSteps.coerceAtLeast(1)
        val overall = (stepsCompleted + stepProgress.coerceIn(0f, 1f)) / totalSteps
        val eta = computeETA(taskId, overall)
        val updated = current.copy(
            stepProgress = stepProgress.coerceIn(0f, 1f),
            overallProgress = overall.coerceIn(0f, 1f),
            etaMs = eta,
            message = message ?: current.message
        )
        taskProgress[taskId] = updated
        emitEvent(taskId, ProgressEventType.PROGRESS, updated)
    }

    fun completeStep(taskId: String, stepId: String) {
        val current = taskProgress[taskId] ?: return
        val newCompleted = current.stepsCompleted + 1
        val overall = newCompleted.toFloat() / current.totalSteps.coerceAtLeast(1)
        val eta = computeETA(taskId, overall)
        val updated = current.copy(
            stepsCompleted = newCompleted,
            overallProgress = overall.coerceIn(0f, 1f),
            stepProgress = 1f,
            currentStep = null,
            etaMs = eta,
            message = "步骤 $stepId 完成"
        )
        taskProgress[taskId] = updated
        emitEvent(taskId, ProgressEventType.STEP_COMPLETED, updated)
    }

    fun changePhase(taskId: String, newPhase: String) {
        val current = taskProgress[taskId] ?: return
        phaseHistory[taskId]?.add(newPhase)
        val updated = current.copy(currentPhase = newPhase, message = "进入阶段: $newPhase")
        taskProgress[taskId] = updated
        emitEvent(taskId, ProgressEventType.PHASE_CHANGE, updated)
    }

    fun completeTask(taskId: String, success: Boolean) {
        val current = taskProgress[taskId] ?: return
        val updated = current.copy(
            overallProgress = 1f, stepProgress = 1f,
            etaMs = 0, message = if (success) "任务完成" else "任务失败"
        )
        taskProgress[taskId] = updated
        emitEvent(taskId, if (success) ProgressEventType.COMPLETED else ProgressEventType.FAILED, updated)
    }

    fun updateSubProgress(taskId: String, key: String, progress: Float) {
        val current = taskProgress[taskId] ?: return
        val updated = current.copy(subProgress = current.subProgress + (key to progress))
        taskProgress[taskId] = updated
    }

    fun getProgress(taskId: String): Progress? = taskProgress[taskId]

    fun getAllProgress(): List<Progress> = taskProgress.values.toList()

    fun getPhaseHistory(taskId: String): List<String> = phaseHistory[taskId]?.toList() ?: emptyList()

    fun generateProgressBar(taskId: String): String {
        val progress = taskProgress[taskId] ?: return "无进度"
        val barLength = 30
        val filled = (progress.overallProgress * barLength).toInt()
        val bar = "█".repeat(filled) + "░".repeat(barLength - filled)
        val percent = (progress.overallProgress * 100).toInt()
        val eta = progress.etaMs?.let { " ETA: ${it / 1000}s" } ?: ""
        return "[$bar] $percent% ${progress.currentPhase}${eta} ${progress.message ?: ""}"
    }

    private fun computeETA(taskId: String, currentProgress: Float): Long? {
        if (currentProgress <= 0.01f) return null
        val startTime = taskStartTimes[taskId] ?: return null
        val elapsed = System.currentTimeMillis() - startTime
        val totalEstimated = elapsed / currentProgress
        return (totalEstimated - elapsed).toLong().coerceAtLeast(0)
    }

    private fun emitEvent(taskId: String, type: ProgressEventType, progress: Progress) {
        _events.tryEmit(ProgressEvent(taskId, type, progress))
    }
}
