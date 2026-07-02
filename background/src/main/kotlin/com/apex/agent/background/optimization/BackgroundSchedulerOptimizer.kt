package com.apex.agent.background.optimization

import android.content.Context
import android.os.PowerManager
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

enum class TaskPriority(val level: Int) {
    CRITICAL(100), HIGH(75), MEDIUM(50), LOW(25), BACKGROUND(10)
}

enum class TaskCategory(val weight: Double) {
    NETWORK(1.0), CPU(2.0), IO(0.5), MEMORY(1.5), STORAGE(0.3)
}

data class OptimizedTask(
    val id: String,
    val name: String,
    val priority: TaskPriority,
    val category: TaskCategory,
    val estimatedDurationMs: Long = 1000L,
    val deadlineMs: Long? = null,
    val requiresCharging: Boolean = false,
    val requiresIdle: Boolean = false,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val tags: Set<String> = emptySet()
)

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    val exponentialFactor: Double = 2.0
)

data class TaskExecutionReport(
    val taskId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val success: Boolean,
    val attempts: Int,
    val errorMessage: String? = null,
    val cpuUsagePercent: Double = 0.0,
    val memoryBytes: Long = 0L
)

data class BatchExecutionPlan(
    val batchId: String,
    val tasks: List<OptimizedTask>,
    val parallelLevel: Int = 2,
    val estimatedDurationMs: Long,
    val shouldDelayUntil: Long? = null
)

data class SchedulerStats(
    val tasksQueued: Long,
    val tasksCompleted: Long,
    val tasksFailed: Long,
    val totalExecutionTimeMs: Long,
    val averageWaitTimeMs: Double,
    val activeWorkers: Int,
    val pendingTasks: Int,
    val throughputPerMinute: Double
)

data class PerformanceSnapshot(
    val timestampMs: Long,
    val activeTaskCount: Int,
    val avgCpuLoad: Double,
    val availableMemoryBytes: Long,
    val batteryLevelPercent: Float,
    val isCharging: Boolean,
    val networkAvailable: Boolean
)

data class WindowedMetrics(
    val windowSizeMs: Long = 60000L,
    val averageLatencyMs: Double = 0.0,
    val p50LatencyMs: Long = 0L,
    val p95LatencyMs: Long = 0L,
    val p99LatencyMs: Long = 0L,
    val errorRate: Double = 0.0,
    val throughput: Double = 0.0
)

data class TaskDependency(
    val taskId: String,
    val dependsOn: Set<String> = emptySet(),
    val dependencyType: DependencyType = DependencyType.HARD
)

enum class DependencyType { HARD, SOFT, TRIGGER }

class BackgroundSchedulerOptimizer private constructor() {

    private val taskQueue = PriorityTaskQueue()
    private val runningTasks = ConcurrentHashMap<String, Job>()
    private val taskHistory = CopyOnWriteArrayList<TaskExecutionReport>()
    private val workerPool = WorkerPool()
    private val metricsCollector = MetricsCollector()
    private var isRunning = false
    private var schedulerScope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: BackgroundSchedulerOptimizer? = null

        fun getInstance(): BackgroundSchedulerOptimizer {
            return instance ?: synchronized(this) {
                instance ?: BackgroundSchedulerOptimizer().also { instance = it }
            }
        }

        private const val DEFAULT_PARALLELISM = 4
        private const val MAX_QUEUE_SIZE = 500
        private const val WATCHDOG_INTERVAL_MS = 5000L
    }

    fun initialize(scope: CoroutineScope) {
        if (isRunning) return
        schedulerScope = scope
        isRunning = true
        scope.launch(Dispatchers.Default) { watchdogLoop() }
        scope.launch(Dispatchers.Default) { metricsLoop() }
        workerPool.initialize(DEFAULT_PARALLELISM)
    }

    fun shutdown() {
        isRunning = false
        runningTasks.values.forEach { it.cancel() }
        runningTasks.clear()
        workerPool.shutdown()
    }

    fun enqueueTask(task: OptimizedTask): Boolean {
        if (taskQueue.size() >= MAX_QUEUE_SIZE) return false
        taskQueue.add(task)
        return true
    }

    fun enqueueBatch(tasks: List<OptimizedTask>): Int {
        var added = 0
        for (task in tasks) {
            if (enqueueTask(task)) added++
        }
        added
    }

    fun createExecutionPlan(
        tasks: List<OptimizedTask>,
        dependencies: List<TaskDependency> = emptyList()
    ): BatchExecutionPlan {
        val depMap = dependencies.associateBy { it.taskId }
        val prioritized = tasks.sortedByDescending { it.priority.level }
        val independentTasks = prioritized.filter { depMap[it.id]?.dependencyType != DependencyType.HARD }
        val grouped = independentTasks.chunked(workerPool.availableWorkers())
        val totalDuration = tasks.sumOf { it.estimatedDurationMs }
        val parallelDuration = grouped.maxOfOrNull { batch ->
            batch.sumOf { it.estimatedDurationMs }
        } ?: totalDuration
        BatchExecutionPlan(
            batchId = "batch_${System.currentTimeMillis()}",
            tasks = tasks,
            parallelLevel = min(workerPool.availableWorkers(), tasks.size),
            estimatedDurationMs = parallelDuration
        )
    }

    suspend fun executeTask(task: OptimizedTask): TaskExecutionReport {
        if (taskQueue.allIds().contains(task.id)) {
            taskQueue.remove(task.id)
        }
        val startTime = System.currentTimeMillis()
        var lastError: String? = null
        var attempt = 0
        val job = schedulerScope?.launch(Dispatchers.Default) {
            while (attempt < task.retryPolicy.maxAttempts) {
                attempt++
                try {
                    withContext(Dispatchers.IO) {
                        metricsCollector.recordTaskStart(task.id, task.category)
                        executeTaskAction(task)
                    }
                    val report = TaskExecutionReport(
                        taskId = task.id,
                        startTimeMs = startTime,
                        endTimeMs = System.currentTimeMillis(),
                        durationMs = System.currentTimeMillis() - startTime,
                        success = true,
                        attempts = attempt
                    )
                    taskHistory.add(report)
                    metricsCollector.recordTaskEnd(task.id, true)
                    return@launch report
                } catch (e: Exception) {
                    lastError = e.message
                    if (attempt < task.retryPolicy.maxAttempts) {
                        val delayMs = calculateBackoff(attempt, task.retryPolicy)
                        delay(delayMs)
                    }
                }
            }
            val report = TaskExecutionReport(
                taskId = task.id,
                startTimeMs = startTime,
                endTimeMs = System.currentTimeMillis(),
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                attempts = attempt,
                errorMessage = lastError
            )
            taskHistory.add(report)
            metricsCollector.recordTaskEnd(task.id, false)
            report
        }
        job?.join()
        taskHistory.lastOrNull { it.taskId == task.id }
            ?: TaskExecutionReport(task.id, startTime, System.currentTimeMillis(), 0, false, 0, "Job failed to start")
    }

    private suspend fun executeTaskAction(task: OptimizedTask) {
        when (task.category) {
            TaskCategory.NETWORK -> delay(task.estimatedDurationMs / 10)
            TaskCategory.CPU -> runBlocking { delay(task.estimatedDurationMs / 5) }
            TaskCategory.IO -> delay(task.estimatedDurationMs / 8)
            TaskCategory.MEMORY -> delay(task.estimatedDurationMs / 6)
            TaskCategory.STORAGE -> delay(task.estimatedDurationMs / 4)
        }
    }

    private fun calculateBackoff(attempt: Int, policy: RetryPolicy): Long {
        val delay = policy.baseDelayMs * (policy.exponentialFactor).pow(attempt - 1).toLong()
        min(delay, policy.maxDelayMs)
    }

    suspend fun processNextTask(): TaskExecutionReport? {
        val task = taskQueue.poll() ?: return null
        executeTask(task)
    }

    suspend fun processAllTasks(): List<TaskExecutionReport> {
        val reports = mutableListOf<TaskExecutionReport>()
        while (true) {
            val task = taskQueue.poll() ?: break
            val report = executeTask(task)
            reports.add(report)
        }
        reports
    }

    suspend fun processWithBatching(tasks: List<OptimizedTask>, batchSize: Int = 5) {
        tasks.chunked(batchSize).forEach { batch ->
            val deferred = batch.map { task ->
                CoroutineScope(Dispatchers.Default).async {
                    executeTask(task)
                }
            }
            deferred.awaitAll()
        }
    }

    fun getStats(): SchedulerStats {
        val completed = taskHistory.count { it.success }
        val failed = taskHistory.count { !it.success }
        val totalTime = taskHistory.sumOf { it.durationMs }
        val waitTimes = taskHistory.map { it.startTimeMs }
        val avgWait = if (waitTimes.isNotEmpty()) {
            waitTimes.zipWithNext { a, b -> b - a }.average()
        } else 0.0
        SchedulerStats(
            tasksQueued = metricsCollector.totalTasksQueued(),
            tasksCompleted = completed.toLong(),
            tasksFailed = failed.toLong(),
            totalExecutionTimeMs = totalTime,
            averageWaitTimeMs = avgWait,
            activeWorkers = workerPool.availableWorkers(),
            pendingTasks = taskQueue.size(),
            throughputPerMinute = metricsCollector.throughputPerMinute()
        )
    }

    fun getMetrics(): WindowedMetrics {
        metricsCollector.computeWindowedMetrics()
    }

    fun getPendingTasks(): List<OptimizedTask> {
        taskQueue.allTasks()
    }

    fun cancelTask(taskId: String): Boolean {
        val job = runningTasks[taskId] ?: return false
        job.cancel()
        runningTasks.remove(taskId)
        true
    }

    fun cancelAll() {
        runningTasks.values.forEach { it.cancel() }
        runningTasks.clear()
        taskQueue.clear()
    }

    fun isTaskRunning(taskId: String): Boolean = runningTasks.containsKey(taskId)

    fun getHistory(limit: Int = 100): List<TaskExecutionReport> {
        taskHistory.takeLast(limit)
    }

    fun getHistoryByCategory(category: TaskCategory): List<TaskExecutionReport> {
        taskHistory.filter { report ->
            taskQueue.allTasks().any { it.id == report.taskId && it.category == category }
        }
    }

    fun getSuccessRate(): Double {
        val total = taskHistory.size
        if (total == 0) return 1.0
        taskHistory.count { it.success }.toDouble() / total
    }

    fun getFailureTrend(lastNMins: Int = 10): Double {
        val cutoff = System.currentTimeMillis() - lastNMins * 60000L
        val recent = taskHistory.filter { it.startTimeMs >= cutoff }
        if (recent.isEmpty()) return 0.0
        recent.count { !it.success }.toDouble() / recent.size
    }

    fun getAverageQueueWaitTimeMs(): Long {
        if (taskHistory.size < 2) return 0L
        val waits = taskHistory.zipWithNext { a, b -> b.startTimeMs - a.startTimeMs }
        waits.filter { it > 0 }.let { if (it.isNotEmpty()) it.average().toLong() else 0L }
    }

    fun getCategoryBreakdown(): Map<TaskCategory, Long> {
        taskHistory.groupBy { report ->
            taskQueue.allTasks().firstOrNull { it.id == report.taskId }?.category ?: TaskCategory.IO
        }.mapValues { (_, reports) -> reports.count { it.success }.toLong() }
    }

    private suspend fun watchdogLoop() {
        while (isRunning) {
            delay(WATCHDOG_INTERVAL_MS)
            val snapshot = metricsCollector.takeSnapshot()
            val pending = taskQueue.size()
            if (pending > 0 && workerPool.availableWorkers() > 0) {
                val toLaunch = min(workerPool.availableWorkers(), pending)
                repeat(toLaunch) {
                    processNextTask()
                }
            }
            if (snapshot.batteryLevelPercent < 15.0f && !snapshot.isCharging) {
                pauseNonCriticalTasks()
            }
        }
    }

    private suspend fun metricsLoop() {
        while (isRunning) {
            delay(30000L)
            metricsCollector.computeWindowedMetrics()
        }
    }

    private fun pauseNonCriticalTasks() {
        val nonCritical = taskQueue.allTasks().filter { it.priority.level < TaskPriority.HIGH.level }
        nonCritical.forEach { task ->
            taskQueue.remove(task.id)
        }
    }

    fun setParallelism(level: Int) {
        workerPool.resize(level.coerceIn(1, 16))
    }

    fun getThroughputTrend(): List<Double> {
        metricsCollector.getThroughputHistory()
    }

    fun resetMetrics() {
        taskHistory.clear()
        metricsCollector.reset()
    }

    class WorkerPool {
        private val workers = AtomicInteger(0)
        private val maxWorkers = AtomicInteger(DEFAULT_PARALLELISM)

        companion object {
            private const val DEFAULT_PARALLELISM = 4
        }

        fun initialize(count: Int) {
            maxWorkers.set(count)
            workers.set(count)
        }

        fun availableWorkers(): Int = workers.get()
        fun acquire(): Boolean = workers.updateAndGet { if (it > 0) it - 1 else it } >= 0
        fun release(): Boolean = workers.updateAndGet { if (it < maxWorkers.get()) it + 1 else it } <= maxWorkers.get()
        fun resize(count: Int) {
            maxWorkers.set(count)
            workers.set(count)
        }
        fun shutdown() { workers.set(0) }
    }

    class PriorityTaskQueue {
        private val lock = Any()
        private val queue = sortedSetOf<OptimizedTask>(compareByDescending<OptimizedTask> { it.priority.level }.thenBy { System.identityHashCode(it) })

        fun add(task: OptimizedTask) = synchronized(lock) { queue.add(task) }
        fun poll(): OptimizedTask? = synchronized(lock) { queue.firstOrNull()?.also { queue.remove(it) } }
        fun remove(id: String): Boolean = synchronized(lock) { queue.removeAll { it.id == id } }
        fun clear() = synchronized(lock) { queue.clear() }
        fun size(): Int = synchronized(lock) { queue.size }
        fun allIds(): Set<String> = synchronized(lock) { queue.map { it.id }.toSet() }
        fun allTasks(): List<OptimizedTask> = synchronized(lock) { queue.toList() }
        fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
    }

    class MetricsCollector {
        private val taskStarts = ConcurrentHashMap<String, Long>()
        private val categoryCounters = ConcurrentHashMap<TaskCategory, AtomicInteger>()
        private val successCount = AtomicInteger(0)
        private val failureCount = AtomicInteger(0)
        private val totalDuration = AtomicLong(0)
        private val throughputHistory = CopyOnWriteArrayList<Double>()
        private val latencyWindow = CopyOnWriteArrayList<Long>()
        private var lastSnapshotMs = AtomicLong(System.currentTimeMillis())

        fun recordTaskStart(id: String, category: TaskCategory) {
            taskStarts[id] = System.currentTimeMillis()
            categoryCounters.computeIfAbsent(category) { AtomicInteger(0) }.incrementAndGet()
        }

        fun recordTaskEnd(id: String, success: Boolean) {
            val start = taskStarts.remove(id) ?: return
            val duration = System.currentTimeMillis() - start
            totalDuration.addAndGet(duration)
            if (success) successCount.incrementAndGet() else failureCount.incrementAndGet()
            latencyWindow.add(duration)
            if (latencyWindow.size > 1000) {
                latencyWindow.removeAt(0)
            }
        }

        fun totalTasksQueued(): Long = categoryCounters.values.sumOf { it.get().toLong() }
        fun throughputPerMinute(): Double {
            val elapsed = System.currentTimeMillis() - lastSnapshotMs.get()
            if (elapsed <= 0) return 0.0
            (successCount.get() + failureCount.get()).toDouble() / (elapsed / 60000.0)
        }

        fun takeSnapshot(): PerformanceSnapshot = PerformanceSnapshot(
            timestampMs = System.currentTimeMillis(),
            activeTaskCount = taskStarts.size,
            avgCpuLoad = 0.5,
            availableMemoryBytes = Runtime.getRuntime().freeMemory(),
            batteryLevelPercent = 100.0f,
            isCharging = true,
            networkAvailable = true
        )

        fun computeWindowedMetrics(): WindowedMetrics {
            val sorted = latencyWindow.sorted()
            val size = sorted.size
            WindowedMetrics(
                windowSizeMs = 60000L,
                averageLatencyMs = if (size > 0) sorted.average() else 0.0,
                p50LatencyMs = if (size > 0) sorted[size / 2] else 0L,
                p95LatencyMs = if (size > 0) sorted[(size * 0.95).toInt().coerceAtMost(size - 1)] else 0L,
                p99LatencyMs = if (size > 0) sorted[(size * 0.99).toInt().coerceAtMost(size - 1)] else 0L,
                errorRate = if (totalTasksQueued() > 0) failureCount.get().toDouble() / totalTasksQueued() else 0.0,
                throughput = throughputPerMinute()
            )
        }

        fun getThroughputHistory(): List<Double> = throughputHistory.toList()
        fun reset() { taskStarts.clear(); categoryCounters.clear(); successCount.set(0); failureCount.set(0); totalDuration.set(0); throughputHistory.clear(); latencyWindow.clear() }
    }
}

private fun Double.pow(n: Int): Double {
    var result = 1.0
    repeat(n) { result *= this }
    result
}

private fun <E> sortedSetOf(comparator: Comparator<E>): java.util.TreeSet<E> = java.util.TreeSet(comparator)
