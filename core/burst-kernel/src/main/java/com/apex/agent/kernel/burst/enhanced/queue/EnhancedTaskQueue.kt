package com.apex.agent.kernel.burst.enhanced.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * B21: 任务队列优先级策略
 *
 * 增强现有 TaskQueue：
 * - 6 种优先级策略
 * - 动态优先级调整
 * - 优先级老化（防饥饿）
 * - 公平共享
 */
class EnhancedTaskQueue(
    private val maxSize: Int = 1000,
    private val agingIntervalMs: Long = 60_000L,
    private val agingBoost: Int = 5
) {

    enum class PriorityStrategy {
        STRICT_PRIORITY,     // 严格优先级
        AGING,               // 老化（防饥饿）
        FAIR_SHARE,          // 公平共享
        WEIGHTED_FAIR,       // 加权公平
        DEADLINE_FIRST,      // 截止时间优先
        ADAPTIVE             // 自适应
    }

    data class QueuedTask(
        val taskId: String,
        val description: String,
        val originalPriority: Int,
        val currentPriority: Int,
        val enqueuedAt: Long,
        val deadline: Long?,
        val skillId: String,
        val userId: String = "default",
        val category: String = "default",
        val estimatedDurationMs: Long = 5000L,
        val isPreemptable: Boolean = true
    ) : Comparable<QueuedTask> {
        override fun compareTo(other: QueuedTask): Int {
            // 优先级高的先（降序）
            if (currentPriority != other.currentPriority) {
                return other.currentPriority - currentPriority
            }
            // 同优先级：deadline 紧的先
            if (deadline != null && other.deadline != null) {
                return deadline.compareTo(other.deadline)
            }
            // 同优先级：入队早的先（FIFO）
            return enqueuedAt.compareTo(other.enqueuedAt)
        }
    }

    data class QueueStats(
        val totalEnqueued: Int,
        val totalDequeued: Int,
        val currentSize: Int,
        val avgWaitTimeMs: Long,
        val maxSize: Int,
        val byCategory: Map<String, Int>,
        val byPriority: Map<Int, Int>
    )

    private val queue = PriorityBlockingQueue<QueuedTask>(maxSize)
    private val allTasks = ConcurrentHashMap<String, QueuedTask>()
    private val dequeueCount = AtomicInteger(0)
    private val waitTimes = mutableListOf<Long>()
    private val categoryStats = ConcurrentHashMap<String, AtomicInteger>()
    private val priorityStats = ConcurrentHashMap<Int, AtomicInteger>()
    private var strategy = PriorityStrategy.AGING
    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    /**
     * 入队
     */
    fun enqueue(task: QueuedTask): Boolean {
        if (queue.size >= maxSize) return false
        val adjusted = applyStrategy(task)
        queue.add(adjusted)
        allTasks[task.taskId] = adjusted
        categoryStats.computeIfAbsent(task.category) { AtomicInteger(0) }.incrementAndGet()
        priorityStats.computeIfAbsent(task.originalPriority) { AtomicInteger(0) }.incrementAndGet()
        _size.value = queue.size
        return true
    }

    /**
     * 出队
     */
    fun dequeue(): QueuedTask? {
        val task = queue.poll()
        if (task != null) {
            allTasks.remove(task.taskId)
            dequeueCount.incrementAndGet()
            val waitTime = System.currentTimeMillis() - task.enqueuedAt
            synchronized(waitTimes) {
                waitTimes.add(waitTime)
                while (waitTimes.size > 100) waitTimes.removeAt(0)
            }
            _size.value = queue.size
        }
        return task
    }

    /**
     * 查看队首（不出队）
     */
    fun peek(): QueuedTask? = queue.peek()

    /**
     * 取消任务
     */
    fun cancel(taskId: String): Boolean {
        val task = allTasks.remove(taskId) ?: return false
        queue.remove(task)
        _size.value = queue.size
        return true
    }

    /**
     * 获取队列中所有任务
     */
    fun getAllTasks(): List<QueuedTask> = queue.toList().sorted()

    /**
     * 按分类获取
     */
    fun getTasksByCategory(category: String): List<QueuedTask> =
        queue.filter { it.category == category }

    /**
     * 设置策略
     */
    fun setStrategy(newStrategy: PriorityStrategy) {
        strategy = newStrategy
    }

    /**
     * 优先级老化（定时调用）
     */
    fun applyAging() {
        if (strategy != PriorityStrategy.AGING && strategy != PriorityStrategy.ADAPTIVE) return
        val now = System.currentTimeMillis()
        val toRequeue = mutableListOf<QueuedTask>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            val waitMs = now - task.enqueuedAt
            if (waitMs > agingIntervalMs) {
                val agedPriority = task.currentPriority + agingBoost
                val aged = task.copy(currentPriority = agedPriority)
                iterator.remove()
                toRequeue.add(aged)
            }
        }
        toRequeue.forEach { queue.add(it) }
    }

    /**
     * 动态调整优先级
     */
    fun adjustPriority(taskId: String, newPriority: Int): Boolean {
        val task = allTasks[taskId] ?: return false
        queue.remove(task)
        val adjusted = task.copy(currentPriority = newPriority)
        queue.add(adjusted)
        allTasks[taskId] = adjusted
        return true
    }

    /**
     * 获取统计
     */
    fun getStats(): QueueStats {
        val avgWait = synchronized(waitTimes) {
            if (waitTimes.isNotEmpty()) waitTimes.average().toLong() else 0L
        }
        return QueueStats(
            totalEnqueued = allTasks.size + dequeueCount.get(),
            totalDequeued = dequeueCount.get(),
            currentSize = queue.size,
            avgWaitTimeMs = avgWait,
            maxSize = maxSize,
            byCategory = categoryStats.mapValues { it.value.get() },
            byPriority = priorityStats.mapValues { it.value.get() }
        )
    }

    /**
     * 清空
     */
    fun clear() {
        queue.clear()
        allTasks.clear()
        _size.value = 0
    }

    /**
     * 应用策略
     */
    private fun applyStrategy(task: QueuedTask): QueuedTask {
        return when (strategy) {
            PriorityStrategy.STRICT_PRIORITY -> task
            PriorityStrategy.AGING -> task  // 老化在 applyAging 中处理
            PriorityStrategy.FAIR_SHARE -> {
                // 公平共享：按 category 轮转
                val catCount = categoryStats[task.category]?.get() ?: 0
                val adjustedPriority = task.originalPriority - (catCount / 5)
                task.copy(currentPriority = adjustedPriority.coerceAtLeast(1))
            }
            PriorityStrategy.WEIGHTED_FAIR -> {
                // 加权公平：按 userId 权重
                val weight = if (task.userId == "premium") 1.5f else 1.0f
                task.copy(currentPriority = (task.originalPriority * weight).toInt())
            }
            PriorityStrategy.DEADLINE_FIRST -> {
                // deadline 紧的提优先级
                if (task.deadline != null) {
                    val timeLeft = task.deadline - System.currentTimeMillis()
                    if (timeLeft < 30_000) {
                        task.copy(currentPriority = task.originalPriority + 50)
                    } else if (timeLeft < 60_000) {
                        task.copy(currentPriority = task.originalPriority + 20)
                    } else task
                } else task
            }
            PriorityStrategy.ADAPTIVE -> {
                // 自适应：综合 aging + deadline
                val agingBoost = ((System.currentTimeMillis() - task.enqueuedAt) / agingIntervalMs).toInt() * agingBoost
                val deadlineBoost = if (task.deadline != null) {
                    val timeLeft = task.deadline - System.currentTimeMillis()
                    when {
                        timeLeft < 30_000 -> 50
                        timeLeft < 60_000 -> 20
                        else -> 0
                    }
                } else 0
                task.copy(currentPriority = task.originalPriority + agingBoost + deadlineBoost)
            }
        }
    }
}
