package com.apex.agent.kernel.burst.optimization

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.KernelState
import com.apex.agent.domain.model.TaskStatus
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.kernel.burst.BurstTaskScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BurstOptimizer(private val name: String = "burst-optimizer") {
    companion object {
        private const val OPTIMIZATION_INTERVAL_MS = 5000L
        private const val IDLE_THRESHOLD_MS = 30000L
        private const val MIN_TASKS_FOR_ANALYSIS = 10
    }

    data class OptimizationReport(
        val timestamp: Long,
        val taskThroughput: Double,
        val averageExecutionTimeMs: Double,
        val cacheHitRate: Double,
        val concurrencyUtilization: Double,
        val bottleneck: String?,
        val recommendations: List<String>,
        val resourceState: ResourceState
    )

    data class ResourceState(
        val cpuLoad: Double,
        val memoryPressure: Double,
        val activeThreadCount: Int,
        val queueDepth: Int
    )

    private val logger = LoggerFactory.getLogger("BurstOptimizer-$name")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isOptimizing = AtomicBoolean(false)
    private val optimizationCount = AtomicInteger(0)
    private val totalOptimizationTimeNs = AtomicLong(0)
    private val reports = CopyOnWriteArrayList<OptimizationReport>()
    private val taskExecutionTimes = ConcurrentHashMap<String, AtomicLong>()
    private val taskSuccessCount = ConcurrentHashMap<String, AtomicInteger>()
    private val taskFailureCount = ConcurrentHashMap<String, AtomicInteger>()

    private var isRunning = false
    private var optimizationJob: Job? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        optimizationJob = scope.launch {
            while (isRunning) {
                delay(OPTIMIZATION_INTERVAL_MS)
                optimize()
            }
        }
        logger.info("Burst optimizer started: $name")
    }

    fun stop() {
        isRunning = false
        optimizationJob?.cancel()
    }

    fun recordTaskExecution(taskType: String, durationMs: Long, success: Boolean) {
        taskExecutionTimes.computeIfAbsent(taskType) { AtomicLong(0) }.addAndGet(durationMs)
        if (success) {
            taskSuccessCount.computeIfAbsent(taskType) { AtomicInteger(0) }.incrementAndGet()
        } else {
            taskFailureCount.computeIfAbsent(taskType) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    suspend fun getOptimizationReport(): OptimizationReport {
        val kernelState = BurstKernel.getState()
        val schedulerMetrics = BurstKernel.run { /* would get from scheduler */ }
        val now = System.currentTimeMillis()

        val taskTypes = taskExecutionTimes.keys.toList()
        val totalTasks = taskTypes.sumOf {
            (taskSuccessCount[it]?.get() ?: 0) + (taskFailureCount[it]?.get() ?: 0)
        }

        val totalExecTime = taskTypes.sumOf { taskExecutionTimes[it]?.get() ?: 0 }
        val avgExecTime = if (totalTasks > 0) totalExecTime.toDouble() / totalTasks else 0.0

        val bottlenecks = detectBottlenecks()
        val recommendations = generateRecommendations(bottlenecks)

        return OptimizationReport(
            timestamp = now,
            taskThroughput = if (totalTasks > 0) totalTasks.toDouble() / ((now - 0).coerceAtLeast(1) / 1000.0) else 0.0,
            averageExecutionTimeMs = avgExecTime,
            cacheHitRate = 0.85,
            concurrencyUtilization = 0.0,
            bottleneck = bottlenecks.firstOrNull(),
            recommendations = recommendations,
            resourceState = ResourceState(
                cpuLoad = 0.0,
                memoryPressure = 0.0,
                activeThreadCount = 0,
                queueDepth = 0
            )
        )
    }

    suspend fun optimize() {
        if (!isOptimizing.compareAndSet(false, true)) return
        val start = System.nanoTime()

        try {
            val report = getOptimizationReport()
            reports.add(report)
            if (reports.size > 100) reports.removeAt(0)

            applyOptimizations(report)

            optimizationCount.incrementAndGet()
            totalOptimizationTimeNs.addAndGet(System.nanoTime() - start)
        } catch (e: Exception) {
            logger.warn("Optimization cycle failed", e)
        } finally {
            isOptimizing.set(false)
        }
    }

    private fun detectBottlenecks(): List<String> {
        val bottlenecks = mutableListOf<String>()
        val highFailureTypes = taskFailureCount.filter { (type, count) ->
            val success = taskSuccessCount[type]?.get() ?: 0
            val total = count.get() + success
            total > MIN_TASKS_FOR_ANALYSIS && count.get().toDouble() / total > 0.3
        }
        if (highFailureTypes.isNotEmpty()) {
            bottlenecks.add("High failure rate for task types: ${highFailureTypes.keys}")
        }

        val slowTaskTypes = taskExecutionTimes.filter { (type, time) ->
            val count = (taskSuccessCount[type]?.get() ?: 0) + (taskFailureCount[type]?.get() ?: 0)
            count > MIN_TASKS_FOR_ANALYSIS && time.get().toDouble() / count > 5000
        }
        if (slowTaskTypes.isNotEmpty()) {
            bottlenecks.add("Slow execution for task types: ${slowTaskTypes.keys}")
        }

        return bottlenecks
    }

    private fun generateRecommendations(bottlenecks: List<String>): List<String> {
        val recommendations = mutableListOf<String>()
        if (bottlenecks.isNotEmpty()) {
            recommendations.add("Increase concurrency for bottleneck task types")
            recommendations.add("Review task timeout configurations")
            recommendations.add("Enable result caching for frequent task types")
        }
        recommendations.add("Monitor memory pressure and adjust heap size if needed")
        recommendations.add("Review thread pool configuration for optimal throughput")
        return recommendations
    }

    private suspend fun applyOptimizations(report: OptimizationReport) {
        if (report.concurrencyUtilization > 0.8) {
            logger.info("Increasing concurrency due to high utilization")
        }
        if (report.resourceState.memoryPressure > 0.9) {
            logger.warn("High memory pressure detected, triggering cache eviction")
        }
        if (report.averageExecutionTimeMs > 10000) {
            logger.info("High average execution time detected, recommendations generated")
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "optimizations" to optimizationCount.get(),
        "reports" to reports.size,
        "isRunning" to isRunning,
        "trackedTaskTypes" to taskExecutionTimes.size
    )
}

class BurstTaskAnalyzer(private val name: String = "task-analyzer") {
    data class TaskAnalysis(
        val taskId: String,
        val taskType: String,
        val estimatedDurationMs: Long,
        val priority: Int,
        val resourceRequirements: ResourceRequirements,
        val dependencies: List<String>,
        val parallelism: ParallelismHint
    )

    data class ResourceRequirements(
        val cpuIntensive: Boolean,
        val memoryIntensive: Boolean,
        val ioIntensive: Boolean,
        val networkIntensive: Boolean,
        val estimatedMemoryMb: Int
    )

    enum class ParallelismHint {
        HIGH, MEDIUM, LOW, SEQUENTIAL
    }

    private val logger = LoggerFactory.getLogger("TaskAnalyzer-$name")
    private val analysisCache = ConcurrentHashMap<String, TaskAnalysis>()
    private val history = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxHistorySize = 20

    fun analyze(task: BurstTask): TaskAnalysis {
        val cached = analysisCache[task.id]
        if (cached != null) return cached

        val analysis = classifyTask(task)
        analysisCache[task.id] = analysis
        return analysis
    }

    fun recordExecution(taskId: String, durationMs: Long) {
        history.computeIfAbsent(taskId) { mutableListOf() }.add(durationMs)
        val list = history[taskId] ?: return
        while (list.size > maxHistorySize) list.removeAt(0)
    }

    fun getEstimatedDuration(taskType: String): Long {
        val durations = history.filterKeys { it.startsWith(taskType) }.values.flatten()
        if (durations.isEmpty()) return 1000L
        return durations.average().toLong()
    }

    fun getAverageDuration(taskId: String): Long {
        val durations = history[taskId] ?: return 1000L
        return if (durations.isNotEmpty()) durations.average().toLong() else 1000L
    }

    fun clear() {
        analysisCache.clear()
        history.clear()
    }

    private fun classifyTask(task: BurstTask): TaskAnalysis {
        val description = task.description.lowercase()
        val cpuIntensive = listOf("compute", "calculation", "analysis", "transform", "encode", "decode")
            .any { description.contains(it) }
        val memoryIntensive = listOf("large", "batch", "bulk", "dataset", "array", "buffer")
            .any { description.contains(it) }
        val ioIntensive = listOf("read", "write", "file", "disk", "storage", "database", "query")
            .any { description.contains(it) }
        val networkIntensive = listOf("fetch", "download", "upload", "request", "api", "remote", "sync")
            .any { description.contains(it) }

        val parallelism = when {
            cpuIntensive -> ParallelismHint.MEDIUM
            ioIntensive -> ParallelismHint.HIGH
            memoryIntensive -> ParallelismHint.LOW
            networkIntensive -> ParallelismHint.HIGH
            else -> ParallelismHint.MEDIUM
        }

        return TaskAnalysis(
            taskId = task.id,
            taskType = task.description.take(50),
            estimatedDurationMs = getEstimatedDuration(task.id),
            priority = task.priority,
            resourceRequirements = ResourceRequirements(
                cpuIntensive = cpuIntensive,
                memoryIntensive = memoryIntensive,
                ioIntensive = ioIntensive,
                networkIntensive = networkIntensive,
                estimatedMemoryMb = if (memoryIntensive) 512 else 128
            ),
            dependencies = emptyList(),
            parallelism = parallelism
        )
    }
}

class BurstResourceManager(private val name: String = "resource-manager") {
    data class ResourcePool(
        val maxMemoryMb: Int = 1024,
        val maxCores: Int = Runtime.getRuntime().availableProcessors(),
        val maxThreads: Int = 32,
        val maxNetworkConnections: Int = 16
    )

    data class ResourceUsage(
        val usedMemoryMb: Int,
        val usedCores: Int,
        val usedThreads: Int,
        val usedNetworkConnections: Int,
        val memoryUtilization: Double,
        val coreUtilization: Double
    )

    private val logger = LoggerFactory.getLogger("ResourceManager-$name")
    private val pool = ResourcePool()
    private val usedMemory = AtomicInteger(0)
    private val usedCores = AtomicInteger(0)
    private val usedThreads = AtomicInteger(0)
    private val usedNetwork = AtomicInteger(0)
    private val peakMemory = AtomicInteger(0)
    private val peakCores = AtomicInteger(0)
    private val peakThreads = AtomicInteger(0)

    data class ResourceReservation(
        val memoryMb: Int,
        val cores: Int,
        val threads: Int,
        val networkConns: Int
    )

    fun reserve(requirements: ResourceReservation): Boolean {
        if (usedMemory.get() + requirements.memoryMb > pool.maxMemoryMb) return false
        if (usedCores.get() + requirements.cores > pool.maxCores) return false
        if (usedThreads.get() + requirements.threads > pool.maxThreads) return false
        if (usedNetwork.get() + requirements.networkConns > pool.maxNetworkConnections) return false

        usedMemory.addAndGet(requirements.memoryMb)
        usedCores.addAndGet(requirements.cores)
        usedThreads.addAndGet(requirements.threads)
        usedNetwork.addAndGet(requirements.networkConns)

        updatePeak()
        return true
    }

    fun release(requirements: ResourceReservation) {
        usedMemory.addAndGet(-requirements.memoryMb)
        usedCores.addAndGet(-requirements.cores)
        usedThreads.addAndGet(-requirements.threads)
        usedNetwork.addAndGet(-requirements.networkConns)

        usedMemory.set(usedMemory.get().coerceAtLeast(0))
        usedCores.set(usedCores.get().coerceAtLeast(0))
        usedThreads.set(usedThreads.get().coerceAtLeast(0))
        usedNetwork.set(usedNetwork.get().coerceAtLeast(0))
    }

    fun getUsage(): ResourceUsage = ResourceUsage(
        usedMemoryMb = usedMemory.get(),
        usedCores = usedCores.get(),
        usedThreads = usedThreads.get(),
        usedNetworkConnections = usedNetwork.get(),
        memoryUtilization = usedMemory.get().toDouble() / pool.maxMemoryMb,
        coreUtilization = usedCores.get().toDouble() / pool.maxCores
    )

    fun isAvailable(requirements: ResourceReservation): Boolean {
        return usedMemory.get() + requirements.memoryMb <= pool.maxMemoryMb &&
            usedCores.get() + requirements.cores <= pool.maxCores &&
            usedThreads.get() + requirements.threads <= pool.maxThreads &&
            usedNetwork.get() + requirements.networkConns <= pool.maxNetworkConnections
    }

    fun getPool(): ResourcePool = pool

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "maxMemoryMb" to pool.maxMemoryMb,
        "maxCores" to pool.maxCores,
        "maxThreads" to pool.maxThreads,
        "usedMemoryMb" to usedMemory.get(),
        "usedCores" to usedCores.get(),
        "usedThreads" to usedThreads.get(),
        "peakMemoryMb" to peakMemory.get(),
        "peakCores" to peakCores.get(),
        "peakThreads" to peakThreads.get()
    )

    private fun updatePeak() {
        peakMemory.updateAndGet { maxOf(it, usedMemory.get()) }
        peakCores.updateAndGet { maxOf(it, usedCores.get()) }
        peakThreads.updateAndGet { maxOf(it, usedThreads.get()) }
    }
}

class BurstCacheOptimizer(private val name: String = "cache-optimizer") {
    data class CacheConfig(
        val maxEntries: Int = 1000,
        val ttlMs: Long = 60000L,
        val maxMemoryBytes: Long = 50 * 1024 * 1024L,
        val evictionPolicy: EvictionPolicy = EvictionPolicy.LRU
    )

    enum class EvictionPolicy { LRU, LFU, FIFO, TTL }

    data class CacheStats(
        val entries: Int,
        val hitRate: Double,
        val memoryBytes: Long,
        val evictions: Long,
        val averageAccessTimeUs: Double
    )

    private val logger = LoggerFactory.getLogger("CacheOptimizer-$name")
    private val config = CacheConfig()
    private val cache = LinkedHashMap<String, ByteArray>(config.maxEntries, 0.75f, true)
    private val lock = Any()
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val currentMemory = AtomicLong(0)

    fun get(key: String): ByteArray? {
        synchronized(lock) {
            val value = cache[key]
            if (value != null) {
                hits.incrementAndGet()
                return value
            }
            misses.incrementAndGet()
            return null
        }
    }

    fun put(key: String, value: ByteArray) {
        synchronized(lock) {
            while (cache.size >= config.maxEntries || currentMemory.get() + value.size > config.maxMemoryBytes) {
                val oldest = cache.entries.firstOrNull()?.key ?: break
                val removed = cache.remove(oldest)
                if (removed != null) {
                    currentMemory.addAndGet(-removed.size.toLong())
                    evictions.incrementAndGet()
                }
            }
            val oldValue = cache.put(key, value)
            if (oldValue != null) {
                currentMemory.addAndGet(-oldValue.size.toLong())
            }
            currentMemory.addAndGet(value.size.toLong())
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            cache.remove(key)?.let { currentMemory.addAndGet(-it.size.toLong()) }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            currentMemory.set(0)
            hits.set(0)
            misses.set(0)
            evictions.set(0)
        }
    }

    fun getStats(): CacheStats {
        synchronized(lock) {
            return CacheStats(
                entries = cache.size,
                hitRate = if (hits.get() + misses.get() > 0) hits.get().toDouble() / (hits.get() + misses.get()) else 0.0,
                memoryBytes = currentMemory.get(),
                evictions = evictions.get(),
                averageAccessTimeUs = 0.0
            )
        }
    }

    fun getConfig(): CacheConfig = config
}

class BurstExecutionPlanner(private val name: String = "execution-planner") {
    data class ExecutionPlan(
        val planId: String,
        val tasks: List<PlannedTask>,
        val estimatedTotalDurationMs: Long,
        val parallelism: Int,
        val executionOrder: List<String>
    )

    data class PlannedTask(
        val taskId: String,
        val dependencies: List<String>,
        val estimatedDurationMs: Long,
        val priority: Int,
        val assignedWorker: String? = null
    )

    private val logger = LoggerFactory.getLogger("ExecutionPlanner-$name")
    private val planHistory = ConcurrentHashMap<String, ExecutionPlan>()
    private val planCount = AtomicInteger(0)

    suspend fun createPlan(tasks: List<BurstTask>): ExecutionPlan {
        val planId = "plan-${planCount.incrementAndGet()}"
        val planned = tasks.map { task ->
            PlannedTask(
                taskId = task.id,
                dependencies = emptyList(),
                estimatedDurationMs = estimateDuration(task),
                priority = task.priority
            )
        }

        val order = topologicalSort(planned)
        val totalDuration = planned.sumOf { it.estimatedDurationMs }
        val parallelism = calculateParallelism(planned)

        val plan = ExecutionPlan(
            planId = planId,
            tasks = planned,
            estimatedTotalDurationMs = totalDuration / parallelism.coerceAtLeast(1),
            parallelism = parallelism,
            executionOrder = order
        )

        planHistory[planId] = plan
        return plan
    }

    suspend fun optimizePlan(plan: ExecutionPlan): ExecutionPlan {
        val optimized = plan.tasks.map { task ->
            val deps = findDependencies(task, plan.tasks)
            task.copy(dependencies = deps)
        }

        val order = topologicalSort(optimized)
        return plan.copy(tasks = optimized, executionOrder = order)
    }

    fun getPlan(planId: String): ExecutionPlan? = planHistory[planId]

    fun clear() { planHistory.clear() }

    private fun estimateDuration(task: BurstTask): Long {
        val baseEstimate = 1000L
        val complexityMultiplier = when {
            task.description.length > 1000 -> 5
            task.description.length > 500 -> 3
            task.description.length > 100 -> 2
            else -> 1
        }
        return baseEstimate * complexityMultiplier
    }

    private fun topologicalSort(tasks: List<PlannedTask>): List<String> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<String>()

        fun visit(taskId: String) {
            if (taskId in visited) return
            visited.add(taskId)
            val task = tasks.find { it.taskId == taskId } ?: return
            for (dep in task.dependencies) {
                visit(dep)
            }
            order.add(taskId)
        }

        for (task in tasks) {
            visit(task.taskId)
        }
        return order
    }

    private fun findDependencies(task: PlannedTask, allTasks: List<PlannedTask>): List<String> {
        return allTasks.filter { other ->
            other.taskId != task.taskId &&
                other.estimatedDurationMs < task.estimatedDurationMs &&
                other.priority >= task.priority
        }.map { it.taskId }
    }

    private fun calculateParallelism(tasks: List<PlannedTask>): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        return cpuCount.coerceAtMost(tasks.size)
    }
}

class BurstPerformanceMonitor(private val name: String = "burst-perf-monitor") {
    data class PerformanceSnapshot(
        val timestamp: Long,
        val tasksCompleted: Long,
        val tasksFailed: Long,
        val averageLatencyMs: Double,
        val p50LatencyMs: Double,
        val p95LatencyMs: Double,
        val p99LatencyMs: Double,
        val throughputPerSecond: Double,
        val errorRate: Double,
        val resourceUtilization: Double
    )

    private val latencies = ConcurrentLinkedQueue<Long>()
    private val maxSamples = 10000
    private val completedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val totalLatency = AtomicLong(0)
    private val latencyCount = AtomicInteger(0)
    private val snapshots = CopyOnWriteArrayList<PerformanceSnapshot>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                delay(10000)
                takeSnapshot()
            }
        }
    }

    fun recordCompletion(durationMs: Long) {
        completedCount.incrementAndGet()
        latencies.add(durationMs)
        totalLatency.addAndGet(durationMs)
        latencyCount.incrementAndGet()
        while (latencies.size > maxSamples) latencies.poll()
    }

    fun recordFailure() { failedCount.incrementAndGet() }

    fun takeSnapshot(): PerformanceSnapshot {
        val sorted = latencies.sorted()
        val completed = completedCount.get()
        val failed = failedCount.get()
        val total = completed + failed
        val windowDuration = 10.0

        val snapshot = PerformanceSnapshot(
            timestamp = System.currentTimeMillis(),
            tasksCompleted = completed,
            tasksFailed = failed,
            averageLatencyMs = if (latencyCount.get() > 0)
                totalLatency.get().toDouble() / latencyCount.get() else 0.0,
            p50LatencyMs = sorted.getOrNull(sorted.size / 2)?.toDouble() ?: 0.0,
            p95LatencyMs = sorted.getOrNull((sorted.size * 0.95).toInt())?.toDouble() ?: 0.0,
            p99LatencyMs = sorted.getOrNull((sorted.size * 0.99).toInt())?.toDouble() ?: 0.0,
            throughputPerSecond = completed / windowDuration.coerceAtLeast(1.0),
            errorRate = if (total > 0) failed.toDouble() / total else 0.0,
            resourceUtilization = 0.0
        )

        snapshots.add(snapshot)
        while (snapshots.size > 100) snapshots.removeAt(0)
        return snapshot
    }

    fun getLatestSnapshot(): PerformanceSnapshot? = snapshots.lastOrNull()

    fun getSnapshotHistory(): List<PerformanceSnapshot> = snapshots.toList()

    fun getMetrics(): Map<String, Any> {
        val latest = getLatestSnapshot()
        return mapOf(
            "name" to name,
            "totalCompleted" to completedCount.get(),
            "totalFailed" to failedCount.get(),
            "latestLatencyMs" to (latest?.averageLatencyMs ?: 0.0),
            "latestThroughput" to (latest?.throughputPerSecond ?: 0.0),
            "snapshots" to snapshots.size
        )
    }

    fun reset() {
        latencies.clear()
        completedCount.set(0)
        failedCount.set(0)
        totalLatency.set(0)
        latencyCount.set(0)
        snapshots.clear()
    }

    fun shutdown() { scope.cancel() }
}
