package com.apex.agent.plugins.burst.builtin.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class SkillExecutionContext(
    val skillName: String,
    val input: Any,
    val parameters: Map<String, Any> = emptyMap(),
    val startTimeMs: Long = System.currentTimeMillis(),
    val attemptNumber: Int = 0,
    val maxRetries: Int = 2,
    val timeoutMs: Long = 30000L,
    val priority: Int = 0
)

data class SkillExecutionResult(
    val skillName: String,
    val output: Any?,
    val durationMs: Long,
    val success: Boolean,
    val attemptCount: Int,
    val errorMessage: String? = null,
    val warnings: List<String> = emptyList(),
    val metrics: SkillPerformanceMetrics = SkillPerformanceMetrics()
)

data class SkillPerformanceMetrics(
    var parseTimeMs: Long = 0L,
    var executionTimeMs: Long = 0L,
    var outputTimeMs: Long = 0L,
    var memoryBytesUsed: Long = 0L,
    var cpuTimeMs: Long = 0L
)

data class SkillCompositeResult(
    val results: Map<String, SkillExecutionResult>,
    val totalDurationMs: Long,
    val overallSuccess: Boolean,
    val failedSkills: List<String>,
    val partialResults: Boolean
)

data class SkillDependencyGraph(
    val skills: Map<String, SkillNode>,
    val executionOrder: List<List<String>>,
    val criticalPath: List<String>,
    val estimatedTotalDurationMs: Long
)

data class SkillNode(
    val name: String,
    val dependencies: List<String>,
    val estimatedDurationMs: Long,
    val priority: Int,
    val isOptional: Boolean = false,
    val alternatives: List<String> = emptyList()
)

data class SkillStatistics(
    val skillName: String,
    val totalExecutions: Long,
    val successfulExecutions: Long,
    val failedExecutions: Long,
    val averageDurationMs: Double,
    val p50DurationMs: Long,
    val p95DurationMs: Long,
    val p99DurationMs: Long,
    val averageRetries: Double,
    val successRate: Double,
    val lastExecutionMs: Long,
    val averageInputSize: Int,
    val averageOutputSize: Int
)

data class SkillExecutionCache(
    val input: Any,
    val result: Any,
    val timestampMs: Long,
    val ttlMs: Long,
    val hitCount: AtomicInteger = AtomicInteger(1)
)

data class SkillAdaptiveConfig(
    val enabled: Boolean = true,
    val cacheEnabled: Boolean = true,
    val parallelExecution: Boolean = true,
    val speculativeExecution: Boolean = false,
    val maxConcurrentSkills: Int = 3,
    val cacheTtlMs: Long = 300000L,
    val timeoutMultiplier: Double = 1.5,
    val retryBackoffBaseMs: Long = 500L,
    val adaptiveThresholdWindow: Int = 20
)

data class SkillProfile(
    val name: String,
    val category: String,
    val averageDurationMs: Long,
    val inputComplexity: Double = 1.0,
    val isDeterministic: Boolean = true,
    val supportsParallel: Boolean = false,
    val memoryIntensive: Boolean = false
)

class SkillExecutionOptimizer private constructor() {

    private val executionCache = ConcurrentHashMap<String, SkillExecutionCache>()
    private val skillProfiles = ConcurrentHashMap<String, SkillProfile>()
    private val executionHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<SkillExecutionResult>>()
    private val durationPercentiles = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
    private val totalExecutions = ConcurrentHashMap<String, AtomicLong>()
    private val totalFailures = ConcurrentHashMap<String, AtomicLong>()
    private val totalRetries = ConcurrentHashMap<String, AtomicLong>()
    private val config = SkillAdaptiveConfig()
    private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: SkillExecutionOptimizer? = null

        fun getInstance(): SkillExecutionOptimizer {
            return instance ?: synchronized(this) {
                instance ?: SkillExecutionOptimizer().also { instance = it }
            }
        }

        private const val MAX_CACHE_SIZE = 1000
        private const val HISTORY_LIMIT = 100
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        registerDefaultProfiles()
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(120000L)
                maintenanceCycle()
            }
        }
    }

    private fun registerDefaultProfiles() {
        registerProfile(SkillProfile("chain_of_thought", "reasoning", 2000L, 1.5, false))
        registerProfile(SkillProfile("tree_of_thought", "reasoning", 5000L, 2.0, false))
        registerProfile(SkillProfile("react", "reasoning", 3000L, 1.8, false))
        registerProfile(SkillProfile("reflexion", "reasoning", 4000L, 2.0, false))
        registerProfile(SkillProfile("rag_pipeline", "retrieval", 2500L, 1.2, false))
        registerProfile(SkillProfile("tool_fusion", "execution", 1500L, 1.0, true))
        registerProfile(SkillProfile("adaptive_execution", "execution", 1000L, 0.8, true))
        registerProfile(SkillProfile("code_quality", "analysis", 3000L, 1.5, true))
        registerProfile(SkillProfile("knowledge_graph", "memory", 2000L, 1.3, true))
        registerProfile(SkillProfile("memory_storage", "memory", 500L, 0.5, true))
        registerProfile(SkillProfile("file_search", "utility", 800L, 0.3, true))
        registerProfile(SkillProfile("security_manager", "security", 1000L, 0.8, true))
        registerProfile(SkillProfile("stream_processor", "data", 2000L, 1.0, true))
        registerProfile(SkillProfile("task_scheduler", "planning", 500L, 0.5, true))
        registerProfile(SkillProfile("self_consistency", "verification", 3000L, 1.5, false))
    }

    fun registerProfile(profile: SkillProfile) {
        skillProfiles[profile.name] = profile
    }

    fun getProfile(name: String): SkillProfile? = skillProfiles[name]

    fun getAllProfiles(): List<SkillProfile> = skillProfiles.values.toList()

    suspend fun executeSkill(
        skillName: String,
        input: Any,
        executor: suspend (SkillExecutionContext) -> Any
    ): SkillExecutionResult {
        val profile = skillProfiles[skillName] ?: return SkillExecutionResult(
            skillName, null, 0, false, 0, "Unknown skill: $skillName")

        val cacheKey = buildCacheKey(skillName, input)
        if (config.cacheEnabled && profile.isDeterministic) {
            val cached = executionCache[cacheKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < config.cacheTtlMs) {
                cached.hitCount.incrementAndGet()
                return SkillExecutionResult(
                    skillName, cached.result, 1, true, 1, metrics = SkillPerformanceMetrics(executionTimeMs = 1))
            }
        }

        totalExecutions.computeIfAbsent(skillName) { AtomicLong(0) }.incrementAndGet()
        var lastError: String? = null
        var attempt = 0
        val overallStart = System.currentTimeMillis()
        val metrics = SkillPerformanceMetrics()

        while (attempt <= config.adaptiveMaxRetries(profile)) {
            attempt++
            val ctx = SkillExecutionContext(
                skillName = skillName,
                input = input,
                attemptNumber = attempt,
                maxRetries = config.adaptiveMaxRetries(profile),
                timeoutMs = (profile.averageDurationMs * config.timeoutMultiplier).toLong(),
                priority = profile.inputComplexity.toInt()
            )
            try {
                val parseStart = System.nanoTime()
                val finalInput = prepareInput(input, profile)
                metrics.parseTimeMs = (System.nanoTime() - parseStart) / 1_000_000

                val execStart = System.nanoTime()
                val output = withTimeout(ctx.timeoutMs) {
                    executor(ctx)
                }
                metrics.executionTimeMs = (System.nanoTime() - execStart) / 1_000_000

                val outputStart = System.nanoTime()
                val finalOutput = prepareOutput(output)
                metrics.outputTimeMs = (System.nanoTime() - outputStart) / 1_000_000

                metrics.memoryBytesUsed = estimateMemoryUsage(input, finalOutput)

                if (config.cacheEnabled && profile.isDeterministic) {
                    cacheResult(cacheKey, finalOutput)
                }

                val duration = System.currentTimeMillis() - overallStart
                recordExecution(skillName, duration, true, attempt)
                return SkillExecutionResult(
                    skillName = skillName,
                    output = finalOutput,
                    durationMs = duration,
                    success = true,
                    attemptCount = attempt,
                    metrics = metrics
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                lastError = "Timeout after ${ctx.timeoutMs}ms"
                totalRetries.computeIfAbsent(skillName) { AtomicLong(0) }.incrementAndGet()
                if (attempt < config.adaptiveMaxRetries(profile)) {
                    delay(config.retryBackoffBaseMs * (1 shl attempt))
                }
            } catch (e: Exception) {
                lastError = e.message
                totalRetries.computeIfAbsent(skillName) { AtomicLong(0) }.incrementAndGet()
                if (attempt < config.adaptiveMaxRetries(profile)) {
                    delay(config.retryBackoffBaseMs * (1 shl attempt))
                }
            }
        }

        val duration = System.currentTimeMillis() - overallStart
        totalFailures.computeIfAbsent(skillName) { AtomicLong(0) }.incrementAndGet()
        recordExecution(skillName, duration, false, attempt)
        return SkillExecutionResult(
            skillName = skillName,
            output = null,
            durationMs = duration,
            success = false,
            attemptCount = attempt,
            errorMessage = lastError,
            metrics = metrics
        )
    }

    suspend fun executeSkillsParallel(
        skills: Map<String, Pair<Any, suspend (SkillExecutionContext) -> Any>>
    ): SkillCompositeResult {
        val startTime = System.currentTimeMillis()
        val deferred = skills.map { (name, pair) ->
            scope?.async(Dispatchers.Default) {
                executeSkill(name, pair.first, pair.second)
            }
        }
        val results = deferred.mapNotNull { it?.await() }
        val resultMap = results.associateBy { it.skillName }
        val failed = results.filter { !it.success }.map { it.skillName }
        return SkillCompositeResult(
            results = resultMap,
            totalDurationMs = System.currentTimeMillis() - startTime,
            overallSuccess = failed.isEmpty(),
            failedSkills = failed,
            partialResults = failed.isNotEmpty() && failed.size < skills.size
        )
    }

    fun buildDependencyGraph(skills: List<SkillNode>): SkillDependencyGraph {
        val skillMap = skills.associateBy { it.name }
        val levels = mutableMapOf<String, Int>()
        val maxLevel = mutableMapOf<String, Int>()

        fun computeLevel(name: String, visited: MutableSet<String> = mutableSetOf()): Int {
            if (name in visited) return levels[name] ?: 0
            visited.add(name)
            val node = skillMap[name] ?: return 0
            val depLevel = node.dependencies.maxOfOrNull { computeLevel(it, visited) + 1 } ?: 0
            levels[name] = depLevel
            return depLevel
        }

        for (skill in skills) {
            computeLevel(skill.name)
        }

        val maxLevelVal = levels.values.maxOrNull() ?: 0
        val executionOrder = (0..maxLevelVal).map { level ->
            levels.filter { it.value == level }.keys.toList()
        }

        val criticalPath = mutableListOf<String>()
        val sortedByLevel = skills.sortedByDescending { levels[it.name] ?: 0 }
        var current = sortedByLevel.firstOrNull()?.name ?: ""
        while (current.isNotEmpty()) {
            criticalPath.add(current)
            val node = skillMap[current] ?: break
            current = node.dependencies.maxByOrNull { levels[it] ?: 0 } ?: ""
        }

        val totalDuration = levels.maxOfOrNull { (name, _) ->
            skillMap[name]?.estimatedDurationMs ?: 0L
        } ?: 0L

        return SkillDependencyGraph(
            skills = skills.associateBy { it.name }.mapValues { (_, v) -> v },
            executionOrder = executionOrder,
            criticalPath = criticalPath.reversed(),
            estimatedTotalDurationMs = totalDuration
        )
    }

    fun getOptimalParallelism(skills: List<String>): Int {
        val profileSkills = skills.mapNotNull { skillProfiles[it] }
        if (profileSkills.isEmpty()) return 1
        val parallelCount = profileSkills.count { it.supportsParallel }
        return parallelCount.coerceIn(1, config.maxConcurrentSkills)
    }

    fun getStatistics(skillName: String): SkillStatistics {
        val history = executionHistory[skillName] ?: return SkillStatistics(skillName, 0, 0, 0, 0.0, 0, 0, 0, 0.0, 0.0, 0, 0, 0)
        val total = history.size.toLong()
        val successes = history.count { it.success }.toLong()
        val failures = total - successes
        val durations = history.map { it.durationMs }.sorted()
        val avgDuration = if (durations.isNotEmpty()) durations.average() else 0.0
        val p50 = if (durations.isNotEmpty()) durations[(durations.size / 2).coerceAtMost(durations.size - 1)] else 0L
        val p95Idx = (durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)
        val p95 = if (durations.isNotEmpty()) durations[p95Idx] else 0L
        val p99Idx = (durations.size * 0.99).toInt().coerceAtMost(durations.size - 1)
        val p99 = if (durations.isNotEmpty()) durations[p99Idx] else 0L
        val avgRetries = history.map { it.attemptCount.toDouble() }.average()
        val successRate = if (total > 0) successes.toDouble() / total else 1.0
        val lastExec = history.lastOrNull()?.let { System.currentTimeMillis() - it.durationMs } ?: 0L
        return SkillStatistics(
            skillName = skillName, totalExecutions = total, successfulExecutions = successes,
            failedExecutions = failures, averageDurationMs = avgDuration, p50DurationMs = p50,
            p95DurationMs = p95, p99DurationMs = p99, averageRetries = avgRetries,
            successRate = successRate, lastExecutionMs = lastExec,
            averageInputSize = 0, averageOutputSize = 0
        )
    }

    fun getAllStatistics(): List<SkillStatistics> {
        return skillProfiles.keys.mapNotNull { getStatistics(it).takeIf { it.totalExecutions > 0 } }
    }

    fun getCacheHitRate(): Double {
        val entries = executionCache.values
        if (entries.isEmpty()) return 0.0
        val totalHits = entries.sumOf { it.hitCount.get().toLong() }
        val totalAccesses = totalHits + entries.size
        if (totalAccesses == 0L) return 0.0
        return totalHits.toDouble() / totalAccesses
    }

    fun invalidateCache(skillName: String) {
        val prefix = "$skillName:"
        executionCache.keys.filter { it.startsWith(prefix) }.forEach { executionCache.remove(it) }
    }

    fun invalidateAllCache() { executionCache.clear() }

    fun getCacheSize(): Int = executionCache.size

    fun getTopExecutedSkills(limit: Int = 5): List<Pair<String, Long>> {
        return totalExecutions.entries.sortedByDescending { it.value.get() }.take(limit).map { it.key to it.value.get() }
    }

    fun getTopFailedSkills(limit: Int = 5): List<Pair<String, Long>> {
        return totalFailures.entries.sortedByDescending { it.value.get() }.take(limit).map { it.key to it.value.get() }
    }

    fun getTopRetriedSkills(limit: Int = 5): List<Pair<String, Long>> {
        return totalRetries.entries.sortedByDescending { it.value.get() }.take(limit).map { it.key to it.value.get() }
    }

    private fun buildCacheKey(skillName: String, input: Any): String = "$skillName:${input.hashCode()}:${input.toString().length}"

    private fun cacheResult(key: String, result: Any) {
        if (executionCache.size >= MAX_CACHE_SIZE) return
        executionCache[key] = SkillExecutionCache(input = result, result = result, timestampMs = System.currentTimeMillis(), ttlMs = config.cacheTtlMs)
    }

    private fun prepareInput(input: Any, profile: SkillProfile): Any {
        return if (profile.inputComplexity > 1.5 && input is String && input.length > 10000) {
            input.take(5000)
        } else input
    }

    private fun prepareOutput(output: Any): Any = output

    private fun estimateMemoryUsage(input: Any, output: Any): Long {
        return input.toString().toByteArray().size.toLong() + output.toString().toByteArray().size.toLong()
    }

    private fun recordExecution(skillName: String, durationMs: Long, success: Boolean, attempts: Int) {
        executionHistory.computeIfAbsent(skillName) { CopyOnWriteArrayList() }.apply {
            add(SkillExecutionResult(skillName, null, durationMs, success, attempts))
            if (size > HISTORY_LIMIT) removeAt(0)
        }
        durationPercentiles.computeIfAbsent(skillName) { CopyOnWriteArrayList() }.apply {
            add(durationMs)
            if (size > HISTORY_LIMIT * 2) removeAt(0)
        }
    }

    private fun maintenanceCycle() {
        val now = System.currentTimeMillis()
        val toRemove = executionCache.filter { (now - it.value.timestampMs) > config.cacheTtlMs }
        toRemove.keys.forEach { executionCache.remove(it) }
    }

    fun updateConfig(update: SkillAdaptiveConfig.() -> SkillAdaptiveConfig): SkillAdaptiveConfig {
        val newConfig = update(config)
        return newConfig
    }

    fun resetStatistics(skillName: String) {
        executionHistory.remove(skillName)
        durationPercentiles.remove(skillName)
        totalExecutions.remove(skillName)
        totalFailures.remove(skillName)
        totalRetries.remove(skillName)
    }

    fun resetAll() {
        executionCache.clear()
        executionHistory.clear()
        durationPercentiles.clear()
        totalExecutions.clear()
        totalFailures.clear()
        totalRetries.clear()
    }

    private fun SkillAdaptiveConfig.adaptiveMaxRetries(profile: SkillProfile): Int {
        val base = if (profile.isDeterministic) 1 else 2
        return profile.averageDurationMs.let { if (it > 3000) base + 1 else base }
    }
}

class TimeoutCancellationException(message: String) : CancellationException(message)
