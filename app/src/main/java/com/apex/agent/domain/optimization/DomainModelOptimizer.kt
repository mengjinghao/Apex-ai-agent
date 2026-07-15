package com.apex.agent.domain.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.time.*

data class BurstExecutionProfile(
    val taskId: String,
    val skillName: String,
    val priority: Int,
    val estimatedComplexity: Double,
    val estimatedDurationMs: Long,
    val dependencies: List<String>,
    val resourceRequirements: Map<String, Double>,
    val timeoutMs: Long = 30000L,
    val retryOnFailure: Boolean = true,
    val maxRetries: Int = 2
)

data class ExecutionGraph(
    val nodes: Map<String, ExecutionNode>,
    val edges: List<ExecutionEdge>,
    val entryPoints: List<String>,
    val criticalPath: List<String>,
    val estimatedTotalDurationMs: Long
)

data class ExecutionNode(
    val id: String,
    val profile: BurstExecutionProfile,
    val level: Int,
    val earliestStartMs: Long,
    val latestStartMs: Long,
    val slackMs: Long,
    val isCritical: Boolean
)

data class ExecutionEdge(
    val from: String,
    val to: String,
    val weight: Long = 0L,
    val condition: String? = null
)

data class ResourceBudget(
    val cpuQuota: Double = 1.0,
    val memoryBytes: Long = 0L,
    val networkBandwidthBps: Long = 0L,
    val diskIops: Int = 0,
    val batteryDrainAllowed: Double = 0.2,
    val priority: Int = 0
)

data class AdaptiveThreshold(
    val name: String,
    val currentValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val dampingFactor: Double = 0.3,
    val lastUpdateMs: Long = System.currentTimeMillis()
)

data class ModelMetrics(
    val totalPlansGenerated: Long,
    val averagePlanComplexity: Double,
    val cacheHitRate: Double,
    val averageOptimizationTimeMs: Double,
    val activeProfiles: Int,
    val graphCacheSize: Int,
    val criticalPathLengths: List<Long>
)

class DomainModelOptimizer private constructor() {

    private val profileCache = ConcurrentHashMap<String, BurstExecutionProfile>()
        private val graphCache = ConcurrentHashMap<String, ExecutionGraph>()
        private val generationTimes = CopyOnWriteArrayList<Long>()
        private val totalGenerated = AtomicLong(0)
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)
        private val activeProfiles = ConcurrentHashMap<String, BurstExecutionProfile>()
        private val thresholds = ConcurrentHashMap<String, AdaptiveThreshold>()

    companion object {
        @Volatile
        private var instance: DomainModelOptimizer? = null

        fun getInstance(): DomainModelOptimizer {
            return instance ?: synchronized(this) {
                instance ?: DomainModelOptimizer().also { instance = it }
            }
        }
        private const val MAX_GRAPH_CACHE = 200
        private const val DEFAULT_TIMEOUT_MS = 30000L
    }

    init {
        registerDefaultThresholds()
    }
        private fun registerDefaultThresholds() {
        thresholds["complexity"] = AdaptiveThreshold("complexity", 0.5, 0.0, 1.0)
        thresholds["parallelism"] = AdaptiveThreshold("parallelism", 2.0, 1.0, 8.0)
        thresholds["cache_ttl"] = AdaptiveThreshold("cache_ttl", 300000.0, 60000.0, 3600000.0)
        thresholds["retry_delay"] = AdaptiveThreshold("retry_delay", 1000.0, 100.0, 30000.0)
        thresholds["batch_size"] = AdaptiveThreshold("batch_size", 5.0, 1.0, 50.0)
    }
        fun registerProfile(profile: BurstExecutionProfile) {
        profileCache[profile.taskId] = profile
        activeProfiles[profile.taskId] = profile
    }
        fun getProfile(taskId: String): BurstExecutionProfile? = profileCache[taskId]

    fun removeProfile(taskId: String) {
        profileCache.remove(taskId)
        activeProfiles.remove(taskId)
        graphCache.remove(taskId)
    }
        fun clearAll() {
        profileCache.clear()
        graphCache.clear()
        activeProfiles.clear()
        generationTimes.clear()
        totalGenerated.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
    }
        fun buildExecutionGraph(profiles: List<BurstExecutionProfile>): ExecutionGraph {
        val startTime = System.nanoTime()
        totalGenerated.incrementAndGet()
        val cacheKey = profiles.sortedBy { it.taskId }.joinToString("|") { it.taskId }
        val cached = graphCache[cacheKey]
        if (cached != null) {
            cacheHits.incrementAndGet()
        return cached
        }
        cacheMisses.incrementAndGet()
        val profileMap = profiles.associateBy { it.taskId }
        val nodes = mutableMapOf<String, ExecutionNode>()
        val edges = mutableListOf<ExecutionEdge>()
        var maxLevel = 0
        val levels = mutableMapOf<String, Int>()
        fun computeLevel(taskId: String, visited: MutableSet<String> = mutableSetOf()): Int {
            if (taskId in visited) return levels[taskId] ?: 0
            visited.add(taskId)
        val profile = profileMap[taskId] ?: return 0
            if (profile.dependencies.isEmpty()) {
                levels[taskId] = 0
                return 0
            }
        val depLevels = profile.dependencies.mapNotNull { depId ->
                profileMap[depId]?.let { computeLevel(depId, visited) + 1 }
            }
        val level = if (depLevels.isNotEmpty()) depLevels.max() else 0
            levels[taskId] = level
            level
        }
        for (profile in profiles) {
            val level = computeLevel(profile.taskId)
            maxLevel = max(maxLevel, level)
        }
        for (profile in profiles) {
            for (depId in profile.dependencies) {
                edges.add(ExecutionEdge(from = depId, to = profile.taskId))
            }
        }
        val forwardPass = mutableMapOf<String, Long>()
        val sorted = profiles.sortedBy { levels[it.taskId] }
        for (profile in sorted) {
            val depMaxEnd = profile.dependencies.maxOfOrNull { forwardPass[it] ?: 0L } ?: 0L
            forwardPass[profile.taskId] = depMaxEnd + profile.estimatedDurationMs
        }
        val totalDuration = forwardPass.values.maxOrNull() ?: 0L
        val backwardPass = mutableMapOf<String, Long>()
        for (profile in sorted.reversed()) {
            val successors = edges.filter { it.from == profile.taskId }
        val succMinStart = successors.minOfOrNull { backwardPass[it.to] ?: totalDuration } ?: totalDuration
            backwardPass[profile.taskId] = succMinStart - profile.estimatedDurationMs
        }
        val entryPoints = profiles.filter { it.dependencies.isEmpty() }.map { it.taskId }
        val criticalPath = mutableListOf<String>()
        var current = entryPoints.minByOrNull { forwardPass[it] ?: 0L } ?: ""
        while (current.isNotEmpty()) {
            criticalPath.add(current)
        val successors = edges.filter { it.from == current }
            current = successors.minByOrNull { backwardPass[it.to] ?: totalDuration }?.to ?: ""
        }
        for (profile in profiles) {
            val es = forwardPass[profile.taskId] ?: 0L
            val ls = backwardPass[profile.taskId] ?: 0L
            val slack = ls - es
            nodes[profile.taskId] = ExecutionNode(
                id = profile.taskId,
                profile = profile,
                level = levels[profile.taskId] ?: 0,
                earliestStartMs = es,
                latestStartMs = ls,
                slackMs = slack,
                isCritical = profile.taskId in criticalPath
            )
        }
        val graph = ExecutionGraph(
            nodes = nodes,
            edges = edges,
            entryPoints = entryPoints,
            criticalPath = criticalPath,
            estimatedTotalDurationMs = totalDuration
        )
        if (graphCache.size < MAX_GRAPH_CACHE) {
            graphCache[cacheKey] = graph
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        generationTimes.add(elapsed)
        if (generationTimes.size > 200) generationTimes.removeAt(0)

        graph
    }
        fun computeOptimalParallelism(profiles: List<BurstExecutionProfile>): Int {
        if (profiles.isEmpty()) return 1
        val depCounts = profiles.associate { it.taskId to it.dependencies.size }
        val indegrees = profiles.associate { it.taskId to it.dependencies.size }
        val maxConcurrent = profiles.groupBy { indegrees[it.taskId] ?: 0 }.maxOfOrNull { it.value.size } ?: 1
        maxConcurrent.coerceIn(1, 8)
    }
        fun analyzeResourceRequirements(profiles: List<BurstExecutionProfile>): ResourceBudget {
        val totalCpu = profiles.sumOf { it.resourceRequirements["cpu"] ?: 0.0 }
        val totalMemory = profiles.sumOf { it.resourceRequirements["memory"] ?: 0.0 }.toLong()
        val totalNetwork = profiles.sumOf { it.resourceRequirements["network"] ?: 0.0 }.toLong()
        val totalDisk = profiles.sumOf { it.resourceRequirements["disk"] ?: 0.0 }.toInt()
        ResourceBudget(
            cpuQuota = totalCpu.coerceIn(0.0, 8.0),
            memoryBytes = totalMemory,
            networkBandwidthBps = totalNetwork,
            diskIops = totalDisk.coerceAtMost(10000),
            priority = profiles.maxOfOrNull { it.priority } ?: 0
        )
    }
        fun adaptThreshold(name: String, measuredValue: Double) {
        val threshold = thresholds[name] ?: return
        val error = measuredValue - threshold.currentValue
        val newValue = threshold.currentValue + threshold.dampingFactor * error
        thresholds[name] = threshold.copy(
            currentValue = newValue.coerceIn(threshold.minValue, threshold.maxValue),
            lastUpdateMs = System.currentTimeMillis()
        )
    }
        fun getThreshold(name: String): AdaptiveThreshold? = thresholds[name]

    fun getAllThresholds(): List<AdaptiveThreshold> = thresholds.values.toList()
        fun mergeProfiles(existing: BurstExecutionProfile, update: BurstExecutionProfile): BurstExecutionProfile {
        existing.copy(
            priority = update.priority,
            estimatedComplexity = update.estimatedComplexity,
            estimatedDurationMs = update.estimatedDurationMs,
            dependencies = update.dependencies.ifEmpty { existing.dependencies },
            resourceRequirements = existing.resourceRequirements + update.resourceRequirements,
            timeoutMs = update.timeoutMs,
            retryOnFailure = update.retryOnFailure && existing.retryOnFailure,
            maxRetries = update.maxRetries.coerceAtLeast(existing.maxRetries)
        )
    }
        fun generateSchedule(profiles: List<BurstExecutionProfile>): List<List<String>> {
        val graph = buildExecutionGraph(profiles)
        val schedule = mutableListOf<MutableList<String>>()
        val visited = mutableSetOf<String>()
        val remaining = profiles.map { it.taskId }.toMutableSet()
        var level = 0

        while (remaining.isNotEmpty()) {
            val currentLevel = profiles.filter { profile ->
                profile.taskId in remaining &&
                    profile.dependencies.all { it in visited }
            }.map { it.taskId }
        if (currentLevel.isEmpty()) {
                remaining.firstOrNull()?.let {
                    schedule.add(mutableListOf(it))
                    visited.add(it)
                    remaining.remove(it)
                }
            } else {
                schedule.add(currentLevel.toMutableList())
                visited.addAll(currentLevel)
                remaining.removeAll(currentLevel)
            }
            level++
        }
        schedule
    }
        fun getCriticalPath(profiles: List<BurstExecutionProfile>): List<String> {
        val graph = buildExecutionGraph(profiles)
        graph.criticalPath
    }
        fun estimateCompletionTime(profiles: List<BurstExecutionProfile>): Long {
        val graph = buildExecutionGraph(profiles)
        graph.estimatedTotalDurationMs
    }
        fun getMetrics(): ModelMetrics {
        val avgComplexity = if (profileCache.isNotEmpty()) {
            profileCache.values.map { it.estimatedComplexity }.average()
        } else 0.0
        val totalCalls = cacheHits.get() + cacheMisses.get()
        val hitRate = if (totalCalls > 0) cacheHits.get().toDouble() / totalCalls else 0.0
        val avgTime = if (generationTimes.isNotEmpty()) generationTimes.average() else 0.0
        ModelMetrics(
            totalPlansGenerated = totalGenerated.get(),
            averagePlanComplexity = avgComplexity,
            cacheHitRate = hitRate,
            averageOptimizationTimeMs = avgTime,
            activeProfiles = activeProfiles.size,
            graphCacheSize = graphCache.size,
            criticalPathLengths = graphCache.values.map { it.criticalPath.size.toLong() }
        )
    }
        fun getActiveProfiles(): List<BurstExecutionProfile> = activeProfiles.values.toList()
        fun resetMetrics() {
        generationTimes.clear()
        totalGenerated.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
    }
}
