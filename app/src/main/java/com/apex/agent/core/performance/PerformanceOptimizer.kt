package com.apex.agent.core.performance

import com.apex.agent.core.concurrent.*
import com.apex.agent.core.monitoring.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PerformanceOptimizer private constructor(
    private val name: String = "global"
) {
    data class GlobalConfig(
        val adaptiveThreadPoolEnabled: Boolean = true,
        val objectPoolEnabled: Boolean = true,
        val circuitBreakerEnabled: Boolean = true,
        val rateLimiterEnabled: Boolean = true,
        val metricsCollectionEnabled: Boolean = true,
        val healthCheckEnabled: Boolean = true,
        val profilingEnabled: Boolean = false,
        val monitoringIntervalMs: Long = 5000L,
        val maxThreadPoolSize: Int = Runtime.getRuntime().availableProcessors() * 4,
        val cacheEnabled: Boolean = true
    )

    data class GlobalMetrics(
        val threadPoolMetrics: Map<String, Any>,
        val poolMetrics: Map<String, Any>,
        val circuitBreakerMetrics: Map<String, CircuitBreaker.CircuitBreakerMetrics>,
        val rateLimiterMetrics: Map<String, RateLimiter.RateLimiterMetrics>,
        val systemMetrics: MetricsCollector.SystemMetrics?,
        val collectorMetrics: MetricsCollector.CollectorMetrics?,
        val healthStatus: HealthChecker.HealthSummary?,
        val jvmSnapshot: JvmProfiler.JvmSnapshot?,
        val uptimeSeconds: Long
    )

    private val logger = LoggerFactory.getLogger("PerformanceOptimizer-$name")
    private val startTime = System.currentTimeMillis()
    private var config = GlobalConfig()
    private var initialized = false
    private var metricsCollector: MetricsCollector? = null
    private var healthChecker: HealthChecker? = null
    private var jvmProfiler: JvmProfiler? = null
    private var resourceMonitor: ResourceMonitor? = null
    private var adaptiveThreadPool: AdaptiveThreadPool? = null

    fun initialize(customConfig: GlobalConfig = GlobalConfig()) {
        if (initialized) return
        this.config = customConfig
        logger.info("Initializing PerformanceOptimizer with config: {}", customConfig)

        if (config.adaptiveThreadPoolEnabled) {
            adaptiveThreadPool = AdaptiveThreadPool.builder("global-adaptive")
                .corePoolSize(Runtime.getRuntime().availableProcessors())
                .maxPoolSize(config.maxThreadPoolSize)
                .build()
            logger.info("Adaptive thread pool initialized with {} max threads", config.maxThreadPoolSize)
        }

        if (config.metricsCollectionEnabled) {
            metricsCollector = MetricsCollector.getInstance("global", config.monitoringIntervalMs)
            healthChecker = HealthChecker("global")
            jvmProfiler = JvmProfiler("global")
            resourceMonitor = ResourceMonitor("global")
        }

        if (config.objectPoolEnabled) {
            PoolRegistry.warmUpAll()
            logger.info("Object pools warmed up")
        }

        initialized = true
        logger.info("PerformanceOptimizer initialized successfully")
    }

    fun shutdown() {
        logger.info("Shutting down PerformanceOptimizer")
        metricsCollector?.shutdown()
        adaptiveThreadPool?.shutdownGracefully()
        PoolRegistry.shutdownAll()
        initialized = false
    }

    fun isInitialized(): Boolean = initialized

    fun getConfig(): GlobalConfig = config

    fun updateConfig(newConfig: GlobalConfig) {
        this.config = newConfig
        logger.info("PerformanceOptimizer config updated")
    }

    fun getMetrics(): GlobalMetrics {
        val systemMetrics = metricsCollector?.getLatestMetrics()
        val collectorMetrics = metricsCollector?.getCollectorMetrics()
        val jvmSnapshot = jvmProfiler?.takeSnapshot()

        return GlobalMetrics(
            threadPoolMetrics = mapOf(
                "enabled" to (adaptiveThreadPool != null),
                "maxPoolSize" to config.maxThreadPoolSize
            ),
            poolMetrics = mapOf(
                "enabled" to config.objectPoolEnabled,
                "pools" to PoolRegistry.getAllMetrics().size
            ),
            circuitBreakerMetrics = CircuitBreaker.getAllMetrics(),
            rateLimiterMetrics = RateLimiter.getAllMetrics(),
            systemMetrics = systemMetrics,
            collectorMetrics = collectorMetrics,
            healthStatus = null,
            jvmSnapshot = jvmSnapshot,
            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
        )
    }

    fun getSystemReport(): String {
        val sb = StringBuilder()
        sb.appendLine("==========================================")
        sb.appendLine("  Performance Optimizer Report")
        sb.appendLine("  Name: $name")
        sb.appendLine("  Uptime: ${(System.currentTimeMillis() - startTime) / 1000}s")
        sb.appendLine("  Initialized: $initialized")
        sb.appendLine("==========================================")
        sb.appendLine()

        if (config.metricsCollectionEnabled) {
            val metrics = metricsCollector?.getLatestMetrics()
            if (metrics != null) {
                sb.appendLine("--- System Metrics ---")
                sb.appendLine("  CPU Usage: ${"%.1f".format(metrics.cpuUsagePercent)}%")
                sb.appendLine("  Memory: ${formatBytes(metrics.usedMemoryBytes)} / ${formatBytes(metrics.totalMemoryBytes)}")
                sb.appendLine("  Threads: ${metrics.threadCount} (peak: ${metrics.peakThreadCount})")
                sb.appendLine("  GC: ${metrics.gcCount} collections, ${metrics.gcTimeMs}ms")
                sb.appendLine()
            }
        }

        if (config.healthCheckEnabled) {
            sb.appendLine("--- Circuit Breakers ---")
            CircuitBreaker.getAllMetrics().forEach { (name, m) ->
                sb.appendLine("  $name: ${m.state} (failures=${m.failureCount}, total=${m.totalCalls})")
            }
            sb.appendLine()
        }

        sb.appendLine("--- Rate Limiters ---")
        RateLimiter.getAllMetrics().forEach { (name, m) ->
            sb.appendLine("  $name: ${m.totalAllowed}/${m.totalRequests} allowed (peak=${"%.1f".format(m.peakRate)}/s)")
        }
        sb.appendLine()

        sb.appendLine("==========================================")
        return sb.toString()
    }

    fun createOperationTimer(operationName: String): OperationTimer = OperationTimer.start(operationName)

    fun <T> measure(operationName: String, block: () -> T): T {
        val timer = createOperationTimer(operationName)
        return try {
            block()
        } finally {
            timer.lap("complete")
            logger.debug("Operation '{}' took {} ms", operationName, timer.elapsedMs())
        }
    }

    suspend fun <T> measureSuspend(operationName: String, block: suspend () -> T): T {
        val timer = createOperationTimer(operationName)
        return try {
            block()
        } finally {
            timer.lap("complete")
            logger.debug("Operation '{}' took {} ms", operationName, timer.elapsedMs())
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    companion object {
        @Volatile
        private var instance: PerformanceOptimizer? = null

        fun getInstance(): PerformanceOptimizer {
            return instance ?: synchronized(this) {
                instance ?: PerformanceOptimizer().also { instance = it }
            }
        }

        fun reset() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }
}

class BufferManager(private val name: String = "buffer-manager") {
    data class BufferConfig(
        val initialSize: Int = 8192,
        val maxSize: Int = 65536,
        val directBuffers: Boolean = false,
        val poolSize: Int = 64
    )

    private val config = BufferConfig()
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private val activeBuffers = ConcurrentLinkedQueue<ByteArray>()
    private val totalAllocated = AtomicLong(0)
    private val totalReleased = AtomicLong(0)
    private val currentPoolSize = AtomicInteger(0)

    fun acquire(minSize: Int = config.initialSize): ByteArray {
        var buffer = bufferPool.poll()
        if (buffer == null || buffer.size < minSize) {
            val newSize = maxOf(minSize, config.initialSize)
            buffer = ByteArray(newSize.coerceAtMost(config.maxSize))
            totalAllocated.addAndGet(buffer.size.toLong())
        } else {
            currentPoolSize.decrementAndGet()
        }
        activeBuffers.add(buffer)
        return buffer
    }

    fun release(buffer: ByteArray) {
        activeBuffers.remove(buffer)
        if (currentPoolSize.get() < config.poolSize) {
            bufferPool.offer(buffer)
            currentPoolSize.incrementAndGet()
        }
        totalReleased.addAndGet(buffer.size.toLong())
    }

    fun clear() {
        bufferPool.clear()
        activeBuffers.clear()
        currentPoolSize.set(0)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "poolSize" to currentPoolSize.get(),
        "activeBuffers" to activeBuffers.size,
        "totalAllocated" to totalAllocated.get(),
        "totalReleased" to totalReleased.get()
    )
}

class PerformanceTuner(private val name: String = "tuner") {
    data class TuningParams(
        val concurrencyLevel: Int = 4,
        val batchSize: Int = 100,
        val cacheSize: Int = 500,
        val timeoutMs: Long = 5000L,
        val retryCount: Int = 3,
        val compressionEnabled: Boolean = false,
        val prefetchEnabled: Boolean = true
    )

    private val logger = LoggerFactory.getLogger("PerformanceTuner-$name")
    private val params = TuningParams()
    private val adjustmentCount = AtomicInteger(0)

    fun autoTune(metric: String, currentValue: Double, targetValue: Double): TuningParams {
        val ratio = if (targetValue > 0) currentValue / targetValue else 1.0

        val tuned = when {
            ratio > 1.2 -> {
                params.copy(
                    concurrencyLevel = (params.concurrencyLevel * 1.25).toInt().coerceAtMost(32),
                    batchSize = (params.batchSize * 1.1).toInt().coerceAtMost(500)
                )
            }
            ratio < 0.8 -> {
                params.copy(
                    concurrencyLevel = (params.concurrencyLevel * 0.75).toInt().coerceAtLeast(1),
                    batchSize = (params.batchSize * 0.9).toInt().coerceAtLeast(10)
                )
            }
            else -> params
        }

        adjustmentCount.incrementAndGet()
        logger.info("Auto-tune '{}': ratio={:.2f}, concurrency={}, batch={}",
            metric, ratio, tuned.concurrencyLevel, tuned.batchSize)
        return tuned
    }

    fun getParams(): TuningParams = params
    fun getAdjustmentCount(): Int = adjustmentCount.get()

    companion object {
        fun calculateOptimalBatchSize(averageItemSize: Int, maxMemoryBytes: Long): Int {
            val maxItems = if (averageItemSize > 0) maxMemoryBytes / averageItemSize else 100
            return maxItems.coerceIn(10, 1000).toInt()
        }

        fun calculateOptimalConcurrency(cpuCores: Int, isIoBound: Boolean): Int {
            return if (isIoBound) cpuCores * 4 else cpuCores + 1
        }

        fun calculateOptimalCacheSize(accessRate: Double, itemSize: Long, maxMemoryBytes: Long): Int {
            val byMemory = (maxMemoryBytes / itemSize.coerceAtLeast(1)).toInt()
            val byAccess = (accessRate * 3600).toInt()
            return minOf(byMemory, byAccess).coerceIn(10, 10000)
        }
    }
}
