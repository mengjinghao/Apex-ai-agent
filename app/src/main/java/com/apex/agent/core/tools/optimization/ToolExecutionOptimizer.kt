package com.apex.agent.core.tools.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.time.*

enum class ToolExecutionStrategy {
    DIRECT, CACHED, PARALLEL, BATCHED, DEFERRED, SPECULATIVE
}

data class ToolExecutionPlan(
    val toolName: String,
    val parameters: Map<String, Any>,
    val strategy: ToolExecutionStrategy,
    val estimatedCost: Double,
    val estimatedDurationMs: Long,
    val dependencies: List<String> = emptyList(),
    val cacheKey: String? = null,
    val speculativeResults: List<Any>? = null
)

data class ToolCacheEntry(
    val key: String,
    val result: Any,
    val timestampMs: Long,
    val ttlMs: Long,
    val hitCount: AtomicInteger = AtomicInteger(1),
    val sizeBytes: Int = 0
)

data class ToolExecutionMetrics(
    val toolName: String,
    val totalInvocations: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val averageDurationMs: Double,
    val p95DurationMs: Double,
    val p99DurationMs: Double,
    val errorRate: Double,
    val averageInputSize: Int,
    val averageOutputSize: Int,
    val executionStrategyDistribution: Map<ToolExecutionStrategy, Int>
)

data class OptimizationSuggestion(
    val toolName: String,
    val suggestionType: SuggestionType,
    val description: String,
    val expectedImprovementPercent: Double,
    val priority: Int
)

enum class SuggestionType {
    ADD_CACHE, INCREASE_TTL, BATCH_INVOCATIONS, PARALLELIZE,
    REDUCE_INPUT, DEFER_EXECUTION, SPECULATIVE_EXECUTION, PREWARM_CACHE
}

data class ToolRegistry(
    val name: String,
    val category: String,
    val averageDurationMs: Long = 100L,
    val isCacheable: Boolean = true,
    val supportsBatching: Boolean = false,
    val supportsParallel: Boolean = false,
    val typicalInputSize: Int = 100,
    val typicalOutputSize: Int = 1000
)

data class ToolOptimizerConfig(
    val enableCache: Boolean = true,
    val enableSpeculativeExecution: Boolean = false,
    val enableAdaptiveStrategy: Boolean = true,
    val maxCacheSize: Int = 5000,
    val defaultCacheTtlMs: Long = 300000L,
    val adaptiveWindowSize: Int = 50,
    val maxParallelTools: Int = 4,
    val enableAutoSuggest: Boolean = true
)

class ToolExecutionOptimizer private constructor() {

    private val toolCache = ConcurrentHashMap<String, ToolCacheEntry>()
    private val executionMetrics = ConcurrentHashMap<String, ToolExecutionMetrics>()
    private val toolRegistry = ConcurrentHashMap<String, ToolRegistry>()
    private val durationHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
    private val strategyCounts = ConcurrentHashMap<String, ConcurrentHashMap<ToolExecutionStrategy, AtomicInteger>>()
    private val errorCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val totalInvocations = ConcurrentHashMap<String, AtomicLong>()
    private val inputSizes = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()
    private val outputSizes = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()
    private val config = ToolOptimizerConfig()
    private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: ToolExecutionOptimizer? = null

        fun getInstance(): ToolExecutionOptimizer {
            return instance ?: synchronized(this) {
                instance ?: ToolExecutionOptimizer().also { instance = it }
            }
        }

        private const val METRICS_HISTORY_SIZE = 200
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        registerCommonTools()
        if (config.enableAutoSuggest) {
            coroutineScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(60000L)
                    generateSuggestions()
                }
            }
        }
        if (config.enableCache) {
            coroutineScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(300000L)
                    evictStaleCacheEntries()
                }
            }
        }
    }

    private fun registerCommonTools() {
        registerTool(ToolRegistry("file_search", "search", 500L, true, false, true))
        registerTool(ToolRegistry("web_fetch", "network", 2000L, true, false, false))
        registerTool(ToolRegistry("code_analyze", "analysis", 1500L, false, false, true))
        registerTool(ToolRegistry("code_generate", "generation", 3000L, false, false, false))
        registerTool(ToolRegistry("database_query", "database", 200L, true, true, false))
        registerTool(ToolRegistry("shell_execute", "system", 1000L, false, false, false))
        registerTool(ToolRegistry("nlp_parse", "nlp", 100L, true, true, false))
        registerTool(ToolRegistry("image_process", "media", 3000L, true, false, false))
        registerTool(ToolRegistry("network_request", "network", 1500L, true, true, false))
        registerTool(ToolRegistry("data_transform", "data", 500L, true, false, true))
    }

    fun registerTool(registry: ToolRegistry) {
        toolRegistry[registry.name] = registry
        executionMetrics[registry.name] = ToolExecutionMetrics(
            toolName = registry.name, totalInvocations = 0, cacheHits = 0, cacheMisses = 0,
            averageDurationMs = registry.averageDurationMs.toDouble(), p95DurationMs = registry.averageDurationMs.toDouble(),
            p99DurationMs = registry.averageDurationMs.toDouble(), errorRate = 0.0,
            averageInputSize = registry.typicalInputSize, averageOutputSize = registry.typicalOutputSize,
            executionStrategyDistribution = emptyMap()
        )
    }

    fun getTool(name: String): ToolRegistry? = toolRegistry[name]

    fun getAllTools(): List<ToolRegistry> = toolRegistry.values.toList()

    fun determineStrategy(toolName: String, inputSize: Int): ToolExecutionStrategy {
        val registry = toolRegistry[toolName] ?: return ToolExecutionStrategy.DIRECT
        if (config.enableCache && registry.isCacheable && isCacheWarm(toolName)) {
            return ToolExecutionStrategy.CACHED
        }
        if (registry.supportsBatching && inputSize > 100) {
            return ToolExecutionStrategy.BATCHED
        }
        if (registry.supportsParallel && config.enableAdaptiveStrategy) {
            return ToolExecutionStrategy.PARALLEL
        }
        ToolExecutionStrategy.DIRECT
    }

    fun createExecutionPlan(toolName: String, parameters: Map<String, Any>): ToolExecutionPlan {
        val registry = toolRegistry[toolName] ?: return ToolExecutionPlan(
            toolName, parameters, ToolExecutionStrategy.DIRECT, 1.0, 100L)
        val inputSize = parameters.values.sumOf { it.toString().length }
        val strategy = determineStrategy(toolName, inputSize)
        val cacheKey = if (registry.isCacheable) {
            "${toolName}:${hashParameters(parameters)}"
        } else null
        val duration = registry.averageDurationMs * (1 + inputSize.toDouble() / 10000)
        val cost = when (strategy) {
            ToolExecutionStrategy.CACHED -> 0.1
            ToolExecutionStrategy.PARALLEL -> 0.8
            ToolExecutionStrategy.BATCHED -> 0.6
            ToolExecutionStrategy.DEFERRED -> 0.3
            ToolExecutionStrategy.SPECULATIVE -> 1.5
            ToolExecutionStrategy.DIRECT -> 1.0
        }
        ToolExecutionPlan(
            toolName = toolName,
            parameters = parameters,
            strategy = strategy,
            estimatedCost = cost,
            estimatedDurationMs = duration,
            cacheKey = cacheKey
        )
    }

    suspend fun executeOptimized(toolName: String, parameters: Map<String, Any>, executor: suspend (Map<String, Any>) -> Any): Any {
        val startTime = System.nanoTime()
        totalInvocations.computeIfAbsent(toolName) { AtomicLong(0) }.incrementAndGet()
        recordInputSize(toolName, parameters)

        val strategy = strategyCounts.computeIfAbsent(toolName) { ConcurrentHashMap() }
        strategy.computeIfAbsent(ToolExecutionStrategy.DIRECT) { AtomicInteger(0) }.incrementAndGet()

        val plan = createExecutionPlan(toolName, parameters)
        recordDuration(toolName, registry?.averageDurationMs ?: 100L)

        if (plan.cacheKey != null && config.enableCache) {
            val cached = toolCache[plan.cacheKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < cached.ttlMs) {
                cached.hitCount.incrementAndGet()
                recordCacheHit(toolName)
                recordOutputSize(toolName, cached.result)
                return cached.result
            }
            recordCacheMiss(toolName)
        }

        return try {
            val result = executor(parameters)
            recordOutputSize(toolName, result)
            if (plan.cacheKey != null && config.enableCache) {
                cacheResult(toolName, plan.cacheKey, result)
            }
            result
        } catch (e: Exception) {
            recordError(toolName)
            throw e
        }
    }

    suspend fun executeOptimizedWithMetrics(
        toolName: String,
        parameters: Map<String, Any>,
        executor: suspend (Map<String, Any>) -> Any
    ): Pair<Any, ToolExecutionPlan> {
        val plan = createExecutionPlan(toolName, parameters)
        val startTime = System.currentTimeMillis()
        val result = executeOptimized(toolName, parameters, executor)
        val duration = System.currentTimeMillis() - startTime
        Pair(result, plan)
    }

    fun executeBatch(toolName: String, batchParams: List<Map<String, Any>>, executor: (Map<String, Any>) -> Any): List<Any> {
        batchParams.map { executor(it) }
    }

    suspend fun executeBatchOptimized(toolName: String, batchParams: List<Map<String, Any>>, executor: suspend (Map<String, Any>) -> Any): List<Any> {
        val registry = toolRegistry[toolName]
        if (registry?.supportsParallel == true && config.maxParallelTools > 1) {
            batchParams.map { param ->
                scope?.async(Dispatchers.Default) {
                    executeOptimized(toolName, param, executor)
                }
            }.mapNotNull { it?.await() }
        } else {
            batchParams.map { executeOptimized(toolName, it, executor) }
        }
    }

    fun getCachedResult(cacheKey: String): Any? {
        val entry = toolCache[cacheKey] ?: return null
        if (System.currentTimeMillis() - entry.timestampMs > entry.ttlMs) {
            toolCache.remove(cacheKey)
            return null
        }
        entry.hitCount.incrementAndGet()
        entry.result
    }

    fun invalidateCache(toolName: String) {
        val prefix = "$toolName:"
        val keysToRemove = toolCache.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { toolCache.remove(it) }
    }

    fun invalidateAllCache() { toolCache.clear() }

    fun invalidateCacheByKey(key: String) { toolCache.remove(key) }

    fun getCachedEntry(key: String): ToolCacheEntry? = toolCache[key]

    fun getCacheSize(): Int = toolCache.size

    private fun cacheResult(toolName: String, cacheKey: String, result: Any) {
        if (toolCache.size >= config.maxCacheSize) return
        val size = result.toString().toByteArray().size
        toolCache[cacheKey] = ToolCacheEntry(
            key = cacheKey,
            result = result,
            timestampMs = System.currentTimeMillis(),
            ttlMs = toolRegistry[toolName]?.let { getAdaptiveTtl(toolName) } ?: config.defaultCacheTtlMs,
            sizeBytes = size
        )
    }

    private fun getAdaptiveTtl(toolName: String): Long {
        val history = durationHistory[toolName]
        if (history == null || history.size < 10) return config.defaultCacheTtlMs
        val avgDuration = history.average().toLong()
        (avgDuration * 100).coerceIn(10000L, 3600000L)
    }

    private fun isCacheWarm(toolName: String): Boolean {
        val prefix = "$toolName:"
        toolCache.keys.count { it.startsWith(prefix) } >= 3
    }

    fun getMetrics(toolName: String): ToolExecutionMetrics {
        val invocations = totalInvocations[toolName]?.get() ?: 0
        val cacheHits = executionMetrics[toolName]?.cacheHits ?: 0
        val cacheMisses = executionMetrics[toolName]?.cacheMisses ?: 0
        val durations = durationHistory[toolName]
        val avgDuration = if (durations != null && durations.isNotEmpty()) durations.average() else 0.0
        val sorted = durations?.sorted() ?: emptyList()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val errors = errorCounts[toolName]?.get() ?: 0
        val errorRate = if (invocations > 0) errors.toDouble() / invocations else 0.0
        val inputs = inputSizes[toolName]
        val outputs = outputSizes[toolName]
        val avgInput = if (inputs != null && inputs.isNotEmpty()) inputs.average() else 0.0
        val avgOutput = if (outputs != null && outputs.isNotEmpty()) outputs.average() else 0.0
        val stratDist = strategyCounts[toolName]?.entries?.associate { it.key to it.value.get() } ?: emptyMap()
        ToolExecutionMetrics(
            toolName = toolName,
            totalInvocations = invocations,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            averageDurationMs = avgDuration,
            p95DurationMs = p95,
            p99DurationMs = p99,
            errorRate = errorRate,
            averageInputSize = avgInput.toInt(),
            averageOutputSize = avgOutput.toInt(),
            executionStrategyDistribution = stratDist
        )
    }

    fun getAllMetrics(): List<ToolExecutionMetrics> {
        toolRegistry.keys.map { getMetrics(it) }
    }

    fun generateSuggestions(): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        for (toolName in toolRegistry.keys) {
            val metrics = getMetrics(toolName)
            val registry = toolRegistry[toolName] ?: continue
            if (registry.isCacheable && metrics.cacheHits < 5 && metrics.totalInvocations > 20) {
                suggestions.add(OptimizationSuggestion(
                    toolName, SuggestionType.ADD_CACHE,
                    "High invocation count but low cache usage for $toolName", 30.0, 3))
            }
            if (metrics.averageDurationMs > 2000 && registry.supportsBatching) {
                suggestions.add(OptimizationSuggestion(
                    toolName, SuggestionType.BATCH_INVOCATIONS,
                    "Slow tool $toolName could benefit from batching", 40.0, 2))
            }
            if (metrics.p95DurationMs > metrics.averageDurationMs * 3) {
                suggestions.add(OptimizationSuggestion(
                    toolName, SuggestionType.REDUCE_INPUT,
                    "High latency variance in $toolName, consider reducing input", 20.0, 4))
            }
            if (metrics.errorRate > 0.1) {
                suggestions.add(OptimizationSuggestion(
                    toolName, SuggestionType.SPECULATIVE_EXECUTION,
                    "High error rate in $toolName, speculative execution may help", 50.0, 1))
            }
            if (metrics.totalInvocations > 100 && metrics.errorRate < 0.05 && registry.isCacheable) {
                suggestions.add(OptimizationSuggestion(
                    toolName, SuggestionType.PREWARM_CACHE,
                    "Stable tool $toolName is a good candidate for cache prewarming", 25.0, 5))
            }
        }
        suggestions.sortedByDescending { it.priority }
    }

    fun getCacheHitRate(toolName: String): Double {
        val metrics = executionMetrics[toolName] ?: return 0.0
        val total = metrics.cacheHits + metrics.cacheMisses
        if (total == 0L) return 0.0
        metrics.cacheHits.toDouble() / total
    }

    fun getOverallCacheHitRate(): Double {
        var totalHits = 0L
        var totalMisses = 0L
        for (metrics in executionMetrics.values) {
            totalHits += metrics.cacheHits
            totalMisses += metrics.cacheMisses
        }
        val total = totalHits + totalMisses
        if (total == 0L) return 0.0
        totalHits.toDouble() / total
    }

    fun getHotCacheEntries(limit: Int = 10): List<ToolCacheEntry> {
        toolCache.values.sortedByDescending { it.hitCount.get() }.take(limit)
    }

    fun getColdCacheEntries(limit: Int = 10): List<ToolCacheEntry> {
        toolCache.values.sortedBy { it.hitCount.get() }.take(limit)
    }

    private fun hashParameters(params: Map<String, Any>): String {
        params.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
            .hashCode().toLong().let { it.toString(16) }
    }

    private fun recordCacheHit(toolName: String) {
        executionMetrics.compute(toolName) { _, existing ->
            if (existing != null) existing.copy(cacheHits = existing.cacheHits + 1) else null
        }
    }

    private fun recordCacheMiss(toolName: String) {
        executionMetrics.compute(toolName) { _, existing ->
            if (existing != null) existing.copy(cacheMisses = existing.cacheMisses + 1) else null
        }
    }

    private fun recordError(toolName: String) {
        errorCounts.computeIfAbsent(toolName) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun recordDuration(toolName: String, durationMs: Long) {
        durationHistory.computeIfAbsent(toolName) { CopyOnWriteArrayList() }.apply {
            add(durationMs)
            if (size > METRICS_HISTORY_SIZE) removeAt(0)
        }
    }

    private fun recordInputSize(toolName: String, params: Map<String, Any>) {
        val size = params.values.sumOf { it.toString().length }
        inputSizes.computeIfAbsent(toolName) { CopyOnWriteArrayList() }.apply {
            add(size)
            if (size > METRICS_HISTORY_SIZE) removeAt(0)
        }
    }

    private fun recordOutputSize(toolName: String, result: Any) {
        val size = result.toString().length
        outputSizes.computeIfAbsent(toolName) { CopyOnWriteArrayList() }.apply {
            add(size)
            if (size > METRICS_HISTORY_SIZE) removeAt(0)
        }
    }

    private fun evictStaleCacheEntries() {
        val now = System.currentTimeMillis()
        val toRemove = toolCache.filter { (now - it.value.timestampMs) > it.value.ttlMs }
        toRemove.keys.forEach { toolCache.remove(it) }
    }

    fun resetMetrics(toolName: String) {
        executionMetrics.remove(toolName)
        durationHistory.remove(toolName)
        errorCounts.remove(toolName)
        totalInvocations.remove(toolName)
        inputSizes.remove(toolName)
        outputSizes.remove(toolName)
        strategyCounts.remove(toolName)
    }

    fun resetAll() {
        toolCache.clear()
        executionMetrics.clear()
        durationHistory.clear()
        errorCounts.clear()
        totalInvocations.clear()
        inputSizes.clear()
        outputSizes.clear()
        strategyCounts.clear()
    }
}
