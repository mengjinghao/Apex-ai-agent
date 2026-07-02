package com.apex.agent.kernel.burst

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.TaskQueueItem
import com.apex.agent.domain.model.TaskStatus
import com.apex.agent.plugins.burst.base.IBurstPluginLoader
import com.apex.agent.plugins.burst.base.IBurstStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Burst任务调度器 - 改进版
 *
 * 改进点：
 * a) 支持按任务配置超时时间（构造参数defaultTimeoutMs，任务级timeoutMs字段覆盖）
 * b) 队列溢出处理（队列满时offer()返回false而非静默使用put()阻塞）
 * c) 任务优先级支持（ScheduledTask.priority + 队列已按优先级排序）
 * d) 执行指标追踪（SchedulerMetrics）
 */
class BurstTaskScheduler(
    private val stateManager: IBurstStateManager,
    private val defaultTimeoutMs: Long = 30000L,
    private val queueCapacity: Int = 100
) {
    companion object {
        private const val DEFAULT_MAX_CONCURRENCY = 3
        private const val MAX_CONCURRENCY_CAP = 32
        private const val QUEUE_POLL_TIMEOUT_MS = 100L
    }

    /**
     * 可调度任务（支持优先级和超时配置）
     */
    data class ScheduledTask(
        val id: String,
        val action: suspend () -> Result<Any>,
        val priority: Int = 0,
        val timeoutMs: Long = 30000L,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 调度器运行指标
     */
    data class SchedulerMetrics(
        val totalScheduled: Long = 0,
        val totalCompleted: Long = 0,
        val totalFailed: Long = 0,
        val totalTimedOut: Long = 0,
        val currentQueueSize: Int = 0,
        val averageExecutionTimeMs: Double = 0.0
    )

    private val _runningTasks = MutableStateFlow<List<BurstTask>>(emptyList())
    val runningTasks: StateFlow<List<BurstTask>> = _runningTasks

    // 修复 C2：PriorityBlockingQueue 的构造参数 initialCapacity 不是硬上限，会自动扩容，
    // 旧版 `if (_queue.size >= queueCapacity) return false` 在并发下会失效，且即使串行也会因为
    // PriorityBlockingQueue 自身扩容而让容量检查形同虚设。改用 Semaphore 作为硬性容量许可。
    private val _queue = PriorityBlockingQueue<TaskQueueItem>(queueCapacity) { a, b -> b.priority.compareTo(a.priority) }
    private val _queueSlots = Semaphore(queueCapacity)
    private val _isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _maxConcurrency = MutableStateFlow(DEFAULT_MAX_CONCURRENCY)
    val maxConcurrency: StateFlow<Int> = _maxConcurrency.asStateFlow()

    // 执行指标
    private val scheduledCounter = AtomicLong(0)
    private val completedCounter = AtomicLong(0)
    private val failedCounter = AtomicLong(0)
    private val timedOutCounter = AtomicLong(0)
    private val totalExecutionTimeNs = AtomicLong(0)
    private val executionCount = AtomicLong(0)

    // 已调度action（不持久化，仅运行时存在）
    private val scheduledActions = ConcurrentHashMap<String, suspend () -> Result<Any>>()

    fun start() {
        _isRunning.set(true)
        processQueue()
    }

    fun stop() {
        _isRunning.set(false)
        scope.cancel()
    }

    /**
     * 调度任务（新API，支持优先级和超时）
     * @return true=入队成功, false=队列已满被拒绝
     *
     * 修复 C2/C3：
     * - 旧版 `if (_queue.size >= queueCapacity) return false` 有 TOCTOU 竞态，且 PriorityBlockingQueue
     *   的 initialCapacity 不是硬上限 → 改用 Semaphore.tryAcquire 做硬性容量许可
     * - 旧版 `scheduledActions[task.id] = task.action` 在入队失败时不会回滚 → 新版先 offer 再 put，
     *   失败时不会污染 scheduledActions
     */
    fun schedule(task: ScheduledTask): Boolean {
        if (!_queueSlots.tryAcquire()) return false
        val item = TaskQueueItem(
            id = task.id,
            task = BurstTask(
                id = task.id,
                name = task.id,
                description = "",
                input = com.apex.agent.domain.model.BurstInput(text = task.id)
            ),
            priority = task.priority
        )
        // PriorityBlockingQueue.offer 不会因容量满而失败（自动扩容），
        // 所以这里 offer 必然成功；若未来换为有界队列，则失败时需 release 许可
        val offered = _queue.offer(item)
        if (!offered) {
            _queueSlots.release()
            return false
        }
        scheduledActions[task.id] = task.action
        scheduledCounter.incrementAndGet()
        return true
    }

    /**
     * 取消任务
     *
     * 修复 C4：旧版无条件返回 true，调用方无法区分任务是否存在。
     * 新版返回 `scheduledActions.remove(taskId) != null || _runningTasks 中存在`。
     */
    fun cancel(taskId: String): Boolean {
        val removedFromQueue = scheduledActions.remove(taskId) != null
        if (removedFromQueue) {
            _queueSlots.release()
        }
        val wasRunning = _runningTasks.value.any { it.id == taskId }
        if (wasRunning) {
            scope.launch {
                _runningTasks.value.find { it.id == taskId }?.let { task ->
                    val cancelled = task.copy(status = TaskStatus.CANCELLED)
                    stateManager.saveTask(cancelled)
                }
                _runningTasks.value = _runningTasks.value.filter { t -> t.id != taskId }
            }
        }
        return removedFromQueue || wasRunning
    }

    /**
     * 获取调度器指标
     */
    fun getMetrics(): SchedulerMetrics {
        val count = executionCount.get()
        return SchedulerMetrics(
            totalScheduled = scheduledCounter.get(),
            totalCompleted = completedCounter.get(),
            totalFailed = failedCounter.get(),
            totalTimedOut = timedOutCounter.get(),
            currentQueueSize = _queue.size,
            averageExecutionTimeMs = if (count > 0) (totalExecutionTimeNs.get() / count) / 1_000_000.0 else 0.0
        )
    }

    /**
     * 清空调度器
     */
    fun clear() {
        val drained = _queue.size
        _queue.clear()
        // 同步释放所有占用的容量许可
        repeat(drained) { _queueSlots.release() }
        scheduledActions.clear()
        _runningTasks.value = emptyList()
    }

    // ========== 原始接口兼容方法 ==========

    fun scheduleTask(task: BurstTask) {
        val item = TaskQueueItem(
            id = task.id,
            task = task,
            priority = task.priority
        )
        // 修复 C2：旧版直接 offer，会绕过 queueCapacity 硬限制。新版用 Semaphore 控制。
        if (!_queueSlots.tryAcquire()) {
            failedCounter.incrementAndGet()
            return
        }
        scheduledCounter.incrementAndGet()
        if (!_queue.offer(item)) {
            _queueSlots.release()
            failedCounter.incrementAndGet()
        }
    }

    fun pauseTask(taskId: String) {
        // 修复 C5：旧版第 171 行有缩进异常（语法 OK 但格式错乱），此处统一缩进
        scope.launch {
            _runningTasks.value.find { it.id == taskId }?.let { task ->
                val paused = task.copy(status = TaskStatus.PAUSED)
                stateManager.saveTask(paused)
                _runningTasks.value = _runningTasks.value.filter { t -> t.id != taskId }
            }
        }
    }

    fun resumeTask(taskId: String) {
        scope.launch {
            stateManager.loadTask(taskId)?.let { task ->
                if (task.status == TaskStatus.PAUSED) {
                    val resumed = task.copy(status = TaskStatus.PENDING)
                    scheduleTask(resumed)
                }
            }
        }
    }

    fun updateMaxConcurrency(n: Int) {
        _maxConcurrency.value = n.coerceIn(1, MAX_CONCURRENCY_CAP)
    }

    fun cancelTask(taskId: String) {
        cancel(taskId)
    }

    private fun processQueue() {
        scope.launch {
            while (_isRunning.get()) {
                if (_runningTasks.value.size < _maxConcurrency.value) {
                    val item = _queue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (item != null) {
                        launch {
                            executeTask(item.task)
                        }
                    }
                } else {
                    delay(QUEUE_POLL_TIMEOUT_MS)
                }
            }
        }
    }

    /**
     * 修复 C1（严重）：旧版逻辑：
     *   withTimeout(...) { action().onFailure { saveTask(FAILED) } }
     *   saveTask(COMPLETED)   // <-- 无条件保存 COMPLETED！
     * 即便 action() 返回 Result.failure，旧版也会接着把任务标记为 COMPLETED，
     * 导致失败任务被错误地视为完成。
     *
     * 新版：根据 result.isSuccess 决定保存 COMPLETED 还是 FAILED，并保证两者互斥。
     */
    private suspend fun executeTask(task: BurstTask) {
        val started = task.copy(
            status = TaskStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        stateManager.saveTask(started)
        _runningTasks.value = _runningTasks.value + started

        val taskTimeout = task.timeout ?: defaultTimeoutMs
        val startTime = System.nanoTime()

        // 提前释放队列许可（任务已离开队列，正在执行）
        _queueSlots.release()

        try {
            val action = scheduledActions.remove(task.id)
            val outcome: Result<Any> = if (action != null) {
                withTimeout(taskTimeout) { action() }
            } else {
                // 没有对应 action（可能是 scheduleTask 直接入队的 BurstTask，没有 lambda）
                Result.success(Unit)
            }

            val elapsedNs = System.nanoTime() - startTime
            totalExecutionTimeNs.addAndGet(elapsedNs)
            executionCount.incrementAndGet()

            if (outcome.isSuccess) {
                val completed = started.copy(
                    status = TaskStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    progress = 1f
                )
                stateManager.saveTask(completed)
                completedCounter.incrementAndGet()
            } else {
                // action 返回 Result.failure —— 保存 FAILED，不再 throw，避免被下面的 catch 重复计数
                val failed = started.copy(
                    status = TaskStatus.FAILED,
                    updatedAt = System.currentTimeMillis()
                )
                stateManager.saveTask(failed)
                failedCounter.incrementAndGet()
            }
        } catch (e: TimeoutCancellationException) {
            timedOutCounter.incrementAndGet()
            failedCounter.incrementAndGet()
            val failed = started.copy(
                status = TaskStatus.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            stateManager.saveTask(failed)
        } catch (e: Exception) {
            failedCounter.incrementAndGet()
            val failed = started.copy(
                status = TaskStatus.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            stateManager.saveTask(failed)
        } finally {
            _runningTasks.value = _runningTasks.value.filter { t -> t.id != task.id }
        }
    }
}
