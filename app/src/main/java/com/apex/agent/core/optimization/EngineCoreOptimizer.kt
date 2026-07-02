package com.apex.agent.core.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class CoreOptimizationConfig(
    val enableBackgroundOptimization: Boolean = true,
    val enableCooperativeScheduling: Boolean = true,
    val enableWorkStealing: Boolean = true,
    val enableMemoryAwareness: Boolean = true,
    val enableBatteryAwareness: Boolean = true,
    val optimizationIntervalMs: Long = 30000L,
    val maxDispatcherThreads: Int = 4,
    val defaultParallelism: Int = 2,
    val memoryThresholdPercent: Int = 80,
    val batteryLowThreshold: Int = 20
)

enum class OptimizationDomain {
    THREAD_POOL, MEMORY, SCHEDULING, IO, NETWORK,
    DATABASE, CACHE, AI, WORKFLOW, PIPELINE
}

data class OptimizationAction(
    val domain: OptimizationDomain,
    val action: String,
    val priority: Int,
    val expectedImprovement: Double,
    val cost: Double,
    val description: String
)

data class CoreMetrics(
    val activeCoroutines: Int,
    val queuedTasks: Int,
    val memoryUsagePercent: Double,
    val freeMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val avgTaskDurationMs: Double,
    val dispatcherUtilization: Map<String, Double>,
    val optimizationCount: Long,
    val totalOptimizationsApplied: Long
)

data class ResourceState(
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val cpuLoadAverage: Double,
    val activeThreadCount: Int,
    val batteryLevel: Float? = null,
    val isCharging: Boolean = true,
    val networkAvailable: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
)

data class OptimizationRecommendation(
    val action: String,
    val domain: OptimizationDomain,
    val priority: Int,
    val reason: String,
    val estimatedImpact: String,
    val autoApplicable: Boolean = false
)

class EngineCoreOptimizer private constructor() {

    private val config = CoreOptimizationConfig()
    private val domainOptimizationCount = ConcurrentHashMap<OptimizationDomain, AtomicInteger>()
    private val taskExecutionTimes = CopyOnWriteArrayList<Long>()
    private val recommendations = CopyOnWriteArrayList<OptimizationRecommendation>()
    private val optimizationHistory = CopyOnWriteArrayList<Pair<String, Long>>()
    private var totalOptimizations = AtomicLong(0)
    private var scope: CoroutineScope? = null
    private var isActive = false
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var instance: EngineCoreOptimizer? = null

        fun getInstance(): EngineCoreOptimizer {
            return instance ?: synchronized(this) {
                instance ?: EngineCoreOptimizer().also { instance = it }
            }
        }

        private const val TASK_HISTORY_SIZE = 500
        private const val RECOMMENDATION_UPDATE_INTERVAL = 4
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        isActive = true

        if (config.enableBackgroundOptimization) {
            coroutineScope.launch(Dispatchers.Default) {
                optimizationLoop()
            }
        }
        if (config.enableCooperativeScheduling) {
            Dispatchers.Default.let {
                coroutineScope.launch(it) {
                    schedulingOptimizerLoop()
                }
            }
        }
    }

    fun shutdown() {
        isActive = false
    }

    suspend fun applyOptimization(domain: OptimizationDomain, action: String) {
        mutex.withLock {
            domainOptimizationCount.computeIfAbsent(domain) { AtomicInteger(0) }.incrementAndGet()
            totalOptimizations.incrementAndGet()
            optimizationHistory.add(Pair("$domain:$action", System.currentTimeMillis()))
            if (optimizationHistory.size > 1000) optimizationHistory.removeAt(0)
        }
    }

    suspend fun applyOptimizations(actions: List<OptimizationAction>) {
        for (action in actions.sortedByDescending { it.priority }) {
            applyOptimization(action.domain, action.action)
        }
    }

    fun getResourceState(): ResourceState {
        val runtime = Runtime.getRuntime()
        ResourceState(
            availableMemoryBytes = runtime.freeMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            cpuLoadAverage = 0.5,
            activeThreadCount = Thread.activeCount(),
            timestampMs = System.currentTimeMillis()
        )
    }

    fun shouldOptimize(state: ResourceState): Boolean {
        val memUsage = if (state.totalMemoryBytes > 0) {
            (state.totalMemoryBytes - state.availableMemoryBytes).toDouble() / state.totalMemoryBytes * 100
        } else 0.0
        memUsage > config.memoryThresholdPercent ||
            state.cpuLoadAverage > 0.8 ||
            (state.batteryLevel != null && state.batteryLevel < config.batteryLowThreshold && !state.isCharging)
    }

    fun recommendOptimizations(state: ResourceState): List<OptimizationRecommendation> {
        val recs = mutableListOf<OptimizationRecommendation>()
        val memUsage = if (state.totalMemoryBytes > 0) {
            (state.totalMemoryBytes - state.availableMemoryBytes).toDouble() / state.totalMemoryBytes * 100
        } else 0.0

        if (memUsage > config.memoryThresholdPercent) {
            recs.add(OptimizationRecommendation(
                "reduce_memory_pressure", OptimizationDomain.MEMORY, 5,
                "Memory usage at ${"%.0f".format(memUsage)}%",
                "Free ~${(state.availableMemoryBytes * 0.2 / 1024 / 1024).toInt()}MB", true))
        }
        if (state.activeThreadCount > config.maxDispatcherThreads * 4) {
            recs.add(OptimizationRecommendation(
                "reduce_thread_pool", OptimizationDomain.THREAD_POOL, 4,
                "Active threads: ${state.activeThreadCount}",
                "Reduce contention by ${"%.0f".format((state.activeThreadCount.toDouble() / config.maxDispatcherThreads / 4 - 1) * 100)}%"))
        }
        if (state.cpuLoadAverage > 0.8) {
            recs.add(OptimizationRecommendation(
                "throttle_background_tasks", OptimizationDomain.SCHEDULING, 3,
                "CPU load: ${"%.0f".format(state.cpuLoadAverage * 100)}%",
                "Reduce scheduling overhead and improve latency"))
        }
        if (taskExecutionTimes.size >= 10) {
            val avg = taskExecutionTimes.average()
            if (avg > 500) {
                recs.add(OptimizationRecommendation(
                    "enable_task_batching", OptimizationDomain.SCHEDULING, 2,
                    "Average task duration: ${"%.0f".format(avg)}ms",
                    "Batch small tasks to reduce overhead"))
            }
        }
        recs.sortedByDescending { it.priority }
    }

    fun getDomainOptimizationCount(domain: OptimizationDomain): Int {
        domainOptimizationCount[domain]?.get() ?: 0
    }

    fun getMetrics(): CoreMetrics {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = totalMem - freeMem
        val memPercent = if (totalMem > 0) usedMem.toDouble() / totalMem * 100 else 0.0
        val avgDuration = if (taskExecutionTimes.isNotEmpty()) taskExecutionTimes.average() else 0.0
        CoreMetrics(
            activeCoroutines = Thread.activeCount(),
            queuedTasks = 0,
            memoryUsagePercent = memPercent,
            freeMemoryBytes = freeMem,
            totalMemoryBytes = totalMem,
            avgTaskDurationMs = avgDuration,
            dispatcherUtilization = mapOf("default" to 0.5),
            optimizationCount = totalOptimizations.get(),
            totalOptimizationsApplied = domainOptimizationCount.values.sumOf { it.get().toLong() }
        )
    }

    fun recordTaskExecution(durationMs: Long) {
        taskExecutionTimes.add(durationMs)
        if (taskExecutionTimes.size > TASK_HISTORY_SIZE) taskExecutionTimes.removeAt(0)
    }

    fun getOptimizationHistory(timeRangeMs: Long = 300000L): List<Pair<String, Long>> {
        val cutoff = System.currentTimeMillis() - timeRangeMs
        optimizationHistory.filter { it.second >= cutoff }
    }

    fun getRecentOptimizations(limit: Int = 20): List<String> {
        optimizationHistory.takeLast(limit).map { it.first }
    }

    fun getRecommendations(): List<OptimizationRecommendation> = recommendations.toList()

    fun updateConfig(newConfig: CoreOptimizationConfig): CoreOptimizationConfig {
        newConfig
    }

    fun resetStatistics() {
        domainOptimizationCount.clear()
        taskExecutionTimes.clear()
        optimizationHistory.clear()
        totalOptimizations.set(0)
    }

    fun getRecommendedActions(): List<String> {
        return recommendations.map { "${it.action} (${it.domain}, priority=${it.priority})" }
    }

    fun getDomainSummary(): String {
        return domainOptimizationCount.entries
            .sortedByDescending { it.value.get() }
            .joinToString("\n") { "  ${it.key}: ${it.value.get()} optimizations" }
    }

    fun printOptimizationReport(): String {
        val metrics = getMetrics()
        val domains = domainOptimizationCount.entries
            .sortedByDescending { it.value.get() }
            .joinToString(", ") { "${it.key}=${it.value.get()}" }
        """
        |EngineCoreOptimizer Report
        |  Memory: ${"%.0f".format(metrics.memoryUsagePercent)}% used (${metrics.freeMemoryBytes / 1024 / 1024}MB free / ${metrics.totalMemoryBytes / 1024 / 1024}MB total)
        |  Coroutines: ${metrics.activeCoroutines} active
        |  Avg Task Duration: ${"%.1f".format(metrics.avgTaskDurationMs)}ms
        |  Optimizations Applied: ${metrics.totalOptimizationsApplied}
        |  Domain Breakdown: $domains
        """.trimMargin()
    }

    private suspend fun optimizationLoop() {
        while (isActive) {
            delay(config.optimizationIntervalMs)
            val state = getResourceState()
            if (shouldOptimize(state)) {
                val recs = recommendOptimizations(state)
                recommendations.addAll(recs)
                if (recommendations.size > 100) {
                    repeat(50) { recommendations.removeFirstOrNull() }
                }
                val autoActions = recs.filter { it.autoApplicable }
                for (rec in autoActions) {
                    applyOptimization(rec.domain, rec.action)
                }
            }
        }
    }

    private suspend fun schedulingOptimizerLoop() {
        while (isActive) {
            delay(config.optimizationIntervalMs / 2)
            val state = getResourceState()
            if (state.cpuLoadAverage > 0.9) {
                applyOptimization(OptimizationDomain.SCHEDULING, "cooperative_yield")
            }
        }
    }
}
