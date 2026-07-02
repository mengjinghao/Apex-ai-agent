package com.apex.agent.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class PerformanceSample(
    val label: String,
    val category: String,
    val startTimeNs: Long,
    val endTimeNs: Long,
    val durationNs: Long,
    val threadName: String,
    val memoryBeforeBytes: Long = 0L,
    val memoryAfterBytes: Long = 0L,
    val tags: Map<String, String> = emptyMap()
)

data class ProfilingResult(
    val label: String,
    val category: String,
    val totalDurationMs: Double,
    val averageDurationMs: Double,
    val minDurationMs: Double,
    val maxDurationMs: Double,
    val p50DurationMs: Double,
    val p95DurationMs: Double,
    val p99DurationMs: Double,
    val sampleCount: Int,
    val memoryDeltaAvg: Long,
    val memoryDeltaMax: Long
)

data class ProfilingSnapshot(
    val timestampMs: Long,
    val activeProfilers: Int,
    val activeSamples: Int,
    val totalSamplesCollected: Long,
    val memoryUsageMb: Long,
    val profilerOverheadMs: Double,
    val slowestCategories: List<Pair<String, Double>>
)

data class ProfilingConfig(
    val enabled: Boolean = true,
    val enableMemoryTracking: Boolean = true,
    val enableThreadTracking: Boolean = true,
    val slowThresholdMs: Long = 100L,
    val maxSamplesPerProfiler: Int = 1000,
    val autoReportThreshold: Int = 100,
    val traceFileEnabled: Boolean = false,
    val traceFileDir: String = "profiler_traces"
)

class PerformanceProfiler private constructor() {

    private val activeProfilers = mutableMapOf<String, MutableList<PerformanceSample>>()
    private val completedProfilers = mutableMapOf<String, ProfilingResult>()
    private val allSamples = CopyOnWriteArrayList<PerformanceSample>()
    private val config = ProfilingConfig()
    private val totalSamples = AtomicLong(0)
    private val overheadAccumulator = CopyOnWriteArrayList<Long>()
    private var scope: CoroutineScope? = null
    private val mutex = Mutex()
    private val collector: TelemetryCollector = TelemetryCollector.getInstance()

    companion object {
        @Volatile
        private var instance: PerformanceProfiler? = null

        fun getInstance(): PerformanceProfiler {
            return instance ?: synchronized(this) {
                instance ?: PerformanceProfiler().also { instance = it }
            }
        }

        private const val MAX_OVERHEAD_SAMPLES = 100
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

    fun startSample(label: String, category: String, tags: Map<String, String> = emptyMap()): PerformanceSample {
        val sample = PerformanceSample(
            label = label,
            category = category,
            startTimeNs = System.nanoTime(),
            endTimeNs = 0L,
            durationNs = 0L,
            threadName = Thread.currentThread().name,
            memoryBeforeBytes = if (config.enableMemoryTracking) getMemoryUsage() else 0L,
            tags = tags
        )
        activeProfilers.computeIfAbsent(label) { mutableListOf() }.add(sample)
        sample
    }

    fun endSample(label: String): PerformanceSample? {
        val now = System.nanoTime()
        val profiler = activeProfilers[label] ?: return null
        val samples = profiler
        val lastIdx = samples.size - 1
        if (lastIdx < 0) return null
        val updated = samples[lastIdx].copy(
            endTimeNs = now,
            durationNs = now - samples[lastIdx].startTimeNs,
            memoryAfterBytes = if (config.enableMemoryTracking) getMemoryUsage() else 0L
        )
        samples[lastIdx] = updated
        allSamples.add(updated)
        totalSamples.incrementAndGet()

        if (config.enableMemoryTracking) {
            val memDelta = updated.memoryAfterBytes - updated.memoryBeforeBytes
            if (memDelta > 1024 * 1024) {
                collector.recordPerformance("memory_spike_$label", memDelta / 1024 / 1024,
                    mapOf("delta_bytes" to memDelta, "sample" to label))
            }
        }

        val durationMs = updated.durationNs / 1_000_000.0
        if (durationMs > config.slowThresholdMs) {
            collector.recordPerformance("slow_operation", durationMs.toLong(),
                mapOf("label" to label, "category" to category, "threshold_ms" to config.slowThresholdMs))
        }

        if (allSamples.size >= config.autoReportThreshold) {
            scope?.launch { autoReport() }
        }
        updated
    }

    fun measure(label: String, category: String, block: () -> Unit): ProfilingResult {
        val sample = startSample(label, category)
        val startMem = if (config.enableMemoryTracking) getMemoryUsage() else 0L
        try {
            block()
        } finally {
            endSample(label)
        }
        getResult(label) ?: ProfilingResult(label, category, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0L, 0L)
    }

    suspend fun measureAsync(label: String, category: String, block: suspend () -> Unit): ProfilingResult {
        val sample = startSample(label, category)
        try {
            block()
        } finally {
            endSample(label)
        }
        getResult(label) ?: ProfilingResult(label, category, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0L, 0L)
    }

    fun getResult(label: String): ProfilingResult? {
        val samples = allSamples.filter { it.label == label }
        if (samples.isEmpty()) return null
        computeResult(label, samples)
    }

    fun getAllResults(): List<ProfilingResult> {
        allSamples.groupBy { it.label }.map { (label, samples) -> computeResult(label, samples) }
    }

    fun getResultsByCategory(category: String): List<ProfilingResult> {
        allSamples.filter { it.category == category }.groupBy { it.label }
            .map { (label, samples) -> computeResult(label, samples) }
    }

    fun getSamples(label: String): List<PerformanceSample> = allSamples.filter { it.label == label }

    fun getSlowSamples(thresholdMs: Long = config.slowThresholdMs): List<PerformanceSample> {
        allSamples.filter { it.durationNs / 1_000_000.0 > thresholdMs }
    }

    fun getSnapshot(): ProfilingSnapshot {
        val categories = allSamples.groupBy { it.category }
            .mapValues { (_, samples) -> samples.map { it.durationNs / 1_000_000.0 }.average() }
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        ProfilingSnapshot(
            timestampMs = System.currentTimeMillis(),
            activeProfilers = activeProfilers.size,
            activeSamples = activeProfilers.values.sumOf { it.size },
            totalSamplesCollected = totalSamples.get(),
            memoryUsageMb = Runtime.getRuntime().totalMemory() / 1024 / 1024,
            profilerOverheadMs = if (overheadAccumulator.isNotEmpty()) overheadAccumulator.average() else 0.0,
            slowestCategories = categories
        )
    }

    fun enableMemoryTracking(enable: Boolean) { config.enableMemoryTracking }

    fun setSlowThreshold(ms: Long) { config.slowThresholdMs }

    fun clearSamples(label: String? = null) {
        if (label != null) {
            allSamples.removeAll { it.label == label }
            activeProfilers.remove(label)
        } else {
            allSamples.clear()
            activeProfilers.clear()
        }
    }

    fun clearCompletedResults() { completedProfilers.clear() }

    fun reset() {
        allSamples.clear()
        activeProfilers.clear()
        completedProfilers.clear()
        overheadAccumulator.clear()
        totalSamples.set(0)
    }

    fun generateReport(): String {
        val results = getAllResults()
        val sb = StringBuilder()
        sb.appendLine("=== Performance Profiler Report ===")
        sb.appendLine("Total samples: ${allSamples.size}")
        results.sortedByDescending { it.totalDurationMs }.forEach { r ->
            sb.appendLine("${r.label} (${r.category}): ${r.sampleCount} samples, avg=${"%.1f".format(r.averageDurationMs)}ms, p95=${"%.1f".format(r.p95DurationMs)}ms, total=${"%.0f".format(r.totalDurationMs)}ms")
        }
        sb.appendLine("Slow operations (>${config.slowThresholdMs}ms): ${getSlowSamples().size}")
        sb.toString()
    }

    fun exportToTelemetry() {
        val results = getAllResults()
        for (r in results) {
            collector.recordPerformance("profiler_${r.category}", r.totalDurationMs.toLong(),
                mapOf("label" to r.label, "samples" to r.sampleCount, "avg_ms" to r.averageDurationMs))
        }
    }

    private fun computeResult(label: String, samples: List<PerformanceSample>): ProfilingResult {
        val durations = samples.map { it.durationNs / 1_000_000.0 }.sorted()
        val category = samples.first().category
        val avg = if (durations.isNotEmpty()) durations.average() else 0.0
        val min = durations.firstOrNull() ?: 0.0
        val max = durations.lastOrNull() ?: 0.0
        val total = durations.sum()
        val p50 = if (durations.isNotEmpty()) durations[durations.size / 2] else 0.0
        val p95Idx = (durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)
        val p95 = if (durations.isNotEmpty()) durations[p95Idx] else 0.0
        val p99Idx = (durations.size * 0.99).toInt().coerceAtMost(durations.size - 1)
        val p99 = if (durations.isNotEmpty()) durations[p99Idx] else 0.0
        val memDeltas = samples.map { it.memoryAfterBytes - it.memoryBeforeBytes }
        val avgMem = if (memDeltas.isNotEmpty()) memDeltas.average().toLong() else 0L
        val maxMem = memDeltas.maxOrNull() ?: 0L
        ProfilingResult(label, category, total, avg, min, max, p50, p95, p99, samples.size, avgMem, maxMem)
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        runtime.totalMemory() - runtime.freeMemory()
    }

    private suspend fun autoReport() {
        val slowSamples = getSlowSamples()
        if (slowSamples.isNotEmpty()) {
            collector.recordPerformance("profiler_auto_report", slowSamples.size.toLong(),
                mapOf("slow_count" to slowSamples.size, "worst_ms" to (slowSamples.maxOfOrNull { it.durationNs / 1_000_000 } ?: 0.0)))
        }
    }
}
