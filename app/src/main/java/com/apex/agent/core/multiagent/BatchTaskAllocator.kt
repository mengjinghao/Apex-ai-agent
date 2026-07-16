package com.apex.agent.core.multiagent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BatchTaskAllocator {

    data class BatchAllocationRequest(
        val tasks: List<IntelligentTaskAllocator.AllocationRequest>,
        val maxParallelism: Int = 4,
        val timeoutMs: Long = 10000
    )

    data class BatchAllocationResult(
        val results: List<IntelligentTaskAllocator.AllocationResult>,
        val totalTime: Long,
        val successfulCount: Int,
        val failedCount: Int
    )

    data class AllocationStats(
        val totalTasks: Int,
        val parallelism: Int,
        val averageTimePerTask: Long,
        val totalTime: Long,
        val throughput: Double
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var allocator: IntelligentTaskAllocator
    private val performanceOptimizer = PerformanceOptimizer()
    private val executionStats = ConcurrentHashMap<String, MutableList<Long>>()

    fun initialize(context: android.content.Context) {
        allocator = IntelligentTaskAllocator(context)
    }

    suspend fun allocateBatch(request: BatchAllocationRequest): BatchAllocationResult =
        withContext(scope.coroutineContext) {
            val startTime = System.currentTimeMillis()
            val results = ConcurrentHashMap.newKeySet<Pair<Int, IntelligentTaskAllocator.AllocationResult>>()
            val successfulCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val semaphore = Semaphore(request.maxParallelism)

            request.tasks.mapIndexed { index, taskRequest ->
                async {
                    semaphore.withPermit {
                        val taskStartTime = System.currentTimeMillis()
                        try {
                            val result = performanceOptimizer.optimizeAllocation(allocator, taskRequest)
                            val taskTime = System.currentTimeMillis() - taskStartTime
                            executionStats.computeIfAbsent(taskRequest.taskFeature.category) { mutableListOf() } += taskTime
                            results.add(index to result)
                            successfulCount.incrementAndGet()
                        } catch (e: Exception) {
                            results.add(index to createFallbackResult(taskRequest))
                            failedCount.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()

            val totalTime = System.currentTimeMillis() - startTime
            BatchAllocationResult(
                results = results.sortedBy { it.first }.map { it.second },
                totalTime = totalTime,
                successfulCount = successfulCount.get(),
                failedCount = failedCount.get()
            )
        }

    suspend fun allocateWithDependencies(
        tasks: List<IntelligentTaskAllocator.AllocationRequest>,
        dependencies: Map<String, List<String>>,
        maxParallelism: Int = 4
    ): BatchAllocationResult = withContext(scope.coroutineContext) {
        val startTime = System.currentTimeMillis()
        val results = ConcurrentHashMap<String, IntelligentTaskAllocator.AllocationResult>()
        val completedTasks = ConcurrentHashMap.newKeySet<String>()
        val pendingTasks = tasks.toMutableList()
        val successfulCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val semaphore = Semaphore(maxParallelism)

        suspend fun processNextTasks() {
            val readyTasks = synchronized(pendingTasks) {
                val ready = pendingTasks.filter { task ->
                    dependencies.getOrDefault(task.taskId, emptyList())
                        .all { completedTasks.contains(it) }
                }
                pendingTasks.removeAll(ready)
                ready
            }
            if (readyTasks.isEmpty()) return

            readyTasks.map { task ->
                async {
                    semaphore.withPermit {
                        try {
                            val result = performanceOptimizer.optimizeAllocation(allocator, task)
                            results[task.taskId] = result
                            completedTasks.add(task.taskId)
                            successfulCount.incrementAndGet()
                            processNextTasks()
                        } catch (e: Exception) {
                            val fallback = createFallbackResult(task)
                            results[task.taskId] = fallback
                            completedTasks.add(task.taskId)
                            failedCount.incrementAndGet()
                            processNextTasks()
                        }
                    }
                }
            }.awaitAll()
        }

        processNextTasks()

        val totalTime = System.currentTimeMillis() - startTime
        BatchAllocationResult(
            results = tasks.map { results[it.taskId] ?: createFallbackResult(it) },
            totalTime = totalTime,
            successfulCount = successfulCount.get(),
            failedCount = failedCount.get()
        )
    }

    fun getAllocationStats(): AllocationStats {
        val totalTasks = executionStats.values.sumOf { it.size }
        if (totalTasks == 0) return AllocationStats(0, 0, 0, 0, 0.0)
        val totalTime = executionStats.values.flatten().sum()
        return AllocationStats(
            totalTasks = totalTasks,
            parallelism = 4,
            averageTimePerTask = totalTime / totalTasks,
            totalTime = totalTime,
            throughput = totalTasks.toDouble() * 1000 / totalTime
        )
    }

    fun getTaskTypeStats(): Map<String, Pair<Int, Long>> =
        executionStats.mapValues { (_, times) -> Pair(times.size, times.average().toLong()) }

    fun optimizeParallelism(taskCount: Int): Int = when {
        taskCount <= 4 -> taskCount
        taskCount <= 16 -> 4
        taskCount <= 64 -> 8
        else -> 16
    }

    fun shutdown() { scope.cancel(); performanceOptimizer.shutdown() }
    fun clearStats() { executionStats.clear() }

    private fun createFallbackResult(request: IntelligentTaskAllocator.AllocationRequest): IntelligentTaskAllocator.AllocationResult {
        return IntelligentTaskAllocator.AllocationResult(
            taskId = request.taskId,
            optimalAgent = IntelligentTaskAllocator.AgentMatch(
                agentId = "sanxing_libu_hr", agentName = "Libu", score = 0.0,
                capabilityMatch = 0.0, performanceMatch = 0.0, resourceMatch = 0.0, reasoning = "Fallback allocation"
            ),
            backupAgents = emptyList(),
            decisionReport = "Fallback allocation due to failure",
            executionTime = 0,
            matchedSkill = null
        )
    }
}
