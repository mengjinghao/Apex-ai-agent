package com.apex.agent.core.monitoring

import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.OperatingSystemMXBean
import java.lang.management.ThreadMXBean
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class MetricsCollector private constructor(
    private val name: String = "default",
    private val collectionIntervalMs: Long = 5000L,
    private val maxHistorySize: Int = 1000
) {
    data class SystemMetrics(
        val timestamp: Long,
        val cpuUsagePercent: Double,
        val freeMemoryBytes: Long,
        val totalMemoryBytes: Long,
        val usedMemoryBytes: Long,
        val threadCount: Int,
        val peakThreadCount: Int,
        val openFileDescriptorCount: Long,
        val processCpuLoad: Double,
        val systemLoadAverage: Double,
        val gcCount: Long,
        val gcTimeMs: Long,
        val heapUsedBytes: Long,
        val heapMaxBytes: Long,
        val nonHeapUsedBytes: Long
    )

    data class CollectorMetrics(
        val totalCollections: Long,
        val totalMetricsCollected: Long,
        val averageCollectionTimeMs: Double,
        val minCollectionTimeMs: Double,
        val maxCollectionTimeMs: Double,
        val storageSize: Int,
        val errorCount: Long
    )

    private val logger = LoggerFactory.getLogger("MetricsCollector-$name")
    private val metricsHistory = ConcurrentLinkedQueue<SystemMetrics>()
    private val osBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()
    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val threadBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
    private val runtime = Runtime.getRuntime()

    private val collectionCount = AtomicLong(0)
    private val totalCollectionTimeNs = AtomicLong(0)
    private val minCollectionTimeNs = AtomicLong(Long.MAX_VALUE)
    private val maxCollectionTimeNs = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    private val listeners = CopyOnWriteArrayList<(SystemMetrics) -> Unit>()
    private val scope = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "metrics-collector-$name").apply { isDaemon = true }
    }

    private val systemMetricsFlow = LinkedBlockingQueue<SystemMetrics>(64)

    init {
        scope.scheduleAtFixedRate(
            { collectMetrics() },
            0,
            collectionIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    fun registerListener(listener: (SystemMetrics) -> Unit) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: (SystemMetrics) -> Unit) {
        listeners.remove(listener)
    }

    fun getLatestMetrics(): SystemMetrics? = metricsHistory.peek()

    fun getMetricsHistory(): List<SystemMetrics> = metricsHistory.toList()

    fun <T> measureOperation(name: String, block: () -> T): MeasuredResult<T> {
        val start = System.nanoTime()
        return try {
            val result = block()
            MeasuredResult(
                success = true,
                result = result,
                durationNs = System.nanoTime() - start,
                operationName = name
            )
        } catch (e: Exception) {
            MeasuredResult(
                success = false,
                result = null,
                durationNs = System.nanoTime() - start,
                operationName = name,
                error = e
            )
        }
    }

    suspend fun <T> measureOperationSuspend(name: String, block: suspend () -> T): MeasuredResult<T> {
        val start = System.nanoTime()
        return try {
            val result = block()
            MeasuredResult(
                success = true,
                result = result,
                durationNs = System.nanoTime() - start,
                operationName = name
            )
        } catch (e: Exception) {
            MeasuredResult(
                success = false,
                result = null,
                durationNs = System.nanoTime() - start,
                operationName = name,
                error = e
            )
        }
    }

    fun takeMetricsSnapshot(): SystemMetrics = collectMetricsNow()

    fun getCollectorMetrics(): CollectorMetrics {
        val count = collectionCount.get()
        return CollectorMetrics(
            totalCollections = count,
            totalMetricsCollected = count,
            averageCollectionTimeMs = if (count > 0)
                totalCollectionTimeNs.get().toDouble() / count / 1_000_000.0 else 0.0,
            minCollectionTimeMs = if (minCollectionTimeNs.get() < Long.MAX_VALUE)
                minCollectionTimeNs.get() / 1_000_000.0 else 0.0,
            maxCollectionTimeMs = maxCollectionTimeNs.get() / 1_000_000.0,
            storageSize = metricsHistory.size,
            errorCount = errorCount.get()
        )
    }

    fun pollMetricsQueue(): SystemMetrics? = systemMetricsFlow.poll()

    fun shutdown() {
        scope.shutdown()
        try {
            scope.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            scope.shutdownNow()
        }
    }

    private fun collectMetrics() {
        val start = System.nanoTime()
        try {
            val metrics = collectMetricsNow()
            metricsHistory.add(metrics)
            while (metricsHistory.size > maxHistorySize) {
                metricsHistory.poll()
            }
            systemMetricsFlow.offer(metrics)
            listeners.forEach { listener ->
                try { listener(metrics) } catch (e: Exception) { logger.warn("Listener failed", e) }
            }
            val elapsed = System.nanoTime() - start
            collectionCount.incrementAndGet()
            totalCollectionTimeNs.addAndGet(elapsed)
            updateMinMax(elapsed)
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.warn("Metrics collection failed", e)
        }
    }

    private fun collectMetricsNow(): SystemMetrics {
        val heap = memoryBean.heapMemoryUsage
        val nonHeap = memoryBean.nonHeapMemoryUsage
        val gcCount = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }
        val gcTime = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.toLong() }

        val processCpu = try {
            val method = osBean.javaClass.getMethod("getProcessCpuLoad")
            (method.invoke(osBean) as? Double) ?: -1.0
        } catch (e: Exception) { -1.0 }

        val openFds = try {
            val method = osBean.javaClass.getMethod("getOpenFileDescriptorCount")
            (method.invoke(osBean) as? Long) ?: -1L
        } catch (e: Exception) { -1L }

        return SystemMetrics(
            timestamp = System.currentTimeMillis(),
            cpuUsagePercent = processCpu.coerceAtLeast(0.0) * 100,
            freeMemoryBytes = runtime.freeMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory(),
            threadCount = threadBean.threadCount,
            peakThreadCount = threadBean.peakThreadCount,
            openFileDescriptorCount = openFds,
            processCpuLoad = processCpu.coerceAtLeast(0.0),
            systemLoadAverage = osBean.systemLoadAverage.coerceAtLeast(0.0),
            gcCount = gcCount,
            gcTimeMs = gcTime,
            heapUsedBytes = heap.used,
            heapMaxBytes = heap.max,
            nonHeapUsedBytes = nonHeap.used
        )
    }

    private fun updateMinMax(elapsed: Long) {
        var min = minCollectionTimeNs.get()
        while (elapsed < min && !minCollectionTimeNs.compareAndSet(min, elapsed)) {
            min = minCollectionTimeNs.get()
        }
        var max = maxCollectionTimeNs.get()
        while (elapsed > max && !maxCollectionTimeNs.compareAndSet(max, elapsed)) {
            max = maxCollectionTimeNs.get()
        }
    }

    data class MeasuredResult<T>(
        val success: Boolean,
        val result: T?,
        val durationNs: Long,
        val operationName: String,
        val error: Throwable? = null
    ) {
        val durationMs: Double get() = durationNs / 1_000_000.0
    }

    companion object {
        private val instances = ConcurrentHashMap<String, MetricsCollector>()

        fun getInstance(name: String = "default", intervalMs: Long = 5000L): MetricsCollector {
            return instances.getOrPut(name) { MetricsCollector(name, intervalMs) }
        }

        fun getAllMetrics(): Map<String, CollectorMetrics> {
            return instances.mapValues { it.value.getCollectorMetrics() }
        }
    }
}

class OperationTimer(private val name: String) {
    private val startTime = System.nanoTime()
    private var laps = mutableListOf<Lap>()

    data class Lap(val name: String, val elapsedNs: Long)

    fun lap(lapName: String) {
        laps.add(Lap(lapName, System.nanoTime() - startTime))
    }

    fun elapsedNs(): Long = System.nanoTime() - startTime
    fun elapsedMs(): Double = elapsedNs() / 1_000_000.0

    fun report(): String {
        val total = elapsedMs()
        val sb = StringBuilder()
        sb.appendLine("=== Operation: $name ===")
        sb.appendLine("Total: ${"%.3f".format(total)} ms")
        laps.forEachIndexed { i, lap ->
            val pct = if (total > 0) lap.elapsedNs / 1_000_000.0 / total * 100 else 0.0
            sb.appendLine("  Lap ${i + 1} [${lap.name}]: ${"%.3f".format(lap.elapsedNs / 1_000_000.0)} ms ($pct%)")
        }
        return sb.toString()
    }

    companion object {
        fun start(name: String): OperationTimer = OperationTimer(name)
    }
}

class ThroughputTracker(private val name: String, private val windowSizeMs: Long = 1000L) {
    private val timestamps = ConcurrentLinkedQueue<Long>()
    private val totalProcessed = AtomicLong(0)
    private val peakThroughput = AtomicLong(0)

    fun record(count: Int = 1) {
        val now = System.currentTimeMillis()
        repeat(count) { timestamps.add(now) }
        totalProcessed.addAndGet(count.toLong())
        cleanup()
    }

    fun getCurrentThroughput(): Double {
        cleanup()
        val windowStart = System.currentTimeMillis() - windowSizeMs
        val count = timestamps.count { it >= windowStart }
        return count.toDouble() / (windowSizeMs / 1000.0)
    }

    fun getPeakThroughput(): Double {
        return peakThroughput.get().toDouble() / (windowSizeMs / 1000.0)
    }

    fun getTotalProcessed(): Long = totalProcessed.get()

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "currentTps" to getCurrentThroughput(),
        "peakTps" to getPeakThroughput(),
        "totalProcessed" to totalProcessed.get()
    )

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - windowSizeMs * 2
        while (timestamps.peek() != null && timestamps.peek() < cutoff) {
            timestamps.poll()
        }
        val current = timestamps.size
        var peak = peakThroughput.get()
        while (current > peak && !peakThroughput.compareAndSet(peak, current)) {
            peak = peakThroughput.get()
        }
    }
}

class LatencyTracker(private val name: String) {
    private val latencies = ConcurrentLinkedQueue<Long>()
    private val maxSamples = 10000
    private val totalSamples = AtomicLong(0)
    private val sumLatency = AtomicLong(0)

    data class LatencyStats(
        val name: String,
        val count: Long,
        val avgUs: Double,
        val minUs: Double,
        val maxUs: Double,
        val p50Us: Double,
        val p90Us: Double,
        val p95Us: Double,
        val p99Us: Double,
        val p999Us: Double
    )

    fun record(durationNs: Long) {
        val us = durationNs / 1000
        latencies.add(us)
        sumLatency.addAndGet(us)
        totalSamples.incrementAndGet()
        while (latencies.size > maxSamples) { latencies.poll() }
    }

    fun getStats(): LatencyStats {
        val sorted = latencies.sorted()
        val count = totalSamples.get()
        val avg = if (count > 0) sumLatency.get().toDouble() / count else 0.0
        return LatencyStats(
            name = name,
            count = count,
            avgUs = avg,
            minUs = sorted.firstOrNull()?.toDouble() ?: 0.0,
            maxUs = sorted.lastOrNull()?.toDouble() ?: 0.0,
            p50Us = percentile(sorted, 50),
            p90Us = percentile(sorted, 90),
            p95Us = percentile(sorted, 95),
            p99Us = percentile(sorted, 99),
            p999Us = percentile(sorted, 99.9)
        )
    }

    private fun percentile(sorted: List<Long>, pct: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * pct / 100.0).roundToLong().toInt()
        return sorted[index.coerceIn(0, sorted.lastIndex)].toDouble()
    }
}

class HealthChecker(private val name: String = "health") {
    data class HealthStatus(
        val name: String,
        val isHealthy: Boolean,
        val checks: List<CheckResult>,
        val lastCheckTime: Long,
        val uptimeMs: Long
    )

    data class CheckResult(
        val checkName: String,
        val passed: Boolean,
        val message: String,
        val responseTimeMs: Long
    )

    private val checks = CopyOnWriteArrayList<HealthCheck>()
    private val startTime = System.currentTimeMillis()
    private val failedChecks = AtomicLong(0)
    private val totalChecks = AtomicLong(0)

    interface HealthCheck {
        val name: String
        suspend fun check(): CheckResult
    }

    abstract class AbstractHealthCheck(override val name: String) : HealthCheck {
        override suspend fun check(): CheckResult {
            val start = System.currentTimeMillis()
            return try {
                doCheck().let { ok ->
                    CheckResult(name, ok, if (ok) "OK" else "Failed", System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                CheckResult(name, false, e.message ?: "Exception", System.currentTimeMillis() - start)
            }
        }
        protected abstract suspend fun doCheck(): Boolean
    }

    fun registerCheck(check: HealthCheck) { checks.add(check) }

    fun unregisterCheck(name: String) {
        checks.removeAll { it.name == name }
    }

    suspend fun performHealthCheck(): HealthStatus {
        val results = checks.map { it.check() }
        val allHealthy = results.all { it.passed }
        val failed = results.count { !it.passed }
        failedChecks.addAndGet(failed.toLong())
        totalChecks.addAndGet(results.size.toLong())
        return HealthStatus(
            name = name,
            isHealthy = allHealthy,
            checks = results,
            lastCheckTime = System.currentTimeMillis(),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }

    data class HealthSummary(
        val isHealthy: Boolean,
        val totalChecks: Long,
        val passedChecks: Long,
        val failedChecks: Long,
        val uptimeSeconds: Long,
        val checkDetails: Map<String, Boolean>
    )

    suspend fun getSummary(): HealthSummary {
        val status = performHealthCheck()
        return HealthSummary(
            isHealthy = status.isHealthy,
            totalChecks = totalChecks.get(),
            passedChecks = totalChecks.get() - failedChecks.get(),
            failedChecks = failedChecks.get(),
            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000,
            checkDetails = status.checks.associate { it.checkName to it.passed }
        )
    }

    fun getUptimeSeconds(): Long = (System.currentTimeMillis() - startTime) / 1000
}

class ResourceMonitor(private val name: String = "resource-monitor") {
    data class ResourceUsage(
        val cpuPercent: Double,
        val memoryPercent: Double,
        val threadCount: Int,
        val gcLoadPercent: Double,
        val ioWaitEstimate: Double
    )

    private val runtime = Runtime.getRuntime()
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val osBean = ManagementFactory.getOperatingSystemMXBean()
    private val threadBean = ManagementFactory.getThreadMXBean()
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val previousGcTime = AtomicLong(0)
    private val previousGcCount = AtomicLong(0)
    private val sampleCount = AtomicInteger(0)

    fun getCurrentUsage(): ResourceUsage {
        val heap = memoryBean.heapMemoryUsage
        val maxMemory = heap.max.coerceAtLeast(1)
        val usedMemory = heap.used
        val memoryPercent = usedMemory.toDouble() / maxMemory * 100.0

        val cpu = try {
            val method = osBean.javaClass.getMethod("getProcessCpuLoad")
            ((method.invoke(osBean) as? Double) ?: 0.0).coerceAtLeast(0.0) * 100.0
        } catch (e: Exception) { 0.0 }

        val gcTime = gcBeans.sumOf { it.collectionTime }
        val gcCount = gcBeans.sumOf { it.collectionCount }
        val prevGcTime = previousGcTime.getAndSet(gcTime)
        val prevGcCount = previousGcCount.getAndSet(gcCount)

        val gcLoadPercent = if (sampleCount.get() > 0 && prevGcTime > 0) {
            ((gcTime - prevGcTime).toDouble() / 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        sampleCount.incrementAndGet()

        return ResourceUsage(
            cpuPercent = cpu,
            memoryPercent = memoryPercent,
            threadCount = threadBean.threadCount,
            gcLoadPercent = gcLoadPercent,
            ioWaitEstimate = 0.0
        )
    }

    fun isHealthy(): Boolean {
        val usage = getCurrentUsage()
        return usage.cpuPercent < 90 && usage.memoryPercent < 90 && usage.threadCount < 500
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "cpuPercent" to getCurrentUsage().cpuPercent,
        "memoryPercent" to getCurrentUsage().memoryPercent,
        "threadCount" to getCurrentUsage().threadCount,
        "healthy" to isHealthy()
    )
}

class JvmProfiler(private val name: String = "jvm-profiler") {
    private val threadBean = ManagementFactory.getThreadMXBean()
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val classBean = ManagementFactory.getClassLoadingMXBean()
    private val compilBean = ManagementFactory.getCompilationMXBean()

    data class JvmSnapshot(
        val timestamp: Long,
        val threadCount: Int,
        val daemonThreadCount: Int,
        val peakThreadCount: Int,
        val totalStartedThreadCount: Long,
        val heapUsedBytes: Long,
        val heapCommittedBytes: Long,
        val heapMaxBytes: Long,
        val nonHeapUsedBytes: Long,
        val loadedClassCount: Int,
        val totalLoadedClassCount: Long,
        val unloadedClassCount: Long,
        val compilationTimeMs: Long,
        val currentCpuTime: Long,
        val currentUserTime: Long
    )

    fun takeSnapshot(): JvmSnapshot {
        return JvmSnapshot(
            timestamp = System.currentTimeMillis(),
            threadCount = threadBean.threadCount,
            daemonThreadCount = threadBean.daemonThreadCount,
            peakThreadCount = threadBean.peakThreadCount,
            totalStartedThreadCount = threadBean.totalStartedThreadCount,
            heapUsedBytes = memoryBean.heapMemoryUsage.used,
            heapCommittedBytes = memoryBean.heapMemoryUsage.committed,
            heapMaxBytes = memoryBean.heapMemoryUsage.max,
            nonHeapUsedBytes = memoryBean.nonHeapMemoryUsage.used,
            loadedClassCount = classBean.loadedClassCount,
            totalLoadedClassCount = classBean.totalLoadedClassCount,
            unloadedClassCount = classBean.unloadedClassCount,
            compilationTimeMs = compilBean.totalCompilationTime ?: 0L,
            currentCpuTime = try { threadBean.getCurrentThreadCpuTime() } catch (e: Exception) { 0L },
            currentUserTime = try { threadBean.getCurrentThreadUserTime() } catch (e: Exception) { 0L }
        )
    }
}

class PerformanceReport(private val name: String = "report") {
    private val sections = mutableListOf<ReportSection>()

    data class ReportSection(
        val title: String,
        val metrics: Map<String, Any>
    )

    fun addSection(title: String, metrics: Map<String, Any>) {
        sections.add(ReportSection(title, metrics))
    }

    fun generate(): String {
        val sb = StringBuilder()
        sb.appendLine("=================================================================")
        sb.appendLine("  Performance Report: $name")
        sb.appendLine("  Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("=================================================================")
        sb.appendLine()

        for ((index, section) in sections.withIndex()) {
            sb.appendLine("--- ${index + 1}. ${section.title} ---")
            for ((key, value) in section.metrics) {
                sb.appendLine("  $key: $value")
            }
            sb.appendLine()
        }

        sb.appendLine("=================================================================")
        return sb.toString()
    }

    fun generateJson(): String {
        val json = StringBuilder()
        json.appendLine("{")
        json.appendLine("  \"report\": \"$name\",")
        json.appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
        json.appendLine("  \"sections\": [")
        for ((index, section) in sections.withIndex()) {
            json.appendLine("    {")
            json.appendLine("      \"title\": \"${section.title}\",")
            json.appendLine("      \"metrics\": {")
            val entries = section.metrics.entries.toList()
            for ((i, entry) in entries.withIndex()) {
                val comma = if (i < entries.lastIndex) "," else ""
                json.appendLine("        \"${entry.key}\": ${entry.value}$comma")
            }
            json.appendLine("      }")
            val comma = if (index < sections.lastIndex) "," else ""
            json.appendLine("    }$comma")
        }
        json.appendLine("  ]")
        json.appendLine("}")
        return json.toString()
    }

    companion object {
        fun fromCollector(
            collector: MetricsCollector,
            metricName: String = "system"
        ): PerformanceReport {
            val report = PerformanceReport(metricName)
            val metrics = collector.getLatestMetrics()
            if (metrics != null) {
                report.addSection("CPU", mapOf(
                    "usage" to "${"%.1f".format(metrics.cpuUsagePercent)}%",
                    "load" to "${"%.2f".format(metrics.systemLoadAverage)}",
                    "processLoad" to "${"%.2f".format(metrics.processCpuLoad)}"
                ))
                report.addSection("Memory", mapOf(
                    "heapUsed" to formatBytes(metrics.heapUsedBytes),
                    "heapMax" to formatBytes(metrics.heapMaxBytes),
                    "heapUsage" to "${"%.1f".format(metrics.heapUsedBytes.toDouble() / metrics.heapMaxBytes.coerceAtLeast(1) * 100)}%",
                    "nonHeapUsed" to formatBytes(metrics.nonHeapUsedBytes)
                ))
                report.addSection("Threads", mapOf(
                    "active" to metrics.threadCount,
                    "peak" to metrics.peakThreadCount
                ))
                report.addSection("GC", mapOf(
                    "totalCollections" to metrics.gcCount,
                    "totalTimeMs" to metrics.gcTimeMs
                ))
            }
            return report
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
                bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
                else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
            }
        }
    }
}
