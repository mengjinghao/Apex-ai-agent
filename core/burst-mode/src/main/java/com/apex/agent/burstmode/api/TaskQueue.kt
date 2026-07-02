package com.apex.agent.burstmode.api

import com.apex.agent.domain.model.BurstTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务优先级。
 *
 * 数值越大优先级越高。同优先级按提交时间 FIFO。
 */
enum class TaskPriority(val value: Int) {
    LOWEST(0),
    LOW(1),
    NORMAL(2),
    HIGH(3),
    HIGHEST(4),
    URGENT(5);

    companion object {
        val DEFAULT = NORMAL
    }
}

/**
 * 队列中的任务条目。
 *
 * @param task 任务
 * @param priority 优先级
 * @param sequence 提交序号（用于同优先级 FIFO 排序）
 * @param submittedAt 提交时间戳
 */
data class QueuedTask(
    val task: BurstTask,
    val priority: TaskPriority = TaskPriority.DEFAULT,
    internal val sequence: Long = 0,
    val submittedAt: Long = System.currentTimeMillis()
) : Comparable<QueuedTask> {
    override fun compareTo(other: QueuedTask): Int {
        // 优先级高的在前
        val priorityCmp = other.priority.value.compareTo(this.priority.value)
        if (priorityCmp != 0) return priorityCmp
        // 同优先级按提交序号（FIFO）
        return this.sequence.compareTo(other.sequence)
    }
}

/**
 * 任务队列状态快照。
 */
data class TaskQueueSnapshot(
    val pendingCount: Int,
    val completedCount: Long,
    val failedCount: Long,
    val cancelledCount: Long,
    val oldestPendingAgeMs: Long
)

/**
 * 任务队列管理器。
 *
 * 提供任务优先级排队能力。当并发度受限时，高优先级任务优先执行。
 *
 * # 特性
 *
 * - 5 级优先级（LOWEST → URGENT）
 * - 同优先级 FIFO
 * - 队列状态实时观察（StateFlow）
 * - 任务取消（按 taskId）
 * - 队列清空
 *
 * # 使用示例
 *
 * ```
 * val queue = burstMode.taskQueue
 *
 * // 入队（默认 NORMAL 优先级）
 * queue.enqueue(task)
 *
 * // 入队（高优先级）
 * queue.enqueue(task, TaskPriority.HIGH)
 *
 * // 观察队列状态
 * queue.snapshot.collect { snap ->
 *     println("待执行: ${snap.pendingCount}, 已完成: ${snap.completedCount}")
 * }
 *
 * // 取消队列中的任务
 * queue.cancel(taskId)
 *
 * // 清空队列
 * queue.clear()
 * ```
 */
interface TaskQueue {

    /**
     * 入队任务。
     *
     * @param task 任务
     * @param priority 优先级（默认 NORMAL）
     * @return 入队后的任务条目
     */
    fun enqueue(task: BurstTask, priority: TaskPriority = TaskPriority.DEFAULT): QueuedTask

    /**
     * 批量入队。
     */
    fun enqueueAll(tasks: List<BurstTask>, priority: TaskPriority = TaskPriority.DEFAULT): List<QueuedTask>

    /**
     * 出队下一个任务（阻塞直到有任务）。
     */
    suspend fun dequeue(): QueuedTask

    /**
     * 非阻塞出队。返回 null 表示队列为空。
     */
    fun poll(): QueuedTask?

    /**
     * 取消队列中的任务。
     *
     * @param taskId 任务 ID
     * @return true 取消成功，false 任务不在队列中
     */
    fun cancel(taskId: String): Boolean

    /**
     * 查看队头任务（不移除）。
     */
    fun peek(): QueuedTask?

    /**
     * 队列中的任务数。
     */
    fun pendingCount(): Int

    /**
     * 队列快照（含历史统计）。
     */
    val snapshot: StateFlow<TaskQueueSnapshot>

    /**
     * 清空队列。
     *
     * @return 被清除的任务数
     */
    fun clear(): Int
}

/**
 * 任务队列默认实现。
 *
 * 使用 [PriorityBlockingQueue] 保证线程安全和优先级排序。
 */
internal class TaskQueueImpl : TaskQueue {

    private val queue = PriorityBlockingQueue<QueuedTask>()
    private val sequenceCounter = AtomicLong(0)

    private val completedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val cancelledCount = AtomicLong(0)

    private val _snapshot = MutableStateFlow(
        TaskQueueSnapshot(0, 0, 0, 0, 0)
    )
    override val snapshot: StateFlow<TaskQueueSnapshot> = _snapshot.asStateFlow()

    override fun enqueue(task: BurstTask, priority: TaskPriority): QueuedTask {
        val entry = QueuedTask(
            task = task,
            priority = priority,
            sequence = sequenceCounter.incrementAndGet()
        )
        queue.put(entry)
        updateSnapshot()
        return entry
    }

    override fun enqueueAll(tasks: List<BurstTask>, priority: TaskPriority): List<QueuedTask> {
        return tasks.map { enqueue(it, priority) }
    }

    override suspend fun dequeue(): QueuedTask {
        // 阻塞式获取
        while (true) {
            val entry = queue.poll() ?: run {
                kotlinx.coroutines.delay(50)
                return@run null
            }
            if (entry != null) return entry
        }
    }

    override fun poll(): QueuedTask? {
        return queue.poll()?.also { updateSnapshot() }
    }

    override fun cancel(taskId: String): Boolean {
        val removed = queue.removeIf { it.task.id == taskId }
        if (removed) {
            cancelledCount.incrementAndGet()
            updateSnapshot()
        }
        return removed
    }

    override fun peek(): QueuedTask? = queue.peek()

    override fun pendingCount(): Int = queue.size

    override fun clear(): Int {
        val size = queue.size
        cancelledCount.addAndGet(size.toLong())
        queue.clear()
        updateSnapshot()
        return size
    }

    /**
     * 标记任务已完成（内部调用）。
     */
    internal fun markCompleted() {
        completedCount.incrementAndGet()
        updateSnapshot()
    }

    /**
     * 标记任务已失败（内部调用）。
     */
    internal fun markFailed() {
        failedCount.incrementAndGet()
        updateSnapshot()
    }

    private fun updateSnapshot() {
        val oldest = queue.peek()?.submittedAt ?: 0
        val age = if (oldest > 0) System.currentTimeMillis() - oldest else 0
        _snapshot.value = TaskQueueSnapshot(
            pendingCount = queue.size,
            completedCount = completedCount.get(),
            failedCount = failedCount.get(),
            cancelledCount = cancelledCount.get(),
            oldestPendingAgeMs = age
        )
    }
}
