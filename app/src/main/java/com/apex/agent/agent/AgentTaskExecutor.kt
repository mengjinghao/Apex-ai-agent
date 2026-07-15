package com.apex.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务执行状态。
 */
sealed class TaskExecutionState {
    object Pending : TaskExecutionState()
    data class Running(val agentId: String, val startedAt: Long) : TaskExecutionState()
    data class Progress(val agentId: String, val progress: Float, val message: String? = null) : TaskExecutionState()
    data class Cancelled(val reason: String?) : TaskExecutionState()
    data class Failed(val error: String, val errorStack: String? = null) : TaskExecutionState()
    data class Completed(val result: SubTaskResult) : TaskExecutionState()
}

/**
 * 任务执行配置。
 *
 * @param timeoutMs 超时时间（毫秒），0 表示不超时
 * @param maxRetries 最大重试次数（首次执行后的额外重试）
 * @param retryDelayMs 重试间隔
 * @param backoffMultiplier 退避倍数（每次重试间隔 *= backoffMultiplier）
 * @param enableProgress 是否启用进度回调
 * @param progressIntervalMs 进度回调最小间隔（节流）
 */
data class TaskExecutionConfig(
    val timeoutMs: Long = 300_000L,
    val maxRetries: Int = 0,
    val retryDelayMs: Long = 1_000L,
    val backoffMultiplier: Float = 2.0f,
    val enableProgress: Boolean = true,
    val progressIntervalMs: Long = 500L
)

/**
 * Agent 任务执行器。
 *
 * 提供比 [TaskScheduler] 更细粒度的单任务执行控制：
 * - 实时进度回调
 * - 任务取消
 * - 可配置的重试和退避
 * - 执行状态观察
 *
 * 与 [TaskScheduler] 的关系：
 * - TaskScheduler 用于多任务编排（并行/串行/依赖）
 * - AgentTaskExecutor 用于单任务的精细执行控制
 *
 * 使用方式：
 * ```
 * val executor = AgentTaskExecutor(lifecycleManager)
 * val handle = executor.submit(agent, subTask, config) { state ->
 *     when (state) {
 *         is TaskExecutionState.Progress -> updateUI(state.progress)
 *         is TaskExecutionState.Completed -> handleResult(state.result)
 *         ...
 *     }
 * }
 *
 * // 取消任务
 * executor.cancel(handle.taskId, "User cancelled")
 * ```
 */
class AgentTaskExecutor(
    private val lifecycleManager: AgentLifecycleManager? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _taskStates = ConcurrentHashMap<String, MutableStateFlow<TaskExecutionState>>()
        private val runningJobs = ConcurrentHashMap<String, Job>()
        private val taskCounter = AtomicLong(0)

    /**
     * 提交任务执行。
     *
     * @param agent 执行任务的 Agent
     * @param task 子任务
     * @param config 执行配置
     * @param onStateUpdate 状态回调（在 IO 线程）
     * @return 任务句柄（含 taskId，可用于取消）
     */
    fun submit(
        agent: SubAgent,
        task: SubTask,
        config: TaskExecutionConfig = TaskExecutionConfig(),
        onStateUpdate: (TaskExecutionState) -> Unit = {}
    ): TaskHandle {
        val taskId = task.taskId.ifBlank {
            "exec_${taskCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        }
        val initialState = TaskExecutionState.Pending
        val stateFlow = MutableStateFlow(initialState)
        _taskStates[taskId] = stateFlow

        val job = scope.launch {
            try {
                // 等待 Agent 就绪
    val agentReady = lifecycleManager?.let { mgr ->
                    mgr.getState(agent.agentId) == null || mgr.getState(agent.agentId) == AgentLifecycleState.ACTIVE
                } ?: true

                if (!agentReady) {
                    val state = TaskExecutionState.Failed("Agent ${agent.agentId} is not active")
                    updateState(taskId, state, onStateUpdate)
                    return@launch
                }

                // 进入运行状态
    val runningState = TaskExecutionState.Running(agent.agentId, System.currentTimeMillis())
                updateState(taskId, runningState, onStateUpdate)

                // 执行（带重试）
    val result = executeWithRetry(agent, task, config, taskId, onStateUpdate)
        val finalState = if (result.success) {
                    TaskExecutionState.Completed(result)
                } else {
                    TaskExecutionState.Failed(
                        error = result.errorMessage ?: "Task failed",
                        errorStack = result.errorStack
                    )
                }
                updateState(taskId, finalState, onStateUpdate)

            } catch (e: CancellationException) {
                val state = TaskExecutionState.Cancelled("Task was cancelled")
                updateState(taskId, state, onStateUpdate)
                lifecycleManager?.notifyTaskCancelled(agent.agentId, taskId, "Cancelled by user")
            } catch (e: Exception) {
                val state = TaskExecutionState.Failed(
                    error = e.message ?: "Unknown execution error",
                    errorStack = e.stackTraceToString()
                )
                updateState(taskId, state, onStateUpdate)
            } finally {
                runningJobs.remove(taskId)
                _taskStates.remove(taskId)
            }
        }

        runningJobs[taskId] = job
        return TaskHandle(taskId = taskId, job = job, stateFlow = stateFlow.asStateFlow())
    }

    /**
     * 取消任务。
     */
    fun cancel(taskId: String, reason: String? = null): Boolean {
        val job = runningJobs[taskId] ?: return false
        return try {
            job.cancel(CancellationException(reason ?: "Cancelled"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取任务状态。
     */
    fun getTaskState(taskId: String): TaskExecutionState? = _taskStates[taskId]?.value

    /**
     * 观察任务状态。
     */
    fun observeTaskState(taskId: String): StateFlow<TaskExecutionState>? =
        _taskStates[taskId]?.asStateFlow()

    /**
     * 获取所有正在执行的任务 ID。
     */
    fun getRunningTaskIds(): Set<String> = runningJobs.keys.toSet()

    /**
     * 取消所有正在执行的任务。
     */
    fun cancelAll(reason: String? = null) {
        for ((_, job) in runningJobs) {
            try {
                job.cancel(CancellationException(reason ?: "All tasks cancelled"))
            } catch (_: Exception) {
                // 单个取消失败不影响其他
            }
        }
        runningJobs.clear()
    }

    /**
     * 关闭执行器，取消所有任务。
     */
    fun shutdown() {
        cancelAll("Executor shutting down")
        scope.cancel()
        _taskStates.clear()
    }

    // ===== 内部方法 =====
    private suspend fun executeWithRetry(
        agent: SubAgent,
        task: SubTask,
        config: TaskExecutionConfig,
        taskId: String,
        onStateUpdate: (TaskExecutionState) -> Unit
    ): SubTaskResult {
        var lastResult: SubTaskResult? = null
        var currentDelay = config.retryDelayMs

        val totalAttempts = config.maxRetries + 1
        for (attempt in 1..totalAttempts) {
            // 检查是否已取消
            kotlinx.coroutines.coroutineScope {
                ensureNotCancelled(taskId)
            }
        val result = try {
                if (config.timeoutMs > 0) {
                    withTimeoutOrNull(config.timeoutMs) {
                        agent.execute(task)
                    } ?: SubTaskResult(
                        taskId = task.taskId,
                        success = false,
                        executionTime = config.timeoutMs,
                        errorMessage = "Task timed out after ${config.timeoutMs}ms"
                    )
                } else {
                    agent.execute(task)
                }
            } catch (e: CancellationException) {
                throw e  // 重新抛出取消异常
            } catch (e: Exception) {
                SubTaskResult(
                    taskId = task.taskId,
                    success = false,
                    executionTime = 0,
                    errorMessage = e.message ?: "Execution error",
                    errorStack = e.stackTraceToString()
                )
            }

            lastResult = result

            // 成功则返回
    if (result.success) {
                return result
            }

            // 最后一次尝试不再等待
    if (attempt < totalAttempts) {
                // 通知重试
    if (config.enableProgress) {
                    val progressState = TaskExecutionState.Progress(
                        agentId = agent.agentId,
                        progress = attempt.toFloat() / totalAttempts,
                        message = "Retry $attempt/$config.maxRetries after ${currentDelay}ms"
                    )
                    updateState(taskId, progressState, onStateUpdate)
                    lifecycleManager?.notifyProgress(agent.agentId, taskId, progressState.progress, progressState.message)
                }
                delay(currentDelay)
                currentDelay = (currentDelay * config.backoffMultiplier).toLong()
            }
        }
        return lastResult ?: SubTaskResult(
            taskId = task.taskId,
            success = false,
            executionTime = 0,
            errorMessage = "Task failed after $totalAttempts attempts"
        )
    }
        private fun ensureNotCancelled(taskId: String) {
        val job = runningJobs[taskId]
        if (job != null && job.isCancelled) {
            throw CancellationException("Task $taskId was cancelled")
        }
    }
        private fun updateState(
        taskId: String,
        state: TaskExecutionState,
        onStateUpdate: (TaskExecutionState) -> Unit
    ) {
        _taskStates[taskId]?.value = state
        try {
            onStateUpdate(state)
        } catch (_: Exception) {
            // 回调失败不影响主流程
        }
    }
}

/**
 * 任务句柄。
 *
 * @param taskId 任务 ID
 * @param job 协程 Job（用于取消）
 * @param stateFlow 状态流（可观察）
 */
data class TaskHandle(
    val taskId: String,
    val job: Job,
    val stateFlow: StateFlow<TaskExecutionState>
) {
    /** 任务是否已完成（成功/失败/取消）。 */
    val isCompleted: Boolean
        get() = stateFlow.value.let {
            it is TaskExecutionState.Completed ||
            it is TaskExecutionState.Failed ||
            it is TaskExecutionState.Cancelled
        }

    /** 任务是否正在运行。 */
    val isRunning: Boolean
        get() = stateFlow.value is TaskExecutionState.Running ||
                stateFlow.value is TaskExecutionState.Progress

    /** 取消任务。 */
    fun cancel(reason: String? = null): Boolean {
        return try {
            job.cancel(CancellationException(reason ?: "Cancelled"))
            true
        } catch (_: Exception) {
            false
        }
    }
}
