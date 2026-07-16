package com.apex.agent.core.tools.skill

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class SkillTaskQueue {

    companion object {
        private const val DEFAULT_MAX_QUEUE_SIZE = 100
        private const val MIN_PRIORITY = 0
        private const val MAX_PRIORITY = 10
        private const val NORMAL_PRIORITY = 5
    }



        companion object {
            fun fromValue(value: Int): Priority {
                return entries.minByOrNull { kotlin.math.abs(it.value - value) } ?: NORMAL
            }
        }
    }

    data class SkillTask(
        val id: String,
        val skillName: String,
        val parameters: Map<String, Any> = emptyMap(),
        val priority: Priority = Priority.NORMAL,
        val createdAt: Long = System.currentTimeMillis(),
        val timeoutMs: Long = 30000L
    )


    data class QueueStats(
        val totalEnqueued: Long,
        val totalDequeued: Long,
        val totalCompleted: Long,
        val totalFailed: Long,
        val currentSize: Int,
        val peakSize: Int,
        val averageWaitTimeMs: Long,
        val averageExecutionTimeMs: Long,
        val throughput: Float
    )

    private val fifoQueue = ArrayDeque<SkillTask>()
    private val priorityQueue = PriorityBlockingQueue<SkillTask>(11) { a, b ->
        b.priority.value.compareTo(a.priority.value)
    }
    private val taskStates = ConcurrentHashMap<String, TaskState>()
    private val taskResults = ConcurrentHashMap<String, TaskResult>()
    private val taskStartTimes = ConcurrentHashMap<String, Long>()
    private val pendingTasks = ConcurrentHashMap<String, SkillTask>()

    private val enqueuedCount = AtomicLong(0)
    private val dequeuedCount = AtomicLong(0)
    private val completedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val peakQueueSize = AtomicLong(0)
    private val totalWaitTime = AtomicLong(0)
    private val totalExecutionTime = AtomicLong(0)

    private val maxQueueSize: Int
    private val usePriorityQueue: Boolean

    private val lock = Any()

    constructor(maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE, usePriorityQueue: Boolean = true) {
        this.maxQueueSize = maxQueueSize
        this.usePriorityQueue = usePriorityQueue
    }

    fun enqueue(task: SkillTask): Boolean {
        synchronized(lock) {
            val currentSize = pendingTasks.size
            if (currentSize >= maxQueueSize) {
                return false
            }

            pendingTasks[task.id] = task
            taskStates[task.id] = TaskState.PENDING

            if (usePriorityQueue && task.priority != Priority.NORMAL) {
                priorityQueue.offer(task)
            } else {
                fifoQueue.addLast(task)
            }

            enqueuedCount.incrementAndGet()
            updatePeakSize()

            return true
        }
    }

    fun enqueueAll(tasks: List<SkillTask>): Int {
        var successCount = 0
        for (task in tasks) {
            if (enqueue(task)) {
                successCount++
            }
        }
        return successCount
    }

    fun dequeue(): SkillTask? {
        synchronized(lock) {
            val task = if (usePriorityQueue) {
                priorityQueue.poll()
            } else {
                fifoQueue.removeFirstOrNull()
            }

            if (task != null) {
                pendingTasks.remove(task.id)
                dequeuedCount.incrementAndGet()
                val waitTime = System.currentTimeMillis() - task.createdAt
                totalWaitTime.addAndGet(waitTime)
            }

            return task
        }
    }

    fun peek(): SkillTask? {
        synchronized(lock) {
            return if (usePriorityQueue) {
                priorityQueue.peek()
            } else {
                fifoQueue.firstOrNull()
            }
        }
    }

    fun getTaskState(taskId: String): TaskState? {
        return taskStates[taskId]
    }

    fun setTaskState(taskId: String, state: TaskState) {
        taskStates[taskId] = state
    }

    fun setTaskRunning(taskId: String) {
        taskStates[taskId] = TaskState.RUNNING
        taskStartTimes[taskId] = System.currentTimeMillis()
    }

    fun setTaskCompleted(taskId: String, result: Any) {
        taskStates[taskId] = TaskState.COMPLETED
        completedCount.incrementAndGet()

        val startTime = taskStartTimes[taskId] ?: System.currentTimeMillis()
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        totalExecutionTime.addAndGet(duration)

        taskResults[taskId] = TaskResult(
            taskId = taskId,
            skillName = pendingTasks[taskId]?.skillName ?: "",
            success = true,
            result = result,
            startTime = startTime,
            endTime = endTime,
            durationMs = duration
        )
    }

    fun setTaskFailed(taskId: String, error: String) {
        taskStates[taskId] = TaskState.FAILED
        failedCount.incrementAndGet()

        val startTime = taskStartTimes[taskId] ?: System.currentTimeMillis()
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        totalExecutionTime.addAndGet(duration)

        taskResults[taskId] = TaskResult(
            taskId = taskId,
            skillName = pendingTasks[taskId]?.skillName ?: "",
            success = false,
            error = error,
            startTime = startTime,
            endTime = endTime,
            durationMs = duration
        )
    }

    fun cancelTask(taskId: String): Boolean {
        synchronized(lock) {
            val state = taskStates[taskId]
            if (state == TaskState.PENDING) {
                taskStates[taskId] = TaskState.CANCELLED
                pendingTasks.remove(taskId)
                return true
            }
            return false
        }
    }

    fun getTaskResult(taskId: String): TaskResult? {
        return taskResults[taskId]
    }

    fun getPendingTasks(): List<SkillTask> {
        return pendingTasks.values.toList()
    }

    fun getTasksByState(state: TaskState): List<SkillTask> {
        return taskStates.filter { it.value == state }.keys.mapNotNull { pendingTasks[it] }
    }

    fun clear() {
        synchronized(lock) {
            fifoQueue.clear()
            priorityQueue.clear()
            pendingTasks.clear()
            taskStates.clear()
            taskStartTimes.clear()
        }
    }

    fun size(): Int {
        return pendingTasks.size
    }

    fun isEmpty(): Boolean {
        return pendingTasks.isEmpty()
    }

    fun isFull(): Boolean {
        return pendingTasks.size >= maxQueueSize
    }

    fun getStats(): QueueStats {
        val totalProcessed = completedCount.get() + failedCount.get()
        val avgWaitTime = if (dequeuedCount.get() > 0) {
            totalWaitTime.get() / dequeuedCount.get()
        } else 0L

        val avgExecTime = if (totalProcessed > 0) {
            totalExecutionTime.get() / totalProcessed
        } else 0L

        val elapsedSeconds = max(1L, (System.currentTimeMillis() - getEarliestTaskTime()) / 1000)
        val throughput = completedCount.get().toFloat() / elapsedSeconds

        return QueueStats(
            totalEnqueued = enqueuedCount.get(),
            totalDequeued = dequeuedCount.get(),
            totalCompleted = completedCount.get(),
            totalFailed = failedCount.get(),
            currentSize = pendingTasks.size,
            peakSize = peakQueueSize.get().toInt(),
            averageWaitTimeMs = avgWaitTime,
            averageExecutionTimeMs = avgExecTime,
            throughput = throughput
        )
    }

    fun resetStats() {
        enqueuedCount.set(0)
        dequeuedCount.set(0)
        completedCount.set(0)
        failedCount.set(0)
        totalWaitTime.set(0)
        totalExecutionTime.set(0)
    }

    private fun updatePeakSize() {
        val currentSize = pendingTasks.size
        while (currentSize > peakQueueSize.get()) {
            peakQueueSize.compareAndSet(peakQueueSize.get(), currentSize.toLong())
        }
    }

    private fun getEarliestTaskTime(): Long {
        return pendingTasks.values.minOfOrNull { it.createdAt } ?: System.currentTimeMillis()
    }

    data class MonitoringInfo(
        val queueSize: Int,
        val priorityDistribution: Map<Priority, Int>,
        val stateDistribution: Map<TaskState, Int>,
        val estimatedWaitTimeMs: Long,
        val isHealthy: Boolean
    )

    fun getMonitoringInfo(): MonitoringInfo {
        val priorityDist = Priority.entries.associateWith { priority ->
            pendingTasks.values.count { it.priority == priority }
        }

        val stateDist = TaskState.entries.associateWith { state ->
            taskStates.values.count { it == state }
        }

        val estimatedWait = if (pendingTasks.isNotEmpty()) {
            val avgTaskDuration = if (totalExecutionTime.get() > 0 && completedCount.get() > 0) {
                totalExecutionTime.get() / completedCount.get()
            } else 1000L
            pendingTasks.size * avgTaskDuration
        } else 0L

        val isHealthy = pendingTasks.size < maxQueueSize && taskStates.values.none { it == TaskState.FAILED }

        return MonitoringInfo(
            queueSize = pendingTasks.size,
            priorityDistribution = priorityDist,
            stateDistribution = stateDist,
            estimatedWaitTimeMs = estimatedWait,
            isHealthy = isHealthy
        )
    }
}