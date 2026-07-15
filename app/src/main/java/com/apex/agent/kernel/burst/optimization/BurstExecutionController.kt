package com.apex.agent.kernel.burst.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class BurstExecutionPlan(
    val planId: String,
    val mode: BurstMode,
    val priority: Int,
    val tasks: List<BurstTaskSpec>,
    val strategy: ExecutionStrategy,
    val estimatedCost: Double,
    val estimatedDurationMs: Long,
    val parallelism: Int,
    val fallbackPlanId: String? = null
)

data class BurstTaskSpec(
    val id: String,
    val skillName: String,
    val input: Any,
    val timeoutMs: Long = 30000L,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val priority: Int = 0,
    val resourceProfile: ResourceProfile = ResourceProfile()
)

data class ResourceProfile(
    val expectedCpuPercent: Double = 50.0,
    val expectedMemoryMb: Long = 64,
    val expectedNetworkKbps: Long = 0,
    val expectedDiskIops: Int = 0
)

enum class BurstMode {
    AGGRESSIVE, BALANCED, EFFICIENT, LOW_POWER, BATCH
}

enum class ExecutionStrategy {
    SEQUENTIAL, PARALLEL, PIPELINED, SPECULATIVE, ADAPTIVE
}

data class BurstPerformanceSnapshot(
    val timestampMs: Long,
    val activeTasks: Int,
    val queuedTasks: Int,
    val completedTasks: Long,
    val failedTasks: Long,
    val cpuLoadPercent: Double,
    val memoryUsedMb: Long,
    val throughputPerSecond: Double,
    val averageLatencyMs: Double,
    val mode: BurstMode
)

data class BurstAdaptiveConfig(
    val aggressiveThreshold: Double = 0.9,
    val balancedThreshold: Double = 0.6,
    val efficientThreshold: Double = 0.3,
    val maxParallelTasks: Int = 8,
    val minParallelTasks: Int = 1,
    val cooldownPeriodMs: Long = 10000L,
    val backoffFactor: Double = 1.5,
    val adaptationRate: Double = 0.3
)

data class ResourceAvailability(
    val freeMemoryMb: Long,
    val freeCpuPercent: Double,
    val freeNetworkKbps: Long,
    val freeDiskIops: Int,
    val batteryLevelPercent: Float,
    val isCharging: Boolean
)

data class BurstExecutionMetrics(
    val tasksSubmitted: Long,
    val tasksCompleted: Long,
    val tasksFailed: Long,
    val totalExecutionTimeMs: Long,
    val averageTaskDurationMs: Double,
    val throughputBurst: Double,
    val modeSwitches: Int,
    val resourceUtilizationPercent: Double,
    val cacheHitRate: Double,
    val isCurrentlyBursting: Boolean
)

class BurstExecutionController private constructor() {

    private val activePlans = ConcurrentHashMap<String, BurstExecutionPlan>()
        private val completedTasks = AtomicLong(0)
        private val failedTasks = AtomicLong(0)
        private val submittedTasks = AtomicLong(0)
        private val modeSwitchCount = AtomicInteger(0)
        private var currentMode = BurstMode.BALANCED
    private val taskDurations = CopyOnWriteArrayList<Long>()
        private val throughputSamples = CopyOnWriteArrayList<Double>()
        private val config = BurstAdaptiveConfig()
        private var isBursting = false
    private var lastBurstTimeMs = AtomicLong(0)
        private var scope: CoroutineScope? = null
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var instance: BurstExecutionController? = null

        fun getInstance(): BurstExecutionController {
            return instance ?: synchronized(this) {
                instance ?: BurstExecutionController().also { instance = it }
            }
        }
        private const val THROUGHPUT_WINDOW = 50
        private const val DURATION_HISTORY_SIZE = 500
        private const val DEFAULT_BURST_TIMEOUT_MS = 30000L
    }
        fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5000L)
                evaluateAndAdapt()
            }
        }
    }
        fun createPlan(
        mode: BurstMode = currentMode,
        tasks: List<BurstTaskSpec>,
        strategy: ExecutionStrategy = ExecutionStrategy.ADAPTIVE
    ): BurstExecutionPlan {
        val planId = "burst_${System.currentTimeMillis()}_${tasks.hashCode()}"
        val totalDuration = tasks.sumOf { it.timeoutMs }
        val parallelTasks = calculateOptimalParallelism(tasks)
        val cost = when (mode) {
            BurstMode.AGGRESSIVE -> 2.0
            BurstMode.BALANCED -> 1.0
            BurstMode.EFFICIENT -> 0.5
            BurstMode.LOW_POWER -> 0.25
            BurstMode.BATCH -> 0.3
        }

        BurstExecutionPlan(
            planId = planId,
            mode = mode,
            priority = tasks.maxOfOrNull { it.priority } ?: 0,
            tasks = tasks,
            strategy = strategy,
            estimatedCost = cost,
            estimatedDurationMs = totalDuration / calculateOptimalParallelism(tasks),
            parallelism = parallelTasks
        )
    }

    suspend fun executePlan(plan: BurstExecutionPlan, executor: suspend (BurstTaskSpec) -> Any): Map<String, Any> {
        activePlans[plan.planId] = plan
        submittedTasks.addAndGet(plan.tasks.size.toLong())
        if (!isBursting) {
            isBursting = true
            lastBurstTimeMs.set(System.currentTimeMillis())
        if (plan.mode != currentMode) {
                switchMode(plan.mode)
            }
        }
        val results = ConcurrentHashMap<String, Any>()
        val errors = CopyOnWriteArrayList<Pair<String, Exception>>()
        when (plan.strategy) {
            ExecutionStrategy.SEQUENTIAL -> {
                for (task in plan.tasks) {
                    try {
                        results[task.id] = executor(task)
                        recordSuccess(task)
                    } catch (e: Exception) {
                        errors.add(Pair(task.id, e))
                        recordFailure(task)
                    }
                }
            }
            ExecutionStrategy.PARALLEL -> {
                val deferred = plan.tasks.map { task ->
                    scope?.async(Dispatchers.Default) {
                        try {
                            results[task.id] = executor(task)
                            recordSuccess(task)
                        } catch (e: Exception) {
                            errors.add(Pair(task.id, e))
                            recordFailure(task)
                        }
                    }
                }
                deferred.forEach { it?.await() }
            }
            ExecutionStrategy.PIPELINED -> {
                val channel = Channel<BurstTaskSpec>(
                    capacity = plan.parallelism,
                    onBufferOverflow = BufferOverflow.SUSPEND
                )
        val consumers = (1..plan.parallelism).map { _ ->
                    scope?.async(Dispatchers.Default) {
                        for (task in channel) {
                            try {
                                results[task.id] = executor(task)
                                recordSuccess(task)
                            } catch (e: Exception) {
                                errors.add(Pair(task.id, e))
                                recordFailure(task)
                            }
                        }
                    }
                }
        for (task in plan.tasks) {
                    channel.send(task)
                }
                channel.close()
                consumers.forEach { it?.await() }
            }
            ExecutionStrategy.SPECULATIVE -> {
                val completion = CompletableDeferred<Map<String, Any>>()
                scope?.launch(Dispatchers.Default) {
                    val fastPath = plan.tasks.take(plan.parallelism)
        val slowPath = plan.tasks.drop(plan.parallelism)
        val fastResults = fastPath.map { task ->
                        async {
                            try { results[task.id] = executor(task); recordSuccess(task) }
                            catch (e: Exception) { errors.add(Pair(task.id, e)); recordFailure(task) }
                        }
                    }
                    fastResults.forEach { it.await() }
        val slowResults = slowPath.map { task ->
                        async {
                            try { results[task.id] = executor(task); recordSuccess(task) }
                            catch (e: Exception) { errors.add(Pair(task.id, e)); recordFailure(task) }
                        }
                    }
                    slowResults.forEach { it.await() }
                    completion.complete(results.toMap())
                }
                completion.await()
            }
            ExecutionStrategy.ADAPTIVE -> {
                val strategy = selectOptimalStrategy(plan)
        val adaptedPlan = plan.copy(strategy = strategy)
                executePlan(adaptedPlan, executor)
            }
        }

        activePlans.remove(plan.planId)
        results.toMap()
    }

    suspend fun burstExecute(
        tasks: List<BurstTaskSpec>,
        executor: suspend (BurstTaskSpec) -> Any
    ): Map<String, Any> {
        val plan = createPlan(mode = BurstMode.AGGRESSIVE, tasks = tasks, strategy = ExecutionStrategy.PARALLEL)
        executePlan(plan, executor)
    }

    suspend fun executeWithMode(
        mode: BurstMode,
        tasks: List<BurstTaskSpec>,
        executor: suspend (BurstTaskSpec) -> Any
    ): Map<String, Any> {
        val plan = createPlan(mode = mode, tasks = tasks, strategy = selectOptimalStrategyForMode(mode))
        executePlan(plan, executor)
    }
        fun switchMode(newMode: BurstMode): Boolean {
        if (newMode == currentMode) return false
        currentMode = newMode
        modeSwitchCount.incrementAndGet()
        lastBurstTimeMs.set(System.currentTimeMillis())
        true
    }
        fun getCurrentMode(): BurstMode = currentMode

    fun getActivePlans(): List<BurstExecutionPlan> = activePlans.values.toList()
        fun getMetrics(): BurstExecutionMetrics {
        val avgDuration = if (taskDurations.isNotEmpty()) taskDurations.average() else 0.0
        val throughput = if (throughputSamples.isNotEmpty()) throughputSamples.average() else 0.0
        val burstActive = isBursting && (System.currentTimeMillis() - lastBurstTimeMs.get()) < DEFAULT_BURST_TIMEOUT_MS
        BurstExecutionMetrics(
            tasksSubmitted = submittedTasks.get(),
            tasksCompleted = completedTasks.get(),
            tasksFailed = failedTasks.get(),
            totalExecutionTimeMs = taskDurations.sum(),
            averageTaskDurationMs = avgDuration,
            throughputBurst = throughput,
            modeSwitches = modeSwitchCount.get(),
            resourceUtilizationPercent = calculateUtilization(),
            cacheHitRate = 0.0,
            isCurrentlyBursting = burstActive
        )
    }
        fun getPerformanceSnapshot(): BurstPerformanceSnapshot {
        val totalTasks = completedTasks.get() + failedTasks.get()
        val duration = taskDurations.takeLast(10)
        val avgLatency = if (duration.isNotEmpty()) duration.average() else 0.0
        val throughput = if (avgLatency > 0) 1000.0 / avgLatency else 0.0
        BurstPerformanceSnapshot(
            timestampMs = System.currentTimeMillis(),
            activeTasks = activePlans.values.sumOf { it.tasks.size },
            queuedTasks = 0,
            completedTasks = completedTasks.get(),
            failedTasks = failedTasks.get(),
            cpuLoadPercent = 50.0,
            memoryUsedMb = Runtime.getRuntime().totalMemory() / 1024 / 1024,
            throughputPerSecond = throughput,
            averageLatencyMs = avgLatency,
            mode = currentMode
        )
    }
        fun estimateResourceRequirement(tasks: List<BurstTaskSpec>): ResourceAvailability {
        val totalMemory = tasks.sumOf { it.resourceProfile.expectedMemoryMb }
        val totalCpu = tasks.map { it.resourceProfile.expectedCpuPercent }.maxOrNull() ?: 50.0
        ResourceAvailability(
            freeMemoryMb = (Runtime.getRuntime().freeMemory() / 1024 / 1024).coerceAtLeast(0),
            freeCpuPercent = (100 - totalCpu).coerceIn(0.0, 100.0),
            freeNetworkKbps = 10000,
            freeDiskIops = 1000,
            batteryLevelPercent = 100.0f,
            isCharging = true
        )
    }
        private fun calculateOptimalParallelism(tasks: List<BurstTaskSpec>): Int {
        val memPerTask = tasks.maxOfOrNull { it.resourceProfile.expectedMemoryMb } ?: 64
        val availableMem = Runtime.getRuntime().freeMemory() / 1024 / 1024
        val memLimit = (availableMem / memPerTask.coerceAtLeast(1)).toInt()
        val parallelLimit = when (currentMode) {
            BurstMode.AGGRESSIVE -> config.maxParallelTasks
            BurstMode.BALANCED -> (config.maxParallelTasks / 2).coerceAtLeast(config.minParallelTasks)
            BurstMode.EFFICIENT -> (config.maxParallelTasks / 3).coerceAtLeast(config.minParallelTasks)
            BurstMode.LOW_POWER -> config.minParallelTasks
            BurstMode.BATCH -> (config.maxParallelTasks / 2).coerceAtLeast(config.minParallelTasks)
        }
        min(parallelLimit, memLimit).coerceIn(config.minParallelTasks, config.maxParallelTasks)
    }
        private fun selectOptimalStrategy(plan: BurstExecutionPlan): ExecutionStrategy {
        val avgDuration = plan.tasks.map { it.timeoutMs }.average()
        val taskCount = plan.tasks.size
        return when {
            taskCount <= 1 -> ExecutionStrategy.SEQUENTIAL
            taskCount <= calculateOptimalParallelism(plan.tasks) -> ExecutionStrategy.PARALLEL
            avgDuration > 5000 -> ExecutionStrategy.PIPELINED
            currentMode == BurstMode.AGGRESSIVE && taskCount > 5 -> ExecutionStrategy.SPECULATIVE
            else -> ExecutionStrategy.PARALLEL
        }
    }
        private fun selectOptimalStrategyForMode(mode: BurstMode): ExecutionStrategy {
        return when (mode) {
            BurstMode.AGGRESSIVE -> ExecutionStrategy.PARALLEL
            BurstMode.BALANCED -> ExecutionStrategy.PIPELINED
            BurstMode.EFFICIENT -> ExecutionStrategy.SEQUENTIAL
            BurstMode.LOW_POWER -> ExecutionStrategy.SEQUENTIAL
            BurstMode.BATCH -> ExecutionStrategy.PIPELINED
        }
    }
        private suspend fun evaluateAndAdapt() {
        val metrics = getMetrics()
        val snapshot = getPerformanceSnapshot()
        if (snapshot.cpuLoadPercent > config.aggressiveThreshold * 100) {
            if (currentMode != BurstMode.EFFICIENT) {
                switchMode(BurstMode.EFFICIENT)
            }
        } else if (snapshot.cpuLoadPercent < config.efficientThreshold * 100) {
            if (currentMode != BurstMode.AGGRESSIVE) {
                switchMode(BurstMode.AGGRESSIVE)
            }
        } else {
            if (currentMode != BurstMode.BALANCED) {
                switchMode(BurstMode.BALANCED)
            }
        }
    }
        private fun calculateUtilization(): Double {
        if (submittedTasks.get() == 0L) return 0.0
        val successRate = completedTasks.get().toDouble() / submittedTasks.get()
        successRate * 100.0
    }
        private fun recordSuccess(task: BurstTaskSpec) {
        completedTasks.incrementAndGet()
    }
        private fun recordFailure(task: BurstTaskSpec) {
        failedTasks.incrementAndGet()
    }
        fun resetMetrics() {
        completedTasks.set(0)
        failedTasks.set(0)
        submittedTasks.set(0)
        taskDurations.clear()
        throughputSamples.clear()
    }
        fun resetAll() {
        activePlans.clear()
        resetMetrics()
        currentMode = BurstMode.BALANCED
        isBursting = false
    }
}
