package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResourceManager {

    companion object {
        private const val DEFAULT_MAX_THREADS = 50
        private const val DEFAULT_THREAD_TTL_MS = 60000L
        private const val MONITOR_INTERVAL_MS = 5000L
        private const val MAX_MEMORY_MB = 512
        private const val WARNING_MEMORY_THRESHOLD = 0.8f
    }

    private val threadPool = Executors.newScheduledThreadPool(DEFAULT_MAX_THREADS)
    private val agentResources = ConcurrentHashMap<String, AgentResourceAllocation>()
    private val taskPriorities = ConcurrentHashMap<String, TaskPriority>()
    private val resourceUsageHistory = mutableListOf<ResourceUsageSnapshot>()
    private var monitorFuture: ScheduledFuture<*>? = null

    private val activeThreads = AtomicInteger(0)
    private val totalMemoryUsed = AtomicInteger(0)

    fun start() {
        startResourceMonitoring()
    }

    fun stop() {
        monitorFuture?.cancel(true)
        threadPool.shutdown()

        agentResources.values.forEach { allocation ->
            releaseResources(allocation.agentId)
        }
        agentResources.clear()
        taskPriorities.clear()
    }

    fun allocateResources(agentId: String, taskComplexity: TaskComplexity): AgentResourceAllocation {
        val priority = taskPriorities[agentId] ?: TaskPriority.NORMAL

        val threads = calculateThreadCount(priority, taskComplexity)
        val memoryMb = calculateMemoryAllocation(priority, taskComplexity)

        val allocation = AgentResourceAllocation(
            agentId = agentId,
            threadCount = threads,
            memoryMb = memoryMb,
            priority = priority,
            allocatedAt = System.currentTimeMillis()
        )

        agentResources[agentId] = allocation
        activeThreads.addAndGet(threads)
        totalMemoryUsed.addAndGet(memoryMb)

        return allocation
    }

    fun releaseResources(agentId: String) {
        val allocation = agentResources.remove(agentId)
        if (allocation != null) {
            activeThreads.addAndGet(-allocation.threadCount)
            totalMemoryUsed.addAndGet(-allocation.memoryMb)
        }
    }

    fun updatePriority(agentId: String, priority: TaskPriority) {
        val allocation = agentResources[agentId]
        if (allocation != null) {
            agentResources[agentId] = allocation.copy(priority = priority)
            taskPriorities[agentId] = priority
        }
    }

    fun rebalanceResources() {
        val totalAvailableThreads = DEFAULT_MAX_THREADS - activeThreads.get()
        if (totalAvailableThreads <= 0) return

        val sortedAgents = agentResources.entries.sortedByDescending { it.value.priority.ordinal }

        var availableThreads = totalAvailableThreads
        sortedAgents.forEach { (agentId, allocation) ->
            if (availableThreads <= 0) return@forEach

            val extraThreads = minOf(2, availableThreads)
            val newThreadCount = allocation.threadCount + extraThreads
            val newMemory = calculateMemoryAllocation(allocation.priority, TaskComplexity.fromThreadCount(newThreadCount))

            agentResources[agentId] = allocation.copy(
                threadCount = newThreadCount,
                memoryMb = newMemory
            )

            availableThreads -= extraThreads
        }
    }

    fun getResourceUsage(): ResourceUsageInfo {
        return ResourceUsageInfo(
            activeThreads = activeThreads.get(),
            maxThreads = DEFAULT_MAX_THREADS,
            memoryUsedMb = totalMemoryUsed.get(),
            maxMemoryMb = MAX_MEMORY_MB,
            activeAgents = agentResources.size,
            threadUtilization = activeThreads.get().toFloat() / DEFAULT_MAX_THREADS,
            memoryUtilization = totalMemoryUsed.get().toFloat() / MAX_MEMORY_MB
        )
    }

    fun getAgentAllocation(agentId: String): AgentResourceAllocation? {
        return agentResources[agentId]
    }

    fun getCacheHitRate(): Float {
        return if (resourceUsageHistory.size < 2) {
            0f
        } else {
            val recent = resourceUsageHistory.takeLast(10)
            val hits = recent.count { it.cacheHit }
            hits.toFloat() / recent.size
        }
    }

    fun optimizeForPerformance(): OptimizationReport {
        val currentUsage = getResourceUsage()
        val issues = mutableListOf<OptimizationIssue>()
        val recommendations = mutableListOf<String>()

        if (currentUsage.threadUtilization > 0.9f) {
            issues.add(OptimizationIssue.HIGH_THREAD_PRESSURE)
            recommendations.add("考虑增加线程池大小或优化任务分配")
        }

        if (currentUsage.memoryUtilization > WARNING_MEMORY_THRESHOLD) {
            issues.add(OptimizationIssue.HIGH_MEMORY_PRESSURE)
            recommendations.add("考虑释放空闲 Agent 资源或增加内存限。
        }

        if (activeThreads.get() < DEFAULT_MAX_THREADS * 0.5f) {
            issues.add(OptimizationIssue.LOW_UTILIZATION)
            recommendations.add("资源利用率较低，可以增加更多并发 Agent")
        }

        return OptimizationReport(
            currentUsage = currentUsage,
            issues = issues,
            recommendations = recommendations,
            cacheHitRate = getCacheHitRate(),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun calculateThreadCount(priority: TaskPriority, complexity: TaskComplexity): Int {
        val baseCount = when (complexity) {
            TaskComplexity.LOW -> 1
            TaskComplexity.MEDIUM -> 2
            TaskComplexity.HIGH -> 4
            TaskComplexity.VERY_HIGH -> 8
        }

        val multiplier = when (priority) {
            TaskPriority.LOW -> 0.5f
            TaskPriority.NORMAL -> 1.0f
            TaskPriority.HIGH -> 1.5f
            TaskPriority.CRITICAL -> 2.0f
        }

        return maxOf(1, (baseCount * multiplier).toInt())
    }

    private fun calculateMemoryAllocation(priority: TaskPriority, complexity: TaskComplexity): Int {
        val baseMemory = when (complexity) {
            TaskComplexity.LOW -> 32
            TaskComplexity.MEDIUM -> 64
            TaskComplexity.HIGH -> 128
            TaskComplexity.VERY_HIGH -> 256
        }

        val multiplier = when (priority) {
            TaskPriority.LOW -> 0.5f
            TaskPriority.NORMAL -> 1.0f
            TaskPriority.HIGH -> 1.5f
            TaskPriority.CRITICAL -> 2.0f
        }

        return maxOf(16, (baseMemory * multiplier).toInt())
    }

    private fun startResourceMonitoring() {
        monitorFuture = threadPool.scheduleAtFixedRate({
            try {
                val snapshot = ResourceUsageSnapshot(
                    activeThreads = activeThreads.get(),
                    memoryUsedMb = totalMemoryUsed.get(),
                    activeAgents = agentResources.size,
                    cacheHit = Math.random() > 0.3,
                    timestamp = System.currentTimeMillis()
                )
                resourceUsageHistory.add(snapshot)

                if (resourceUsageHistory.size > 1000) {
                    resourceUsageHistory.removeAt(0)
                }

                checkResourcePressure()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Monitor task error", e)
            }
        }, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun checkResourcePressure() {
        val usage = getResourceUsage()

        if (usage.threadUtilization > 0.9f || usage.memoryUtilization > WARNING_MEMORY_THRESHOLD) {
            rebalanceResources()
        }
    }
}

data class AgentResourceAllocation(
    val agentId: String,
    val threadCount: Int,
    val memoryMb: Int,
    val priority: TaskPriority,
    val allocatedAt: Long
)

enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

enum class TaskComplexity {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    companion object {
        fun fromThreadCount(threads: Int): TaskComplexity {
            return when {
                threads <= 1 -> LOW
                threads <= 2 -> MEDIUM
                threads <= 4 -> HIGH
                else -> VERY_HIGH
            }
        }
    }
}

data class ResourceUsageInfo(
    val activeThreads: Int,
    val maxThreads: Int,
    val memoryUsedMb: Int,
    val maxMemoryMb: Int,
    val activeAgents: Int,
    val threadUtilization: Float,
    val memoryUtilization: Float
)

data class ResourceUsageSnapshot(
    val activeThreads: Int,
    val memoryUsedMb: Int,
    val activeAgents: Int,
    val cacheHit: Boolean,
    val timestamp: Long
)

data class OptimizationReport(
    val currentUsage: ResourceUsageInfo,
    val issues: List<OptimizationIssue>,
    val recommendations: List<String>,
    val cacheHitRate: Float,
    val timestamp: Long
)

enum class OptimizationIssue {
    HIGH_THREAD_PRESSURE,
    HIGH_MEMORY_PRESSURE,
    LOW_UTILIZATION,
    AGENT_OVERLOAD,
    CACHE_MISS
}

class ResultCacheManager {

    private val cache = ConcurrentHashMap<String, CachedResult>()
    private val accessCount = ConcurrentHashMap<String, Int>()
    private val maxCacheSize = 100
    private val defaultTtlMs = 3600000L

    fun put(key: String, result: String, ttlMs: Long = defaultTtlMs) {
        if (cache.size >= maxCacheSize) {
            evictLeastRecentlyUsed()
        }

        cache[key] = CachedResult(
            key = key,
            result = result,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + ttlMs
        )
        accessCount[key] = 0
    }

    fun get(key: String): String? {
        val cached = cache[key] ?: return null

        if (System.currentTimeMillis() > cached.expiresAt) {
            cache.remove(key)
            accessCount.remove(key)
            return null
        }

        accessCount[key] = (accessCount[key] ?: 0) + 1
        return cached.result
    }

    fun invalidate(key: String) {
        cache.remove(key)
        accessCount.remove(key)
    }

    fun clear() {
        cache.clear()
        accessCount.clear()
    }

    fun getHitRate(): Float {
        if (accessCount.isEmpty()) return 0f
        val totalAccesses = accessCount.values.sum()
        val hits = accessCount.values.count { it > 1 }
        return hits.toFloat() / totalAccesses
    }

    private fun evictLeastRecentlyUsed() {
        val lruKey = accessCount.minByOrNull { it.value }?.key
        lruKey?.let {
            cache.remove(it)
            accessCount.remove(it)
        }
    }

    data class CachedResult(
        val key: String,
        val result: String,
        val createdAt: Long,
        val expiresAt: Long
    )
}