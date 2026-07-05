package com.apex.lib.rage

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存任务存储 — CRUD + 状态流转 + 事件流。
 *
 * 职责：
 * - 维护 [RageTask] 生命周期（创建 / 查询 / 更新 / 删除）
 * - 状态机流转（PENDING → RUNNING → COMPLETED / FAILED / CANCELLED）
 * - 通过 [taskEvents] 暴露 [RageEvent] 流
 * - 统计 [RageMetrics]
 *
 * 注意：本存储为纯内存实现，不涉及文件持久化。
 * 文件持久化由 APK 侧的 `RageTaskStore`（com.apex.apk.rage.agent）负责。
 */
class RageTaskStore {

    private val tasks = ConcurrentHashMap<String, RageTask>()
    private val _taskEvents = MutableSharedFlow<RageEvent>(extraBufferCapacity = 256)
    val taskEvents: SharedFlow<RageEvent> = _taskEvents.asSharedFlow()

    private val totalTasks = AtomicLong(0L)
    private val successfulTasks = AtomicLong(0L)
    private val failedTasks = AtomicLong(0L)
    private val cancelledTasks = AtomicLong(0L)

    private val concurrencyLock = Any()
    @Volatile private var currentConcurrency = 0
    @Volatile private var peakConcurrency = 0

    // ============================================================
    // CRUD
    // ============================================================

    /**
     * 创建任务（状态 = PENDING），并发 TaskStarted 事件。
     */
    fun create(task: RageTask): RageTask {
        tasks[task.id] = task
        totalTasks.incrementAndGet()
        emit(RageEvent.TaskStarted(task.id, task.description))
        ApexLog.d(ApexSuite.ApkId.RAGE, "[RageTaskStore] created: ${task.id}")
        return task
    }

    /** 获取任务。 */
    fun get(taskId: String): RageTask? = tasks[taskId]

    /** 列出全部任务（按创建时间倒序）。 */
    fun list(): List<RageTask> = tasks.values.toList().sortedByDescending { it.createdAt }

    /**
     * 更新任务（全量替换）。
     */
    fun update(task: RageTask): RageTask {
        tasks[task.id] = task
        emit(RageEvent.TaskProgress(task.id, task.progress, null))
        return task
    }

    // ============================================================
    // 状态流转
    // ============================================================

    /**
     * 流转任务状态。
     *
     * - RUNNING → 记录 startedAt，并发 TaskProgress
     * - COMPLETED → 记录 completedAt，成功计数 +1，发 TaskCompleted
     * - FAILED → 记录 completedAt，失败计数 +1，发 TaskFailed
     * - CANCELLED → 取消计数 +1，发 TaskCancelled
     */
    fun updateStatus(
        taskId: String,
        status: RageTaskStatus,
        result: String? = null,
        errorMessage: String? = null,
        progress: Float? = null,
        agentInvocations: Int? = null,
        retryCount: Int? = null,
        durationMs: Long? = null
    ): RageTask? {
        val task = tasks[taskId] ?: return null
        val now = System.currentTimeMillis()
        val updated = task.copy(
            status = status,
            completedAt = if (status.isTerminal()) now else task.completedAt,
            startedAt = if (status == RageTaskStatus.RUNNING && task.startedAt == null) now else task.startedAt,
            result = result ?: task.result,
            errorMessage = errorMessage ?: task.errorMessage,
            progress = progress ?: task.progress,
            agentInvocations = agentInvocations ?: task.agentInvocations,
            retryCount = retryCount ?: task.retryCount,
            durationMs = durationMs ?: task.durationMs
        )
        tasks[taskId] = updated

        when (status) {
            RageTaskStatus.RUNNING -> {
                emit(RageEvent.TaskProgress(taskId, updated.progress, null))
            }
            RageTaskStatus.COMPLETED -> {
                successfulTasks.incrementAndGet()
                emit(RageEvent.TaskCompleted(taskId, true, updated.durationMs))
            }
            RageTaskStatus.FAILED -> {
                failedTasks.incrementAndGet()
                emit(RageEvent.TaskFailed(taskId, errorMessage ?: "unknown"))
            }
            RageTaskStatus.CANCELLED -> {
                cancelledTasks.incrementAndGet()
                emit(RageEvent.TaskCancelled(taskId, "cancelled by user"))
            }
            RageTaskStatus.PENDING -> { /* 无事件 */ }
        }
        return updated
    }

    /** 删除任务。 */
    fun delete(taskId: String): Boolean = tasks.remove(taskId) != null

    /** 清空全部任务。 */
    fun clear(): Int {
        val n = tasks.size
        tasks.clear()
        return n
    }

    // ============================================================
    // 指标
    // ============================================================

    /** 获取指标快照。 */
    fun getMetrics(): RageMetrics {
        val total = totalTasks.get()
        val success = successfulTasks.get()
        val failed = failedTasks.get()
        val cancelled = cancelledTasks.get()
        val finished = success + failed
        return RageMetrics(
            totalTasks = total,
            successfulTasks = success,
            failedTasks = failed,
            cancelledTasks = cancelled,
            averageExecutionTimeMs = 0.0, // 由引擎在完成时累加
            successRate = if (finished == 0L) 0.0 else success.toDouble() / finished,
            currentConcurrency = currentConcurrency,
            peakConcurrency = peakConcurrency
        )
    }

    // ============================================================
    // 并发计数（引擎内部调用）
    // ============================================================

    /** 标记任务进入执行（并发 +1）。 */
    internal fun enterRunning() {
        synchronized(concurrencyLock) {
            currentConcurrency++
            if (currentConcurrency > peakConcurrency) {
                peakConcurrency = currentConcurrency
            }
        }
    }

    /** 标记任务退出执行（并发 -1）。 */
    internal fun exitRunning() {
        synchronized(concurrencyLock) {
            if (currentConcurrency > 0) currentConcurrency--
        }
    }

    // ============================================================
    // 事件
    // ============================================================

    /** 内部发射事件。 */
    internal fun emit(event: RageEvent) {
        _taskEvents.tryEmit(event)
    }

    private fun RageTaskStatus.isTerminal(): Boolean =
        this == RageTaskStatus.COMPLETED || this == RageTaskStatus.FAILED || this == RageTaskStatus.CANCELLED
}
