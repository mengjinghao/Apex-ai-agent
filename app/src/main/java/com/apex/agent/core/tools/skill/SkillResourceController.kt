package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

class SkillResourceController private constructor() {

    companion object {
        private const val TAG = "SkillResourceController"
        private const val DEFAULT_MAX_CONCURRENT_TASKS = 8
        private const val DEFAULT_MAX_MEMORY_MB = 256
        private const val DEFAULT_CPU_THRESHOLD = 80
        private const val DEFAULT_MEMORY_THRESHOLD = 75
        private const val MONITOR_INTERVAL_MS = 1000L
        private const val GC_TRIGGER_THRESHOLD = 70
        private const val COOLDOWN_MS = 5000L

        @Volatile private var INSTANCE: SkillResourceController? = null

        fun getInstance(): SkillResourceController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillResourceController().also { INSTANCE = it }
            }
        }
    }

    enum class ResourceType {
        CPU,
        MEMORY,
        THREAD,
        QUEUE_SLOT
    }

    data class ResourceAllocation(
        val taskId: String,
        val resourceType: ResourceType,
        val amount: Int,
        val allocatedAt: Long = System.currentTimeMillis()
    )

    data class ResourceUsage(
        val cpuUsagePercent: Float,
        val memoryUsedMb: Int,
        val memoryAvailableMb: Int,
        val activeTasks: Int,
        val queuedTasks: Int,
        val threadCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ResourceLimit(
        val maxConcurrentTasks: Int = DEFAULT_MAX_CONCURRENT_TASKS,
        val maxMemoryMb: Int = DEFAULT_MAX_MEMORY_MB,
        val maxQueueSize: Int = 100,
        val cpuThrottleThreshold: Int = DEFAULT_CPU_THRESHOLD,
        val memoryThrottleThreshold: Int = DEFAULT_MEMORY_THRESHOLD
    )

    interface ResourceAllocationStrategy {
        fun calculateAllocation(taskComplexity: TaskComplexity, currentUsage: ResourceUsage): Int
    }

    enum class TaskComplexity {
        LOW,
        MEDIUM,
        HIGH,
        VERY_HIGH;
        fun getThreadRequirement(): Int = when (this) {
            LOW -> 1
            MEDIUM -> 2
            HIGH -> 4
            VERY_HIGH -> 8
        }
        fun getMemoryRequirement(): Int = when (this) {
            LOW -> 32
            MEDIUM -> 64
            HIGH -> 128
            VERY_HIGH -> 256
        }
    }
        private val resourceLimits = AtomicReference(ResourceLimit())
        private val currentAllocations = ConcurrentHashMap<String, MutableList<ResourceAllocation>>()
        private val cpuUsageHistory = ConcurrentHashMap<String, Float>()
        private val memoryUsageHistory = ConcurrentHashMap<String, Float>()
        private val activeTaskCount = AtomicInteger(0)
        private val totalCpuUsage = AtomicLong(0)
        private val peakCpuUsage = AtomicLong(0)
        private val peakMemoryUsage = AtomicLong(0)
        private val throttledTaskCount = AtomicLong(0)
        private val totalThrottleEvents = AtomicLong(0)
        private val lastThrottleTime = AtomicLong(0)
        private val monitorExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
        private var isMonitoring = false

    private var onThrottleListener: ((Int) -> Unit)? = null

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        monitorExecutor.scheduleAtFixedRate({
            try {
                updateResourceUsage()
                checkResourcePressure()
                performAdaptiveThrottling()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in resource monitoring", e)
            }
        }, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS)

        AppLogger.d(TAG, "Resource monitoring started")
    }
        fun stopMonitoring() {
        isMonitoring = false
        monitorExecutor.shutdown()
        try {
            if (!monitorExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            monitorExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        AppLogger.d(TAG, "Resource monitoring stopped")
    }
        fun setResourceLimits(limits: ResourceLimit) {
        resourceLimits.set(limits)
        AppLogger.d(TAG, "Resource limits updated: ${limits}")
    }
        fun getResourceLimits(): ResourceLimit {
        return resourceLimits.get()
    }
        fun setOnThrottleListener(listener: (Int) -> Unit) {
        onThrottleListener = listener
    }
        fun allocateResources(taskId: String, complexity: TaskComplexity): Boolean {
        val limits = resourceLimits.get()
        val currentUsage = getCurrentResourceUsage()
        if (activeTaskCount.get() >= limits.maxConcurrentTasks) {
            AppLogger.w(TAG, "Cannot allocate resources for ${taskId}: max concurrent tasks reached")
        return false
        }
        val requiredMemory = complexity.getMemoryRequirement()
        if (currentUsage.memoryUsedMb + requiredMemory > limits.maxMemoryMb) {
            AppLogger.w(TAG, "Cannot allocate resources for ${taskId}: insufficient memory")
        return false
        }
        val requiredThreads = complexity.getThreadRequirement()
        if (currentUsage.threadCount + requiredThreads > limits.maxConcurrentTasks * 2) {
            AppLogger.w(TAG, "Cannot allocate resources for ${taskId}: insufficient threads")
        return false
        }
        val allocations = mutableListOf<ResourceAllocation>()
        allocations.add(ResourceAllocation(taskId, ResourceType.THREAD, requiredThreads))
        allocations.add(ResourceAllocation(taskId, ResourceType.MEMORY, requiredMemory))
        allocations.add(ResourceAllocation(taskId, ResourceType.QUEUE_SLOT, 1))

        currentAllocations[taskId] = allocations
        activeTaskCount.incrementAndGet()

        AppLogger.d(TAG, "Allocated resources for ${taskId}: threads=${requiredThreads}, memory=${requiredMemory}MB")
        return true
    }
        fun releaseResources(taskId: String) {
        val allocations = currentAllocations.remove(taskId)
        if (allocations != null) {
            activeTaskCount.decrementAndGet()
            AppLogger.d(TAG, "Released resources for ${taskId}")
        }
    }
        fun getTaskAllocation(taskId: String): List<ResourceAllocation>? {
        return currentAllocations[taskId]
    }
        fun getCurrentResourceUsage(): ResourceUsage {
        val rt = Runtime.getRuntime()
        val memoryUsed = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val memoryAvailable = (rt.maxMemory() / (1024 * 1024)).toInt() - memoryUsed

        val cpuUsage = calculateCpuUsage()
        return ResourceUsage(
            cpuUsagePercent = cpuUsage,
            memoryUsedMb = memoryUsed,
            memoryAvailableMb = memoryAvailable,
            activeTasks = activeTaskCount.get(),
            queuedTasks = 0,
            threadCount = Thread.activeCount()
        )
    }
        fun canAcceptTask(complexity: TaskComplexity): Boolean {
        val limits = resourceLimits.get()
        val usage = getCurrentResourceUsage()
        if (activeTaskCount.get() >= limits.maxConcurrentTasks) {
            return false
        }
        if (usage.memoryUsedMb + complexity.getMemoryRequirement() > limits.maxMemoryMb) {
            return false
        }
        if (usage.cpuUsagePercent > limits.cpuThrottleThreshold) {
            val now = System.currentTimeMillis()
        if (now - lastThrottleTime.get() < COOLDOWN_MS) {
                return false
            }
        }
        return true
    }
        fun getThrottledTaskCount(): Long {
        return throttledTaskCount.get()
    }
        fun getTotalThrottleEvents(): Long {
        return totalThrottleEvents.get()
    }
        fun getPeakCpuUsage(): Float {
        return peakCpuUsage.get().toFloat() / 100
    }
        fun getPeakMemoryUsage(): Long {
        return peakMemoryUsage.get()
    }
        fun resetStats() {
        totalCpuUsage.set(0)
        peakCpuUsage.set(0)
        peakMemoryUsage.set(0)
        throttledTaskCount.set(0)
        totalThrottleEvents.set(0)
        cpuUsageHistory.clear()
        memoryUsageHistory.clear()
    }
        private fun updateResourceUsage() {
        val rt = Runtime.getRuntime()
        val memoryUsed = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val memoryAvailable = (rt.maxMemory() / (1024 * 1024)).toInt() - memoryUsed

        val cpuUsage = calculateCpuUsage()
        val timestamp = System.currentTimeMillis()
        cpuUsageHistory["${timestamp}"] = cpuUsage
        memoryUsageHistory["${timestamp}"] = memoryUsed.toFloat()
        if (memoryUsed > peakMemoryUsage.get()) {
            peakMemoryUsage.set(memoryUsed.toLong())
        }
        if ((cpuUsage * 100).toLong() > peakCpuUsage.get()) {
            peakCpuUsage.set((cpuUsage * 100).toLong())
        }

        pruneOldHistory()
        val currentUsage = getCurrentResourceUsage()
        AppLogger.v(TAG, "Resource usage: CPU=${currentUsage.cpuUsagePercent}%, Memory=${currentUsage.memoryUsedMb}MB, ActiveTasks=${currentUsage.activeTasks}")
    }
        private fun calculateCpuUsage(): Float {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        var totalIdle = 0L
        var totalTick = 0L

        try {
            val reader = java.io.RandomAccessFile("/proc/stat", "r")
        val load = reader.readLine()
            reader.close()
        val toks = load.split(" +".toRegex())
        if (toks.size >= 5) {
                val idle = toks[4].toLong()
        var total = 0L
                for (i in 1..3) {
                    total += toks[i].toLong()
                }
                total += idle

                val cpuUsage = 1.0f - (idle.toFloat() / total.toFloat())
        return (cpuUsage * 100).coerceIn(0f, 100f)
            }
        } catch (e: Exception) {
            // Fallback for non-Linux systems
        }
        return 50f
    }
        private fun checkResourcePressure() {
        val usage = getCurrentResourceUsage()
        val limits = resourceLimits.get()
        if (usage.cpuUsagePercent > limits.cpuThrottleThreshold ||
            usage.memoryUsedMb > limits.maxMemoryMb * limits.memoryThrottleThreshold / 100) {
            lastThrottleTime.set(System.currentTimeMillis())
        }
    }
        private fun performAdaptiveThrottling() {
        val usage = getCurrentResourceUsage()
        val limits = resourceLimits.get()
        val shouldThrottle = usage.cpuUsagePercent > limits.cpuThrottleThreshold ||
                            (usage.memoryUsedMb.toFloat() / limits.maxMemoryMb) > (limits.memoryThrottleThreshold / 100f)
        if (shouldThrottle) {
            throttledTaskCount.incrementAndGet()
            totalThrottleEvents.incrementAndGet()
            onThrottleListener?.invoke(activeTaskCount.get())

            AppLogger.w(TAG, "Throttling active: CPU=${usage.cpuUsagePercent}%, Memory=${usage.memoryUsedMb}MB")
        }
    }
        private fun pruneOldHistory() {
        val cutoff = System.currentTimeMillis() - 60000
        cpuUsageHistory.keys.toList().forEach { key ->
            if (key is String) {
                val timestamp = key.toLongOrNull() ?: return@forEach
                if (timestamp < cutoff) {
                    cpuUsageHistory.remove(key)
                }
            }
        }
        memoryUsageHistory.keys.toList().forEach { key ->
            if (key is String) {
                val timestamp = key.toLongOrNull() ?: return@forEach
                if (timestamp < cutoff) {
                    memoryUsageHistory.remove(key)
                }
            }
        }
    }

    data class OptimizationSuggestion(
        val type: SuggestionType,
        val message: String,
        val potentialImprovement: Float
    )

    enum class SuggestionType {
        INCREASE_CONCURRENCY,
        DECREASE_CONCURRENCY,
        OPTIMIZE_MEMORY,
        CPU_OPTIMIZATION,
        QUEUE_SIZE_INCREASE
    }
        fun getOptimizationSuggestions(): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        val usage = getCurrentResourceUsage()
        val limits = resourceLimits.get()
        if (usage.cpuUsagePercent < 40 && usage.memoryUsedMb < limits.maxMemoryMb * 0.5) {
            suggestions.add(OptimizationSuggestion(
                SuggestionType.INCREASE_CONCURRENCY,
                "Resource utilization is low, consider increasing concurrency",
                0.3f
            ))
        }
        if (usage.cpuUsagePercent > 80) {
            suggestions.add(OptimizationSuggestion(
                SuggestionType.DECREASE_CONCURRENCY,
                "CPU usage is high, consider reducing concurrent tasks",
                0.25f
            ))
        }
        if (usage.memoryUsedMb > limits.maxMemoryMb * 0.8) {
            suggestions.add(OptimizationSuggestion(
                SuggestionType.OPTIMIZE_MEMORY,
                "Memory usage is approaching limit, consider optimizing memory usage",
                0.2f
            ))
        }
        return suggestions
    }
        fun shutdown() {
        stopMonitoring()
        currentAllocations.clear()
        INSTANCE = null
    }
}