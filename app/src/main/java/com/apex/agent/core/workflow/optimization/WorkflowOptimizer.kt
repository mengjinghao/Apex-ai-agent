package com.apex.agent.core.workflow.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WorkflowOptimizer(private val name: String = "workflow-optimizer") {
    data class WorkflowStep(
        val id: String,
        val name: String,
        val type: StepType,
        val estimatedDurationMs: Long = 1000L,
        val dependencies: List<String> = emptyList(),
        val retryCount: Int = 0,
        val timeoutMs: Long = 30000L
    )

    enum class StepType {
        COMPUTE, IO, NETWORK, LLM_CALL, TOOL_EXECUTION, DATA_TRANSFORM, CONDITIONAL, PARALLEL
    }

    data class WorkflowPlan(
        val workflowId: String,
        val steps: List<WorkflowStep>,
        val parallelGroups: List<List<String>>,
        val criticalPath: List<String>,
        val estimatedTotalMs: Long,
        val optimizationScore: Double
    )

    data class OptimizationMetrics(
        val totalWorkflows: Long,
        val totalSteps: Long,
        val optimizedSteps: Long,
        val averageCriticalPathMs: Double,
        val averageParallelismGain: Double,
        val cacheHitRate: Double,
        val totalTimeSavedMs: Long
    )

    private val logger = LoggerFactory.getLogger("WorkflowOptimizer-$name")
    private val stepTimings = ConcurrentHashMap<String, MutableList<Long>>()
    private val stepResults = ConcurrentHashMap<String, Any?>()
    private val optimizedPlans = ConcurrentHashMap<String, WorkflowPlan>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val totalWorkflows = AtomicLong(0)
    private val totalSteps = AtomicLong(0)
    private val optimizedSteps = AtomicLong(0)
    private val totalTimeSaved = AtomicLong(0)
    private val stepTimingHits = AtomicLong(0)
    private val stepTimingMisses = AtomicLong(0)

    suspend fun analyzeWorkflow(workflowId: String, steps: List<WorkflowStep>): WorkflowPlan {
        totalWorkflows.incrementAndGet()
        totalSteps.addAndGet(steps.size.toLong())

        val dependencies = buildDependencyMap(steps)
        val parallelGroups = findParallelGroups(steps, dependencies)
        val criticalPath = findCriticalPath(steps, dependencies)
        val estimatedTotal = criticalPath.sumOf { stepId ->
            steps.find { it.id == stepId }?.estimatedDurationMs ?: 1000
        }
        val sequentialTotal = steps.sumOf { it.estimatedDurationMs }
        val optimizationScore = if (sequentialTotal > 0)
            (sequentialTotal - estimatedTotal).toDouble() / sequentialTotal else 0.0

        val plan = WorkflowPlan(
            workflowId = workflowId,
            steps = steps,
            parallelGroups = parallelGroups,
            criticalPath = criticalPath,
            estimatedTotalMs = estimatedTotal,
            optimizationScore = optimizationScore
        )

        optimizedPlans[workflowId] = plan
        optimizedSteps.addAndGet(steps.size.toLong())
        totalTimeSaved.addAndGet(sequentialTotal - estimatedTotal)

        return plan
    }

    suspend fun recordStepTiming(stepId: String, durationMs: Long) {
        val timings = stepTimings.getOrPut(stepId) { mutableListOf() }
        timings.add(durationMs)
        if (timings.size > 20) timings.removeAt(0)
    }

    suspend fun getEstimatedStepDuration(stepId: String): Long {
        val timings = stepTimings[stepId]
        return if (timings != null && timings.isNotEmpty()) {
            stepTimingHits.incrementAndGet()
            timings.average().toLong()
        } else {
            stepTimingMisses.incrementAndGet()
            1000L
        }
    }

    suspend fun cacheStepResult(stepId: String, result: Any?, ttlMs: Long = 60000L) {
        stepResults[stepId] = result
    }

    fun getCachedStepResult(stepId: String): Any? = stepResults[stepId]

    suspend fun getOptimizedExecution(workflowId: String): List<List<String>> {
        val plan = optimizedPlans[workflowId] ?: return emptyList()
        return plan.parallelGroups
    }

    fun getMetrics(): OptimizationMetrics {
        val totalHits = stepTimingHits.get()
        val totalMisses = stepTimingMisses.get()
        return OptimizationMetrics(
            totalWorkflows = totalWorkflows.get(),
            totalSteps = totalSteps.get(),
            optimizedSteps = optimizedSteps.get(),
            averageCriticalPathMs = 0.0,
            averageParallelismGain = 0.0,
            cacheHitRate = if (totalHits + totalMisses > 0)
                totalHits.toDouble() / (totalHits + totalMisses) else 0.0,
            totalTimeSavedMs = totalTimeSaved.get()
        )
    }

    private fun buildDependencyMap(steps: List<WorkflowStep>): Map<String, Set<String>> {
        val deps = mutableMapOf<String, Set<String>>()
        for (step in steps) {
            deps[step.id] = step.dependencies.toSet()
        }
        return deps
    }

    private fun findParallelGroups(steps: List<WorkflowStep>, dependencies: Map<String, Set<String>>): List<List<String>> {
        val levels = mutableListOf<MutableList<String>>()
        val nodeLevels = mutableMapOf<String, Int>()

        fun getLevel(stepId: String): Int {
            nodeLevels[stepId]?.let { return it }
            val deps = dependencies[stepId] ?: emptySet()
            val level = if (deps.isEmpty()) 0
            else (deps.mapNotNull { nodeLevels[it] }.maxOrNull() ?: 0) + 1
            nodeLevels[stepId] = level
            while (levels.size <= level) levels.add(mutableListOf())
            levels[level].add(stepId)
            return level
        }

        for (step in steps) { getLevel(step.id) }
        return levels
    }

    private fun findCriticalPath(steps: List<WorkflowStep>, dependencies: Map<String, Set<String>>): List<String> {
        val longestPath = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(stepId: String, path: MutableList<String>) {
            if (stepId in visited) return
            visited.add(stepId)
            path.add(stepId)

            val deps = dependencies[stepId] ?: emptySet()
            if (deps.isEmpty()) {
                if (path.sumOf { id -> steps.find { it.id == id }?.estimatedDurationMs ?: 0 } >
                    longestPath.sumOf { id -> steps.find { it.id == id }?.estimatedDurationMs ?: 0 }) {
                    longestPath.clear()
                    longestPath.addAll(path)
                }
            } else {
                for (dep in deps) {
                    dfs(dep, path)
                }
            }

            path.removeAt(path.lastIndex)
            visited.remove(stepId)
        }

        for (step in steps) {
            if (step.dependencies.isEmpty()) {
                dfs(step.id, mutableListOf())
            }
        }

        return longestPath
    }
}

class WorkflowCache(private val name: String = "workflow-cache") {
    data class CachedStep(
        val stepId: String,
        val workflowId: String,
        val input: Map<String, Any?>,
        val output: Any?,
        val cachedAt: Long,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CachedStep>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val maxEntries = 500

    init {
        scope.launch {
            while (true) {
                delay(60000)
                cleanup()
            }
        }
    }

    fun getOrNull(stepId: String, workflowId: String, input: Map<String, Any?>): Any? {
        val key = buildKey(stepId, workflowId, input)
        val entry = cache[key]
        if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
            hits.incrementAndGet()
            return entry.output
        }
        if (entry != null) {
            cache.remove(key)
            evictions.incrementAndGet()
        }
        misses.incrementAndGet()
        return null
    }

    fun put(stepId: String, workflowId: String, input: Map<String, Any?>, output: Any?, ttlMs: Long = 60000L) {
        while (cache.size >= maxEntries) {
            val oldest = cache.minByOrNull { it.value.cachedAt }?.key ?: break
            cache.remove(oldest)
            evictions.incrementAndGet()
        }
        val key = buildKey(stepId, workflowId, input)
        val now = System.currentTimeMillis()
        cache[key] = CachedStep(stepId, workflowId, input, output, now, now + ttlMs)
    }

    fun invalidateWorkflow(workflowId: String) {
        cache.entries.removeAll { it.value.workflowId == workflowId }
    }

    fun invalidateStep(stepId: String) {
        cache.entries.removeAll { it.value.stepId == stepId }
    }

    fun clear() {
        cache.clear()
        hits.set(0)
        misses.set(0)
        evictions.set(0)
    }

    fun getHitRate(): Double {
        val total = hits.get() + misses.get()
        return if (total > 0) hits.get().toDouble() / total else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "size" to cache.size,
        "hits" to hits.get(),
        "misses" to misses.get(),
        "evictions" to evictions.get(),
        "hitRate" to getHitRate()
    )

    private fun buildKey(stepId: String, workflowId: String, input: Map<String, Any?>): String {
        return "$workflowId::$stepId::${input.hashCode()}"
    }

    fun shutdown() { scope.cancel() }
}

class WorkflowScheduler(private val name: String = "workflow-scheduler") {
    data class ScheduledWorkflow(
        val workflowId: String,
        val cronExpression: String,
        val context: Map<String, Any>,
        val maxRetries: Int = 3,
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val logger = LoggerFactory.getLogger("WorkflowScheduler-$name")
    private val schedules = ConcurrentHashMap<String, ScheduledWorkflow>()
    private val executionHistory = CopyOnWriteArrayList<ExecutionRecord>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val maxHistorySize = 1000
    private val scheduleCheckIntervalMs = 10000L

    data class ExecutionRecord(
        val workflowId: String,
        val startTime: Long,
        val endTime: Long,
        val success: Boolean,
        val error: String? = null,
        val durationMs: Long
    )

    data class SchedulerMetrics(
        val totalSchedules: Int,
        val activeSchedules: Int,
        val totalExecutions: Long,
        val successfulExecutions: Long,
        val failedExecutions: Long,
        val averageExecutionDurationMs: Double
    )

    init {
        scope.launch {
            while (true) {
                delay(scheduleCheckIntervalMs)
                checkSchedules()
            }
        }
    }

    fun registerSchedule(schedule: ScheduledWorkflow) {
        schedules[schedule.workflowId] = schedule
        logger.info("Registered schedule: {} [{}]", schedule.workflowId, schedule.cronExpression)
    }

    fun unregisterSchedule(workflowId: String) {
        schedules.remove(workflowId)
    }

    fun enableSchedule(workflowId: String) {
        schedules.computeIfPresent(workflowId) { _, s -> s.copy(enabled = true) }
    }

    fun disableSchedule(workflowId: String) {
        schedules.computeIfPresent(workflowId) { _, s -> s.copy(enabled = false) }
    }

    fun getSchedule(workflowId: String): ScheduledWorkflow? = schedules[workflowId]

    fun getAllSchedules(): List<ScheduledWorkflow> = schedules.values.toList()

    fun recordExecution(workflowId: String, success: Boolean, durationMs: Long, error: String? = null) {
        val record = ExecutionRecord(
            workflowId = workflowId,
            startTime = System.currentTimeMillis() - durationMs,
            endTime = System.currentTimeMillis(),
            success = success,
            error = error,
            durationMs = durationMs
        )
        executionHistory.add(record)
        while (executionHistory.size > maxHistorySize) executionHistory.removeAt(0)
    }

    fun getExecutionHistory(workflowId: String, limit: Int = 10): List<ExecutionRecord> {
        return executionHistory.filter { it.workflowId == workflowId }.takeLast(limit)
    }

    fun getMetrics(): SchedulerMetrics {
        val total = executionHistory.size
        val successful = executionHistory.count { it.success }
        return SchedulerMetrics(
            totalSchedules = schedules.size,
            activeSchedules = schedules.count { it.value.enabled },
            totalExecutions = total.toLong(),
            successfulExecutions = successful.toLong(),
            failedExecutions = (total - successful).toLong(),
            averageExecutionDurationMs = if (total > 0)
                executionHistory.sumOf { it.durationMs }.toDouble() / total else 0.0
        )
    }

    fun shutdown() {
        scope.cancel()
        schedules.clear()
    }

    private suspend fun checkSchedules() {
        for ((id, schedule) in schedules) {
            if (!schedule.enabled) continue
            if (shouldExecute(schedule)) {
                logger.debug("Triggering scheduled workflow: {}", id)
                scope.launch {
                    val start = System.currentTimeMillis()
                    try {
                        // In production, this would call the workflow engine
                        val duration = System.currentTimeMillis() - start
                        recordExecution(id, true, duration)
                    } catch (e: Exception) {
                        val duration = System.currentTimeMillis() - start
                        recordExecution(id, false, duration, e.message)
                    }
                }
            }
        }
    }

    private fun shouldExecute(schedule: ScheduledWorkflow): Boolean {
        return true
    }
}

class WorkflowStepOptimizer(private val name: String = "step-optimizer") {
    data class StepOptimization(
        val stepId: String,
        val canBeCached: Boolean,
        val canBeParallelized: Boolean,
        val suggestedTimeoutMs: Long,
        val suggestedRetryCount: Int,
        val estimatedInputSize: Long,
        val estimatedOutputSize: Long
    )

    private val stepAnalysis = ConcurrentHashMap<String, StepOptimization>()
    private val inputSizeHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val outputSizeHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val executionTimeHistory = ConcurrentHashMap<String, MutableList<Long>>()

    fun analyzeStep(stepId: String, input: Any?, output: Any?, durationMs: Long) {
        val inputSize = estimateSize(input)
        val outputSize = estimateSize(output)

        inputSizeHistory.computeIfAbsent(stepId) { mutableListOf() }.add(inputSize)
        outputSizeHistory.computeIfAbsent(stepId) { mutableListOf() }.add(outputSize)
        executionTimeHistory.computeIfAbsent(stepId) { mutableListOf() }.add(durationMs)

        trimHistory(inputSizeHistory[stepId]!!)
        trimHistory(outputSizeHistory[stepId]!!)
        trimHistory(executionTimeHistory[stepId]!!)

        val avgExecTime = executionTimeHistory[stepId]?.average() ?: durationMs.toDouble()
        val avgInputSize = inputSizeHistory[stepId]?.average() ?: inputSize.toDouble()
        val avgOutputSize = outputSizeHistory[stepId]?.average() ?: outputSize.toDouble()

        val canBeCached = output != null && outputSize < 1024 * 1024
        val canBeParallelized = avgExecTime > 100
        val suggestedTimeout = (avgExecTime * 3).toLong().coerceAtLeast(5000)
        val suggestedRetries = if (avgExecTime > 10000) 3 else 1

        stepAnalysis[stepId] = StepOptimization(
            stepId = stepId,
            canBeCached = canBeCached,
            canBeParallelized = canBeParallelized,
            suggestedTimeoutMs = suggestedTimeout,
            suggestedRetryCount = suggestedRetries,
            estimatedInputSize = avgInputSize.toLong(),
            estimatedOutputSize = avgOutputSize.toLong()
        )
    }

    fun getOptimization(stepId: String): StepOptimization? = stepAnalysis[stepId]

    fun clear() {
        stepAnalysis.clear()
        inputSizeHistory.clear()
        outputSizeHistory.clear()
        executionTimeHistory.clear()
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "stepsAnalyzed" to stepAnalysis.size,
        "cacheableSteps" to stepAnalysis.count { it.value.canBeCached },
        "parallelizableSteps" to stepAnalysis.count { it.value.canBeParallelized }
    )

    private fun estimateSize(obj: Any?): Long {
        if (obj == null) return 0L
        return when (obj) {
            is String -> obj.length.toLong()
            is ByteArray -> obj.size.toLong()
            is List<*> -> obj.size * 100L
            is Map<*, *> -> obj.size * 200L
            else -> 100L
        }
    }

    private fun trimHistory(list: MutableList<Long>) {
        while (list.size > 20) list.removeAt(0)
    }
}
