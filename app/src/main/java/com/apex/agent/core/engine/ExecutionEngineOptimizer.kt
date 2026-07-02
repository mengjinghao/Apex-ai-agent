package com.apex.agent.core.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ExecutionEngineOptimizer(private val name: String = "exec-engine") {
    data class EngineConfig(
        val maxConcurrency: Int = Runtime.getRuntime().availableProcessors() * 2,
        val queueCapacity: Int = 500,
        val defaultTimeoutMs: Long = 30000L,
        val enablePreemption: Boolean = true,
        val enableBatching: Boolean = true,
        val batchSize: Int = 50,
        val enableProfiling: Boolean = true,
        val enableCircuitBreaker: Boolean = true
    )

    data class EngineMetrics(
        val totalTasks: Long,
        val completedTasks: Long,
        val failedTasks: Long,
        val timedOutTasks: Long,
        val activeTasks: Int,
        val queuedTasks: Int,
        val averageExecutionTimeMs: Double,
        val throughputPerSecond: Double,
        val cacheHitRate: Double,
        val errorRate: Double,
        val avgQueueWaitMs: Double
    )

    data class WorkerInfo(
        val workerId: Int,
        val currentTask: String?,
        val tasksCompleted: Long,
        val totalExecutionTimeMs: Long,
        val utilizationRate: Double,
        val isActive: Boolean
    )

    private val logger = LoggerFactory.getLogger("ExecutionEngineOptimizer-$name")
    private var config = EngineConfig()
    private val taskQueue = ConcurrentLinkedQueue<TaskSubmission>()
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private val taskHistory = CopyOnWriteArrayList<TaskResult>()
    private val workerMetrics = ConcurrentHashMap<Int, WorkerMetrics>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val workerCount = Runtime.getRuntime().availableProcessors()
    private val counters = TaskCounters()

    private data class TaskSubmission(
        val id: String,
        val type: String,
        val priority: Int,
        val timeoutMs: Long,
        val action: suspend () -> Any?
    )

    data class TaskResult(
        val id: String,
        val type: String,
        val success: Boolean,
        val durationMs: Long,
        val queueWaitMs: Long,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private class TaskCounters {
        val total = AtomicLong(0)
        val completed = AtomicLong(0)
        val failed = AtomicLong(0)
        val timedOut = AtomicLong(0)
        val totalExecTimeNs = AtomicLong(0)
        val totalQueueWaitNs = AtomicLong(0)
        val execCount = AtomicInteger(0)
        val queueCount = AtomicInteger(0)
        val throughputCount = AtomicLong(0)
    }

    private class WorkerMetrics {
        val tasksCompleted = AtomicLong(0)
        val totalExecTimeMs = AtomicLong(0)
        var currentTask: String? = null
    }

    fun configure(cfg: EngineConfig) { this.config = cfg; logger.info("Engine configured: {}", cfg) }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        for (i in 0 until workerCount) {
            workerMetrics[i] = WorkerMetrics()
            val workerId = i
            scope.launch { workerLoop(workerId) }
        }
        scope.launch { metricsCollector() }
        logger.info("Execution engine started with $workerCount workers")
    }

    fun stop() {
        isRunning.set(false)
        scope.cancel()
        logger.info("Execution engine stopped")
    }

    fun submit(id: String, type: String, priority: Int = 0, timeoutMs: Long = config.defaultTimeoutMs, action: suspend () -> Any?) {
        counters.total.incrementAndGet()
        val submission = TaskSubmission(id, type, priority, timeoutMs, action)
        if (!taskQueue.offer(submission)) {
            counters.failed.incrementAndGet()
            logger.warn("Task queue full, rejected: {}", id)
        }
    }

    suspend fun submitAndWait(id: String, type: String, priority: Int = 0, timeoutMs: Long = config.defaultTimeoutMs, action: suspend () -> Any?): Any? {
        val deferred = CompletableDeferred<Any?>()
        submit(id, type, priority, timeoutMs) {
            val result = action()
            deferred.complete(result)
            result
        }
        return deferred.await()
    }

    fun cancel(id: String) {
        activeTasks[id]?.cancel()
        activeTasks.remove(id)
        taskQueue.removeIf { it.id == id }
    }

    fun cancelByType(type: String) {
        activeTasks.entries.removeAll { (_, job) -> job.cancel(); true }
        taskQueue.removeIf { it.type == type }
    }

    fun getActiveCount(): Int = activeTasks.size
    fun getQueuedCount(): Int = taskQueue.size

    fun getMetrics(): EngineMetrics {
        val c = counters
        val completed = c.completed.get()
        val failed = c.failed.get()
        val total = completed + failed
        val execCount = c.execCount.get()
        val queueCount = c.queueCount.get()
        return EngineMetrics(
            totalTasks = c.total.get(),
            completedTasks = completed,
            failedTasks = failed,
            timedOutTasks = c.timedOut.get(),
            activeTasks = activeTasks.size,
            queuedTasks = taskQueue.size,
            averageExecutionTimeMs = if (execCount > 0) c.totalExecTimeNs.get().toDouble() / execCount / 1_000_000.0 else 0.0,
            throughputPerSecond = c.throughputCount.get().toDouble() / (System.currentTimeMillis() / 1000.0).coerceAtLeast(1.0),
            cacheHitRate = 0.0,
            errorRate = if (total > 0) failed.toDouble() / total else 0.0,
            avgQueueWaitMs = if (queueCount > 0) c.totalQueueWaitNs.get().toDouble() / queueCount / 1_000_000.0 else 0.0
        )
    }

    fun getWorkerInfo(): List<WorkerInfo> {
        return workerMetrics.map { (id, m) ->
            val totalTime = m.totalExecTimeMs.get()
            val tasksDone = m.tasksCompleted.get()
            WorkerInfo(
                workerId = id,
                currentTask = m.currentTask,
                tasksCompleted = tasksDone,
                totalExecutionTimeMs = totalTime,
                utilizationRate = if (tasksDone > 0) totalTime.toDouble() / (System.currentTimeMillis()).coerceAtLeast(1) else 0.0,
                isActive = activeTasks.isNotEmpty()
            )
        }
    }

    fun getTaskHistory(type: String? = null, limit: Int = 20): List<TaskResult> {
        val filtered = if (type != null) taskHistory.filter { it.type == type } else taskHistory.toList()
        return filtered.takeLast(limit)
    }

    fun getSlowTasks(thresholdMs: Long = 5000): List<TaskResult> {
        return taskHistory.filter { it.durationMs > thresholdMs }.takeLast(20)
    }

    fun getFailedTasks(): List<TaskResult> {
        return taskHistory.filter { !it.success }.takeLast(20)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "workers" to workerCount,
        "maxConcurrency" to config.maxConcurrency,
        "active" to activeTasks.size,
        "queued" to taskQueue.size,
        "totalTasks" to counters.total.get(),
        "completed" to counters.completed.get(),
        "failed" to counters.failed.get(),
        "timedOut" to counters.timedOut.get(),
        "historySize" to taskHistory.size
    )

    fun shutdown() { stop(); taskQueue.clear(); activeTasks.clear(); taskHistory.clear() }

    private suspend fun workerLoop(workerId: Int) {
        while (isRunning.get()) {
            try {
                val submission = taskQueue.poll()
                if (submission != null) {
                    executeTask(workerId, submission)
                } else {
                    delay(10)
                }
            } catch (e: Exception) {
                if (e is CancellationException) break
                logger.warn("Worker $workerId error", e)
            }
        }
    }

    private suspend fun executeTask(workerId: Int, submission: TaskSubmission) {
        val queueStart = System.nanoTime()
        workerMetrics[workerId]?.currentTask = submission.id
        val job = scope.launch {
            val queueWait = System.nanoTime() - queueStart
            counters.totalQueueWaitNs.addAndGet(queueWait)
            counters.queueCount.incrementAndGet()
            val execStart = System.nanoTime()
            try {
                withTimeout(submission.timeoutMs) {
                    submission.action()
                }
                val execTime = System.nanoTime() - execStart
                counters.completed.incrementAndGet()
                counters.totalExecTimeNs.addAndGet(execTime)
                counters.execCount.incrementAndGet()
                counters.throughputCount.incrementAndGet()
                workerMetrics[workerId]?.tasksCompleted?.incrementAndGet()
                workerMetrics[workerId]?.totalExecTimeMs?.addAndGet(execTime / 1_000_000)
                taskHistory.add(TaskResult(submission.id, submission.type, true, execTime / 1_000_000, queueWait / 1_000_000))
                while (taskHistory.size > 1000) taskHistory.removeAt(0)
            } catch (e: TimeoutCancellationException) {
                counters.timedOut.incrementAndGet()
                counters.failed.incrementAndGet()
                taskHistory.add(TaskResult(submission.id, submission.type, false, System.nanoTime() - execStart / 1_000_000, queueWait / 1_000_000, "Timeout"))
            } catch (e: Exception) {
                counters.failed.incrementAndGet()
                taskHistory.add(TaskResult(submission.id, submission.type, false, System.nanoTime() - execStart / 1_000_000, queueWait / 1_000_000, e.message))
            } finally {
                workerMetrics[workerId]?.currentTask = null
                activeTasks.remove(submission.id)
            }
        }
        activeTasks[submission.id] = job
    }

    private suspend fun metricsCollector() {
        while (isRunning.get()) {
            delay(60000)
            counters.throughputCount.set(0)
        }
    }
}

class TaskPipelineOptimizer(private val name: String = "pipeline-opt") {
    data class PipelineStage(
        val name: String,
        val concurrency: Int = 1,
        val timeoutMs: Long = 30000L,
        val retryCount: Int = 0
    )

    data class PipelineConfig(
        val stages: List<PipelineStage>,
        val bufferSize: Int = 100,
        val errorHandling: ErrorHandling = ErrorHandling.STOP_ON_ERROR
    )

    enum class ErrorHandling { STOP_ON_ERROR, SKIP_AND_CONTINUE, RETRY_STAGE }

    private val logger = LoggerFactory.getLogger("TaskPipelineOptimizer-$name")
    private val stageMetrics = ConcurrentHashMap<String, StageMetrics>()
    private val pipelineCount = AtomicInteger(0)
    private val totalProcessed = AtomicLong(0)
    private val totalFailed = AtomicLong(0)

    private class StageMetrics {
        val processed = AtomicLong(0)
        val failed = AtomicLong(0)
        val totalTimeNs = AtomicLong(0)
        val count = AtomicInteger(0)
    }

    fun createPipeline(config: PipelineConfig): Pipeline {
        val pipeline = Pipeline(config)
        pipelineCount.incrementAndGet()
        for (stage in config.stages) {
            stageMetrics[stage.name] = StageMetrics()
        }
        return pipeline
    }

    inner class Pipeline(private val config: PipelineConfig) {
        suspend fun <T> execute(input: T, processor: (T, PipelineStage) -> T): T {
            var current = input
            for (stage in config.stages) {
                val start = System.nanoTime()
                try {
                    current = processor(current, stage)
                    recordSuccess(stage.name, System.nanoTime() - start)
                } catch (e: Exception) {
                    recordFailure(stage.name)
                    totalFailed.incrementAndGet()
                    when (config.errorHandling) {
                        ErrorHandling.STOP_ON_ERROR -> throw e
                        ErrorHandling.SKIP_AND_CONTINUE -> logger.warn("Stage '{}' failed, skipping: {}", stage.name, e.message)
                        ErrorHandling.RETRY_STAGE -> {
                            for (retry in 1..stage.retryCount) {
                                try {
                                    current = processor(current, stage)
                                    recordSuccess(stage.name, System.nanoTime() - start)
                                    break
                                } catch (retryError: Exception) {
                                    if (retry == stage.retryCount) throw retryError
                                }
                            }
                        }
                    }
                }
            }
            totalProcessed.incrementAndGet()
            return current
        }
    }

    fun getStageMetrics(stageName: String): Map<String, Any> {
        val m = stageMetrics[stageName] ?: return emptyMap()
        val cnt = m.count.get()
        return mapOf(
            "processed" to m.processed.get(),
            "failed" to m.failed.get(),
            "avgTimeMs" to if (cnt > 0) m.totalTimeNs.get().toDouble() / cnt / 1_000_000.0 else 0.0,
            "totalCount" to cnt
        )
    }

    fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "pipelines" to pipelineCount.get(),
        "totalProcessed" to totalProcessed.get(),
        "totalFailed" to totalFailed.get(),
        "stages" to stageMetrics.size
    )

    private fun recordSuccess(stage: String, timeNs: Long) {
        val m = stageMetrics[stage] ?: return
        m.processed.incrementAndGet()
        m.totalTimeNs.addAndGet(timeNs)
        m.count.incrementAndGet()
    }

    private fun recordFailure(stage: String) { stageMetrics[stage]?.failed?.incrementAndGet() }
}

class ParallelExecutor(private val name: String = "parallel-exec") {
    data class ParallelTask<T>(
        val id: String,
        val action: suspend () -> T
    )

    data class ParallelResult<T>(
        val results: Map<String, T>,
        val failures: Map<String, String>,
        val totalTimeMs: Long,
        val successCount: Int,
        val failureCount: Int
    )

    private val logger = LoggerFactory.getLogger("ParallelExecutor-$name")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val totalExecuted = AtomicLong(0)
    private val totalTimeNs = AtomicLong(0)

    suspend fun <T> executeParallel(tasks: List<ParallelTask<T>>, maxConcurrency: Int = 4): ParallelResult<T> {
        val start = System.nanoTime()
        val results = ConcurrentHashMap<String, T>()
        val failures = ConcurrentHashMap<String, String>()

        coroutineScope {
            tasks.chunked(maxConcurrency).forEach { chunk ->
                chunk.map { task ->
                    async {
                        try {
                            results[task.id] = task.action()
                        } catch (e: Exception) {
                            failures[task.id] = e.message ?: "Unknown error"
                        }
                    }
                }.awaitAll()
            }
        }

        val elapsed = System.nanoTime() - start
        totalExecuted.addAndGet(tasks.size.toLong())
        totalTimeNs.addAndGet(elapsed)

        return ParallelResult(
            results = results.toMap(),
            failures = failures.toMap(),
            totalTimeMs = elapsed / 1_000_000,
            successCount = results.size,
            failureCount = failures.size
        )
    }

    suspend fun <T> executeBatched(tasks: List<ParallelTask<T>>, batchSize: Int = 10): List<ParallelResult<T>> {
        return tasks.chunked(batchSize).map { executeParallel(it) }
    }

    suspend fun <T> executeWithTimeout(tasks: List<ParallelTask<T>>, timeoutMs: Long, maxConcurrency: Int = 4): ParallelResult<T> {
        return withTimeoutOrNull(timeoutMs) {
            executeParallel(tasks, maxConcurrency)
        } ?: ParallelResult(emptyMap(), tasks.associate { it.id to "Timeout after ${timeoutMs}ms" }, timeoutMs, 0, tasks.size)
    }

    fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "totalExecuted" to totalExecuted.get(),
        "avgTimeMs" to if (totalExecuted.get() > 0) totalTimeNs.get().toDouble() / totalExecuted.get() / 1_000_000.0 else 0.0
    )

    fun shutdown() { scope.cancel() }
}

class StreamingExecutor(private val name: String = "stream-exec") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _outputFlow = MutableSharedFlow<Any>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Any> = _outputFlow.asSharedFlow()

    private val processedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    fun <T> submit(item: T, processor: suspend (T) -> Any?) {
        scope.launch {
            try {
                val result = processor(item)
                if (result != null) {
                    _outputFlow.emit(result)
                }
                processedCount.incrementAndGet()
            } catch (e: Exception) {
                droppedCount.incrementAndGet()
            }
        }
    }

    fun <T> submitBatch(items: List<T>, processor: suspend (T) -> Any?) {
        for (item in items) {
            submit(item, processor)
        }
    }

    fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "processed" to processedCount.get(),
        "dropped" to droppedCount.get()
    )

    fun shutdown() { scope.cancel() }
}

class BatchedExecutor<T, R>(
    private val name: String = "batched-exec",
    private val batchSize: Int = 100,
    private val flushIntervalMs: Long = 1000L,
    private val processor: suspend (List<T>) -> List<R>
) {
    private val pending = ConcurrentLinkedQueue<Pair<String, T>>()
    private val deferreds = ConcurrentHashMap<String, CompletableDeferred<R>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processedCount = AtomicLong(0)
    private val batchCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    suspend fun submit(key: String, item: T): R {
        val deferred = CompletableDeferred<R>()
        deferreds[key] = deferred
        pending.add(key to item)
        if (pending.size >= batchSize) {
            flush()
        }
        return deferred.await()
    }

    suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = mutableListOf<Pair<String, T>>()
        while (batch.size < batchSize) {
            pending.poll()?.let { batch.add(it) } ?: break
        }
        if (batch.isEmpty()) return

        batchCount.incrementAndGet()
        try {
            val results = processor(batch.map { it.second })
            for ((i, r) in results.withIndex()) {
                deferreds.remove(batch[i].first)?.complete(r)
            }
            processedCount.addAndGet(batch.size.toLong())
        } catch (e: Exception) {
            failedCount.addAndGet(batch.size.toLong())
            for ((key, _) in batch) {
                deferreds.remove(key)?.completeExceptionally(e)
            }
        }
    }

    fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "batchSize" to batchSize,
        "processed" to processedCount.get(),
        "batches" to batchCount.get(),
        "failed" to failedCount.get(),
        "pending" to pending.size
    )

    fun shutdown() { scope.cancel() }
}
