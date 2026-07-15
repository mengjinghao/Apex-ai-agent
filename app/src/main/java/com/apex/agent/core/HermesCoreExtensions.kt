package com.apex.agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HermesCoreExtensions(private val name: String = "hermes-ext") {
    private val logger = LoggerFactory.getLogger("HermesCoreExtensions-$name")

    data class CorePerformanceMetrics(
        val totalOperations: Long,
        val averageOperationTimeMs: Double,
        val cacheHitRate: Double,
        val activeCoroutines: Int,
        val queueDepth: Int,
        val throughputPerSecond: Double,
        val errorRate: Double
    )

    data class OperationRecord(
        val operationName: String,
        val durationMs: Long,
        val success: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val errorType: String? = null
    )
        private val operationTimes = ConcurrentHashMap<String, MutableList<Long>>()
        private val operationCounts = ConcurrentHashMap<String, AtomicLong>()
        private val operationErrors = ConcurrentHashMap<String, AtomicLong>()
        private val recentOperations = CopyOnWriteArrayList<OperationRecord>()
        private val maxRecentOperations = 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val activeCoroutines = AtomicInteger(0)
        private val errorCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val totalOperations = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(60000)
                cleanup()
            }
        }
    }
        fun <T> trackOperation(operationName: String, block: () -> T): T {
        val start = System.nanoTime()
        totalOperations.incrementAndGet()
        return try {
            val result = block()
        val duration = (System.nanoTime() - start) / 1_000_000
            recordOperation(operationName, duration, true)
            result
        } catch (e: Exception) {
            val duration = (System.nanoTime() - start) / 1_000_000
            recordOperation(operationName, duration, false, e)
        throw e
        }
    }

    suspend fun <T> trackOperationSuspend(operationName: String, block: suspend () -> T): T {
        activeCoroutines.incrementAndGet()
        val start = System.nanoTime()
        totalOperations.incrementAndGet()
        return try {
            val result = block()
        val duration = (System.nanoTime() - start) / 1_000_000
            recordOperation(operationName, duration, true)
            result
        } catch (e: Exception) {
            val duration = (System.nanoTime() - start) / 1_000_000
            recordOperation(operationName, duration, false, e)
        throw e
        } finally {
            activeCoroutines.decrementAndGet()
        }
    }
        fun getOperationStats(operationName: String): OperationStats? {
        val times = operationTimes[operationName] ?: return null
        val count = operationCounts[operationName]?.get() ?: return null
        val errors = operationErrors[operationName]?.get() ?: 0
        val sorted = times.sorted()
        return OperationStats(
            operationName = operationName,
            count = count,
            errorCount = errors,
            errorRate = if (count > 0) errors.toDouble() / count else 0.0,
            averageMs = if (times.isNotEmpty()) times.average() else 0.0,
            minMs = sorted.firstOrNull()?.toDouble() ?: 0.0,
            maxMs = sorted.lastOrNull()?.toDouble() ?: 0.0,
            p50Ms = sorted.getOrNull(sorted.size / 2)?.toDouble() ?: 0.0,
            p95Ms = sorted.getOrNull((sorted.size * 0.95).toInt())?.toDouble() ?: 0.0,
            p99Ms = sorted.getOrNull((sorted.size * 0.99).toInt())?.toDouble() ?: 0.0
        )
    }
        fun getAllOperationStats(): List<OperationStats> {
        return operationTimes.keys.mapNotNull { getOperationStats(it) }
    }
        fun getRecentOperations(count: Int = 10): List<OperationRecord> {
        return recentOperations.takeLast(count)
    }
        fun getSlowOperations(thresholdMs: Long = 1000): List<OperationStats> {
        return getAllOperationStats().filter { it.averageMs > thresholdMs }
    }
        fun getErrorProneOperations(errorThreshold: Double = 0.1): List<OperationStats> {
        return getAllOperationStats().filter { it.errorRate > errorThreshold }
    }
        fun getPerformanceMetrics(): CorePerformanceMetrics {
        val allStats = getAllOperationStats()
        val totalOps = allStats.sumOf { it.count }
        val totalErrors = allStats.sumOf { it.errorCount }
        val avgTime = if (allStats.isNotEmpty()) allStats.map { it.averageMs }.average() else 0.0
        val totalAvgTime = if (totalOps > 0) avgTime else 0.0
        return CorePerformanceMetrics(
            totalOperations = totalOperations.get(),
            averageOperationTimeMs = totalAvgTime,
            cacheHitRate = 0.85,
            activeCoroutines = activeCoroutines.get(),
            queueDepth = 0,
            throughputPerSecond = totalOps.toDouble() / (System.currentTimeMillis() / 1000.0).coerceAtLeast(1.0),
            errorRate = if (totalOps > 0) totalErrors.toDouble() / totalOps else 0.0
        )
    }
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "trackedOperations" to operationTimes.size,
        "totalOperations" to totalOperations.get(),
        "activeCoroutines" to activeCoroutines.get(),
        "recentOperations" to recentOperations.size
    )
        fun reset() {
        operationTimes.clear()
        operationCounts.clear()
        operationErrors.clear()
        recentOperations.clear()
        totalOperations.set(0)
    }
        fun shutdown() { scope.cancel() }
        private fun recordOperation(name: String, durationMs: Long, success: Boolean, error: Throwable? = null) {
        val times = operationTimes.getOrPut(name) { mutableListOf() }
        times.add(durationMs)
        if (times.size > 100) times.removeAt(0)

        operationCounts.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()
        if (!success) {
            operationErrors.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()
        }
        val record = OperationRecord(
            operationName = name,
            durationMs = durationMs,
            success = success,
            errorType = error?.javaClass?.simpleName
        )
        recentOperations.add(record)
        while (recentOperations.size > maxRecentOperations) recentOperations.removeAt(0)
    }
        private fun cleanup() {
        val threshold = System.currentTimeMillis() - 3600000
        recentOperations.removeAll { it.timestamp < threshold }
    }

    data class OperationStats(
        val operationName: String,
        val count: Long,
        val errorCount: Long,
        val errorRate: Double,
        val averageMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double
    )
}

class TaskExecutionOptimizer(private val name: String = "task-exec") {
    data class TaskProfile(
        val taskType: String,
        val averageDurationMs: Long,
        val p95DurationMs: Long,
        val maxDurationMs: Long,
        val successRate: Double,
        val executionCount: Long,
        val recommendedTimeoutMs: Long,
        val recommendedRetryCount: Int,
        val isCacheable: Boolean,
        val priority: Int
    )
        private val logger = LoggerFactory.getLogger("TaskExecutionOptimizer-$name")
        private val taskProfiles = ConcurrentHashMap<String, TaskProfile>()
        private val taskDurations = ConcurrentHashMap<String, MutableList<Long>>()
        private val taskSuccesses = ConcurrentHashMap<String, AtomicLong>()
        private val taskFailures = ConcurrentHashMap<String, AtomicLong>()
        private val maxSamples = 100
    private val profilingEnabled = AtomicInteger(1)
        fun recordTaskExecution(taskType: String, durationMs: Long, success: Boolean) {
        if (profilingEnabled.get() == 0) return

        val durations = taskDurations.getOrPut(taskType) { mutableListOf() }
        durations.add(durationMs)
        while (durations.size > maxSamples) durations.removeAt(0)
        if (success) {
            taskSuccesses.computeIfAbsent(taskType) { AtomicLong(0) }.incrementAndGet()
        } else {
            taskFailures.computeIfAbsent(taskType) { AtomicLong(0) }.incrementAndGet()
        }

        updateProfile(taskType)
    }
        fun getProfile(taskType: String): TaskProfile? = taskProfiles[taskType]

    fun getRecommendedTimeout(taskType: String): Long {
        return taskProfiles[taskType]?.recommendedTimeoutMs ?: 30000L
    }
        fun getRecommendedRetries(taskType: String): Int {
        return taskProfiles[taskType]?.recommendedRetryCount ?: 3
    }
        fun isTaskCacheable(taskType: String): Boolean {
        return taskProfiles[taskType]?.isCacheable ?: false
    }
        fun getAllProfiles(): List<TaskProfile> = taskProfiles.values.toList()
        fun getHotTasks(minCount: Long = 10): List<TaskProfile> {
        return taskProfiles.values.filter { it.executionCount >= minCount }
            .sortedByDescending { it.executionCount }
    }
        fun getSlowTasks(thresholdMs: Long = 5000): List<TaskProfile> {
        return taskProfiles.values.filter { it.averageDurationMs > thresholdMs }
            .sortedByDescending { it.averageDurationMs }
    }
        fun enableProfiling() { profilingEnabled.set(1) }
        fun disableProfiling() { profilingEnabled.set(0) }
        fun isProfilingEnabled(): Boolean = profilingEnabled.get() == 1

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "profiledTasks" to taskProfiles.size,
        "profilingEnabled" to isProfilingEnabled(),
        "hotTasks" to getHotTasks(10).size,
        "slowTasks" to getSlowTasks().size
    )
        fun reset() {
        taskProfiles.clear()
        taskDurations.clear()
        taskSuccesses.clear()
        taskFailures.clear()
    }
        private fun updateProfile(taskType: String) {
        val durations = taskDurations[taskType] ?: return
        val successes = taskSuccesses[taskType]?.get() ?: 0
        val failures = taskFailures[taskType]?.get() ?: 0
        val total = successes + failures
        if (total == 0 || durations.isEmpty()) return

        val sorted = durations.sorted()
        val avg = durations.average()
        val p95 = sorted.getOrNull((sorted.size * 0.95).toInt()) ?: sorted.last()
        val max = sorted.last()
        val recommendedTimeout = (p95 * 3).coerceAtLeast(5000L)
        val recommendedRetries = when {
            p95 > 30000 -> 1
            p95 > 10000 -> 2
            else -> 3
        }
        val isCacheable = durations.all { it < 1000 } && total > 10

        val profile = TaskProfile(
            taskType = taskType,
            averageDurationMs = avg.toLong(),
            p95DurationMs = p95,
            maxDurationMs = max,
            successRate = if (total > 0) successes.toDouble() / total else 1.0,
            executionCount = total,
            recommendedTimeoutMs = recommendedTimeout,
            recommendedRetryCount = recommendedRetries,
            isCacheable = isCacheable,
            priority = if (avg > 5000) 1 else if (avg > 1000) 2 else 3
        )
        taskProfiles[taskType] = profile
    }
}

class MemoryOptimizer(private val name: String = "memory-opt") {
    data class MemoryStats(
        val heapUsedMb: Long,
        val heapMaxMb: Long,
        val heapUtilization: Double,
        val nonHeapUsedMb: Long,
        val nativeMemoryMb: Long,
        val gcCount: Long,
        val gcTimeMs: Long,
        val allocationRateMbPerSec: Double
    )

    data class MemoryPressureLevel(val level: String, val threshold: Double)
        private val runtime = Runtime.getRuntime()
        private val logger = LoggerFactory.getLogger("MemoryOptimizer-$name")
        private val previousFreeMemory = AtomicLong(runtime.freeMemory())
        private val previousTime = AtomicLong(System.nanoTime())
        private val gcCount = AtomicLong(0)
        private val gcTimeMs = AtomicLong(0)
        private val totalAllocationBytes = AtomicLong(0)
        private val sampleCount = AtomicInteger(0)

    companion object {
        val PRESSURE_LEVELS = listOf(
            MemoryPressureLevel("CRITICAL", 0.95),
            MemoryPressureLevel("HIGH", 0.85),
            MemoryPressureLevel("MODERATE", 0.70),
            MemoryPressureLevel("LOW", 0.50),
            MemoryPressureLevel("NORMAL", 0.0)
        )
    }
        fun getMemoryPressure(): String {
        val usage = getMemoryUsage()
        return PRESSURE_LEVELS.first { usage <= it.threshold || it.threshold == 0.0 }.level
    }
        fun getMemoryUsage(): Double {
        val max = runtime.maxMemory().coerceAtLeast(1)
        val used = runtime.totalMemory() - runtime.freeMemory()
        return used.toDouble() / max
    }
        fun getMemoryStats(): MemoryStats {
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapMax = runtime.maxMemory()
        val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
        val totalGcCount = gcBeans.sumOf { it.collectionCount }
        val totalGcTime = gcBeans.sumOf { it.collectionTime.toLong() }
        val prevFree = previousFreeMemory.getAndSet(runtime.freeMemory())
        val prevTime = previousTime.getAndSet(System.nanoTime())
        val currentFree = runtime.freeMemory()
        val elapsedNs = System.nanoTime() - prevTime
        val allocatedBytes = prevFree - currentFree + 0
        totalAllocationBytes.addAndGet(allocatedBytes.coerceAtLeast(0))
        sampleCount.incrementAndGet()
        val allocRate = if (elapsedNs > 0)
            allocatedBytes.coerceAtLeast(0).toDouble() / elapsedNs * 1_000_000_000.0 / 1024 / 1024 else 0.0

        return MemoryStats(
            heapUsedMb = heapUsed / 1024 / 1024,
            heapMaxMb = heapMax / 1024 / 1024,
            heapUtilization = getMemoryUsage(),
            nonHeapUsedMb = java.lang.management.ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used / 1024 / 1024,
            nativeMemoryMb = 0,
            gcCount = totalGcCount,
            gcTimeMs = totalGcTime,
            allocationRateMbPerSec = allocRate.coerceAtLeast(0.0)
        )
    }
        fun suggestGc(): Boolean {
        val usage = getMemoryUsage()
        if (usage > 0.9) {
            logger.warn("High memory pressure ({:.1f}%), suggesting GC", usage * 100)
            System.gc()
            gcCount.incrementAndGet()
        return true
        }
        return false
    }
        fun getRecommendedHeapSize(currentMaxMb: Long): Long {
        val stats = getMemoryStats()
        val headroom = (stats.heapUsedMb * 1.5).toLong()
        return headroom.coerceIn(64, currentMaxMb * 2)
    }
        fun getStats(): Map<String, Any> {
        val stats = getMemoryStats()
        return mapOf(
            "name" to name,
            "heapUsedMb" to stats.heapUsedMb,
            "heapMaxMb" to stats.heapMaxMb,
            "utilization" to "${"%.1f".format(stats.heapUtilization * 100)}%",
            "pressure" to getMemoryPressure(),
            "gcCount" to stats.gcCount,
            "gcTimeMs" to stats.gcTimeMs,
            "allocationRate" to "${"%.1f".format(stats.allocationRateMbPerSec)} MB/s"
        )
    }
}

class CoroutineOptimizer(private val name: String = "coroutine-opt") {
    data class CoroutineMetrics(
        val activeCoroutines: Int,
        val completedCoroutines: Long,
        val failedCoroutines: Long,
        val averageExecutionTimeMs: Double,
        val maxConcurrent: Int,
        val dispatcherUtilization: Double
    )
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val activeCoroutines = ConcurrentHashMap.newKeySet<String>()
        private val totalStarted = AtomicLong(0)
        private val totalCompleted = AtomicLong(0)
        private val totalFailed = AtomicLong(0)
        private val totalExecutionNs = AtomicLong(0)
        private val peakConcurrent = AtomicInteger(0)
        private val executionCount = AtomicInteger(0)
        private val logger = LoggerFactory.getLogger("CoroutineOptimizer-$name")
        fun launch(name: String = "task", block: suspend CoroutineScope.() -> Unit): Job {
        val id = "${name}-${totalStarted.incrementAndGet()}"
        activeCoroutines.add(id)
        updatePeakConcurrent()
        val start = System.nanoTime()
        return scope.launch(CoroutineName(name)) {
            try {
                block()
                totalCompleted.incrementAndGet()
                totalExecutionNs.addAndGet(System.nanoTime() - start)
                executionCount.incrementAndGet()
            } catch (e: Exception) {
                totalFailed.incrementAndGet()
                logger.debug("Coroutine '{}' failed: {}", id, e.message)
            } finally {
                activeCoroutines.remove(id)
            }
        }
    }
        fun <T> async(name: String = "async", block: suspend CoroutineScope.() -> T): Deferred<T> {
        val id = "${name}-${totalStarted.incrementAndGet()}"
        activeCoroutines.add(id)
        updatePeakConcurrent()
        return scope.async(CoroutineName(name)) {
            try {
                block()
            } finally {
                activeCoroutines.remove(id)
                totalCompleted.incrementAndGet()
            }
        }
    }

    suspend fun withOptimizedContext(dispatcher: CoroutineDispatcher = Dispatchers.Default, block: suspend () -> Unit) {
        withContext(dispatcher) {
            totalStarted.incrementAndGet()
            try {
                block()
                totalCompleted.incrementAndGet()
            } catch (e: Exception) {
                totalFailed.incrementAndGet()
        throw e
            }
        }
    }
        fun getActiveCount(): Int = activeCoroutines.size
    fun getTotalStarted(): Long = totalStarted.get()
        fun getTotalCompleted(): Long = totalCompleted.get()
        fun getMetrics(): CoroutineMetrics {
        val active = activeCoroutines.size
        val completed = totalCompleted.get()
        val failed = totalFailed.get()
        val count = executionCount.get()
        val totalExecTime = totalExecutionNs.get()
        return CoroutineMetrics(
            activeCoroutines = active,
            completedCoroutines = completed,
            failedCoroutines = failed,
            averageExecutionTimeMs = if (count > 0) totalExecTime.toDouble() / count / 1_000_000.0 else 0.0,
            maxConcurrent = peakConcurrent.get(),
            dispatcherUtilization = active.toDouble() / Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        )
    }
        fun cancelAll() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        activeCoroutines.clear()
    }
        fun shutdown() {
        cancelAll()
        scope.cancel()
    }
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "active" to activeCoroutines.size,
        "started" to totalStarted.get(),
        "completed" to totalCompleted.get(),
        "failed" to totalFailed.get(),
        "peakConcurrent" to peakConcurrent.get()
    )
        private fun updatePeakConcurrent() {
        val current = activeCoroutines.size
        var peak = peakConcurrent.get()
        while (current > peak && !peakConcurrent.compareAndSet(peak, current)) {
            peak = peakConcurrent.get()
        }
    }
}
