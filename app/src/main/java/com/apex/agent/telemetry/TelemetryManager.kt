package com.apex.agent.telemetry

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryManager private constructor() {

    private val collector = TelemetryCollector.getInstance()
    private val reporter = TelemetryReporter.getInstance()
    private val profiler = PerformanceProfiler.getInstance()
    private val crashReporter = CrashReporter.getInstance()
    private var scope: CoroutineScope? = null
    private val initialized = AtomicBoolean(false)

    companion object {
        @Volatile
        private var instance: TelemetryManager? = null

        fun getInstance(): TelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager().also { instance = it }
            }
        }
    }

    fun initialize(ctx: Context, coroutineScope: CoroutineScope, config: TelemetryConfig = TelemetryConfig()) {
        if (initialized.getAndSet(true)) return
        scope = coroutineScope
        collector.initialize(ctx, coroutineScope)
        crashReporter.initialize(ctx, coroutineScope)
        profiler.initialize(coroutineScope)
        reporter.initialize(coroutineScope)

        collector.record(EventType.APP_START, EventCategory.SYSTEM, mapOf("app_version" to getAppVersion(ctx)))

        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(300000L)
                flushCycle()
            }
        }
    }

    fun shutdown() {
        collector.record(EventType.APP_START, EventCategory.SYSTEM, mapOf("action" to "shutdown"))
        reporter.flushPending()
        collector.shutdown()
        initialized.set(false)
    }

    fun getCollector(): TelemetryCollector = collector
    fun getReporter(): TelemetryReporter = reporter
    fun getProfiler(): PerformanceProfiler = profiler
    fun getCrashReporter(): CrashReporter = crashReporter

    fun recordEvent(type: EventType, category: EventCategory, metadata: Map<String, Any> = emptyMap()) {
        collector.record(type, category, metadata)
    }

    fun recordError(type: EventType, category: EventCategory, errorCode: String, metadata: Map<String, Any> = emptyMap()) {
        collector.recordError(type, category, errorCode, metadata)
    }

    fun recordFeatureUsage(featureName: String, success: Boolean = true) {
        collector.recordFeatureUsage(featureName, success = success)
    }

    fun recordPerformance(category: String, durationMs: Long, metadata: Map<String, Any> = emptyMap()) {
        collector.recordPerformance(category, durationMs, metadata)
    }

    fun <T> measure(label: String, category: String, block: () -> T): T {
        profiler.startSample(label, category)
        try {
            return block()
        } finally {
            profiler.endSample(label)
        }
    }

    suspend fun <T> measureAsync(label: String, category: String, block: suspend () -> T): T {
        profiler.startSample(label, category)
        try {
            return block()
        } finally {
            profiler.endSample(label)
        }
    }

    fun flush() {
        collector.flush()
        reporter.flushPending()
    }

    fun getSnapshot(): TelemetrySnapshot = collector.getSnapshot()

    fun generateFullReport(): String {
        val snapshot = getSnapshot()
        val exportMetrics = reporter.getMetrics()
        val profilingSnapshot = profiler.getSnapshot()
        val crashStats = crashReporter.getStatistics()

        return buildString {
            appendLine("=== Telemetry System Report ===")
            appendLine()
            appendLine("Session:")
            appendLine("  Active: ${snapshot.sessionActive}")
            appendLine("  Events Collected: ${snapshot.totalEventsCollected}")
            appendLine("  Pending Events: ${snapshot.eventsSinceLastFlush}")
            appendLine("  Stored Reports: ${snapshot.pendingReports}")
            appendLine()
            appendLine("Exports:")
            appendLine("  Total: ${exportMetrics.totalExports}")
            appendLine("  Successful: ${exportMetrics.successfulExports}")
            appendLine("  Failed: ${exportMetrics.failedExports}")
            appendLine("  Events Exported: ${exportMetrics.totalEventsExported}")
            appendLine("  Bytes Sent: ${exportMetrics.totalBytesSent}")
            appendLine()
            appendLine("Profiler:")
            appendLine("  Total Samples: ${profilingSnapshot.totalSamplesCollected}")
            appendLine("  Active Samples: ${profilingSnapshot.activeSamples}")
            appendLine("  Memory: ${profilingSnapshot.memoryUsageMb}MB")
            appendLine()
            appendLine("Crash Reporter:")
            appendLine("  Total Crashes: ${crashStats.totalCrashes}")
            appendLine("  Rate/Day: ${"%.2f".format(crashStats.crashRatePerDay)}")
            for ((exc, count) in crashStats.topExceptions.take(5)) {
                appendLine("  $exc: $count")
            }
        }
    }

    private fun flushCycle() {
        collector.flush()
        profiler.exportToTelemetry()
    }

    private fun getAppVersion(ctx: Context): String {
        return try {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pkg.versionName ?: "unknown"} (${pkg.versionCode})"
        } catch (_: Exception) { "unknown" }
    }
}
