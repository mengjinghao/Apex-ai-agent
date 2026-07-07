package com.apex.agent.core.multiagent

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class PerformanceOptimizer {

    private val allocationCache = ConcurrentHashMap<String, IntelligentTaskAllocator.AllocationResult>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val maxCacheSize = 200

    suspend fun optimizeAllocation(
        allocator: IntelligentTaskAllocator,
        request: IntelligentTaskAllocator.AllocationRequest
    ): IntelligentTaskAllocator.AllocationResult = withContext(Dispatchers.Default) {
        val cacheKey = "${request.taskDescription}_${request.taskFeature.category}_${request.taskFeature.difficulty}"
        allocationCache[cacheKey]?.let { return@withContext it }

        val result = allocator.allocateTask(request)

        if (allocationCache.size >= maxCacheSize) {
            allocationCache.keys.firstOrNull()?.let { allocationCache.remove(it) }
        }
        allocationCache[cacheKey] = result
        result
    }

    suspend fun optimizeBatchAllocation(
        allocator: IntelligentTaskAllocator,
        requests: List<IntelligentTaskAllocator.AllocationRequest>
    ): List<IntelligentTaskAllocator.AllocationResult> = coroutineScope {
        requests.map { request ->
            async {
                try {
                    optimizeAllocation(allocator, request)
                } catch (e: Exception) {
                    IntelligentTaskAllocator.AllocationResult(
                        taskId = request.taskId,
                        optimalAgent = IntelligentTaskAllocator.AgentMatch(
                            agentId = "sanxing_libu_hr", agentName = "Libu", score = 0.0,
                            reasoning = "Optimization failed: ${e.message}"
                        ),
                        decisionReport = "Fallback after optimization failure",
                        executionTime = 0L
                    )
                }
            }
        }.awaitAll()
    }

    fun clearCache() { allocationCache.clear() }
    fun getCacheSize(): Int = allocationCache.size
    fun shutdown() { scope.cancel(); allocationCache.clear() }
}
