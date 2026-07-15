package com.apex.agent.telemetry

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.component1
import kotlin.collections.component2

data class TelemetryEvent(
    val id: String,
    val type: EventType,
    val category: EventCategory,
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: String,
    val durationMs: Long? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val appVersion: String = "",
    val deviceInfo: DeviceSnapshot? = null
)

data class DeviceSnapshot(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val osVersion: String = Build.VERSION.RELEASE,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val board: String = Build.BOARD,
    val fingerprint: String = Build.FINGERPRINT,
    val display: String = Build.DISPLAY,
    val cpuAbi: String = Build.CPU_ABI
)

enum class EventType(val code: String) {
    APP_START("app_start"), APP_CRASH("app_crash"), APP_ANR("app_anr"),
    SESSION_START("session_start"), SESSION_END("session_end"),
    FEATURE_USED("feature_used"), FEATURE_ERROR("feature_error"),
    PERFORMANCE("performance"), NETWORK_REQUEST("network_req"),
    DATABASE_QUERY("db_query"), MEMORY_WARNING("mem_warning"),
    BATTERY_LOW("battery_low"), CONFIG_CHANGE("config_change"),
    USER_ACTION("user_action"), BACKGROUND_TASK("bg_task"),
    SKILL_EXECUTION("skill_exec"), TOOL_INVOCATION("tool_invoke"),
    SYNC_OPERATION("sync_op"), MIGRATION("migration"),
    PERMISSION_VIOLATION("perm_violation"), CUSTOM("custom")
}

enum class EventCategory(val code: String) {
    SYSTEM("system"), PERFORMANCE("performance"), ERROR("error"),
    USAGE("usage"), SECURITY("security"), NETWORK("network"),
    DATABASE("database"), USER("user"), BACKGROUND("background"),
    BURST("burst"), SKILL("skill"), TOOL("tool")
}

data class TelemetrySession(
    val sessionId: String,
    val startTimeMs: Long,
    val appVersion: String,
    val deviceSnapshot: DeviceSnapshot,
    var lastActivityMs: Long = startTimeMs,
    var eventCount: Int = 0,
    var isActive: Boolean = true,
    var endTimeMs: Long? = null,
    var crashDetected: Boolean = false
)

data class TelemetryReport(
    val reportId: String,
    val sessionId: String,
    val events: List<TelemetryEvent>,
    val summary: TelemetrySummary,
    val createdMs: Long = System.currentTimeMillis(),
    val formatVersion: Int = 1
)

data class TelemetrySummary(
    val totalEvents: Int,
    val errorCount: Int,
    val averageLatencyMs: Double,
    val p95LatencyMs: Double,
    val eventTypeDistribution: Map<EventType, Int>,
    val categoryDistribution: Map<EventCategory, Int>,
    val durationMs: Long,
    val memoryUsageMb: Long,
    val cpuUsagePercent: Double
)

data class TelemetryConfig(
    val enabled: Boolean = true,
    val enableCrashReporting: Boolean = true,
    val enablePerformanceMetrics: Boolean = true,
    val enableFeatureUsage: Boolean = true,
    val enableSessionTracking: Boolean = true,
    val enableNetworkMonitoring: Boolean = false,
    val enableBatteryMonitoring: Boolean = false,
    val samplingRate: Double = 1.0,
    val batchSize: Int = 50,
    val flushIntervalMs: Long = 60000L,
    val maxStoredEvents: Int = 10000,
    val maxReportSize: Int = 500,
    val storageDirectory: String = "telemetry",
    val anonymizeDeviceInfo: Boolean = false,
    val includeMetadata: Boolean = true,
    val developmentMode: Boolean = false
)

data class TelemetrySnapshot(
    val sessionActive: Boolean,
    val sessionDurationMs: Long,
    val totalEventsCollected: Long,
    val eventsSinceLastFlush: Int,
    val storedEventCount: Int,
    val lastFlushTimeMs: Long?,
    val pendingReports: Int,
    val crashDetected: Boolean
)

class TelemetryCollector private constructor() {

    private val events = CopyOnWriteArrayList<TelemetryEvent>()
        private val config = TelemetryConfig()
        private var currentSession: TelemetrySession? = null
    private val eventCount = AtomicLong(0)
        private val flushCount = AtomicInteger(0)
        private val storedReports = mutableListOf<TelemetryReport>()
        private var lastFlushTimeMs: Long? = null
    private val categoryCounts = ConcurrentHashMap<EventCategory, AtomicInteger>()
        private val typeCounts = ConcurrentHashMap<EventType, AtomicInteger>()
        private val latencyAccumulator = CopyOnWriteArrayList<Long>()
        private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var sessionJob: Job? = null
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var instance: TelemetryCollector? = null

        fun getInstance(): TelemetryCollector {
            return instance ?: synchronized(this) {
                instance ?: TelemetryCollector().also { instance = it }
            }
        }
        private const val MAX_LATENCY_SAMPLES = 500
        private const val SESSION_TIMEOUT_MS = 300000L
    }
        fun initialize(ctx: Context, coroutineScope: CoroutineScope) {
        context = ctx
        scope = coroutineScope
        if (!config.enabled) return

        startNewSession()
        sessionJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(SESSION_TIMEOUT_MS)
                checkSessionHeartbeat()
            }
        }
        if (config.enableCrashReporting) {
            installCrashHandler(ctx)
        }
    }
        fun shutdown() {
        sessionJob?.cancel()
        flush()
        currentSession?.let { endSession(it.sessionId) }
    }
        fun recordEvent(event: TelemetryEvent) {
        if (!config.enabled) return
        if (config.samplingRate < 1.0 && Math.random() > config.samplingRate) return

        val enriched = if (config.anonymizeDeviceInfo) {
            event.copy(deviceInfo = anonymize(event.deviceInfo))
        } else event

        events.add(enriched)
        eventCount.incrementAndGet()
        currentSession?.let { s ->
            s.eventCount++
            s.lastActivityMs = System.currentTimeMillis()
        }
        categoryCounts.computeIfAbsent(event.category) { AtomicInteger(0) }.incrementAndGet()
        typeCounts.computeIfAbsent(event.type) { AtomicInteger(0) }.incrementAndGet()
        if (event.durationMs != null) {
            latencyAccumulator.add(event.durationMs)
        if (latencyAccumulator.size > MAX_LATENCY_SAMPLES) latencyAccumulator.removeAt(0)
        }
        if (events.size >= config.batchSize) {
            scope?.launch { flush() }
        }
        if (config.developmentMode) {
            logEvent(event)
        }
    }
        fun record(type: EventType, category: EventCategory, metadata: Map<String, Any> = emptyMap(), durationMs: Long? = null, success: Boolean? = null) {
        val event = TelemetryEvent(
            id = UUID.randomUUID().toString(),
            type = type,
            category = category,
            sessionId = currentSession?.sessionId ?: "no_session",
            durationMs = durationMs,
            success = success,
            metadata = metadata,
            appVersion = currentSession?.appVersion ?: "",
            deviceInfo = currentSession?.deviceSnapshot
        )
        recordEvent(event)
    }
        fun recordError(type: EventType, category: EventCategory, errorCode: String, metadata: Map<String, Any> = emptyMap()) {
        record(type, category, metadata + mapOf("error" to errorCode), success = false)
    }
        fun recordFeatureUsage(featureName: String, durationMs: Long? = null, success: Boolean = true) {
        record(EventType.FEATURE_USED, EventCategory.USAGE, mapOf("feature" to featureName), durationMs, success)
    }
        fun recordPerformance(category: String, durationMs: Long, metadata: Map<String, Any> = emptyMap()) {
        record(EventType.PERFORMANCE, EventCategory.PERFORMANCE, mapOf("category" to category) + metadata, durationMs)
    }
        fun recordCrash(throwable: Throwable, thread: Thread? = null) {
        currentSession?.crashDetected = true
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()
        val metadata = mutableMapOf<String, Any>(
            "exception" to throwable.javaClass.name,
            "message" to (throwable.message ?: ""),
            "stacktrace" to stackTrace,
            "thread" to (thread?.name ?: "unknown")
        )
        throwable.cause?.let { metadata["cause"] = it.javaClass.name }
        record(EventType.APP_CRASH, EventCategory.ERROR, metadata, success = false)

        try {
            flush()
        } catch (_: Exception) {}
    }
        fun startNewSession(): TelemetrySession {
        val session = TelemetrySession(
            sessionId = UUID.randomUUID().toString(),
            startTimeMs = System.currentTimeMillis(),
            appVersion = context?.let { getAppVersion(it) } ?: "unknown",
            deviceSnapshot = DeviceSnapshot()
        )
        currentSession = session
        record(EventType.SESSION_START, EventCategory.SYSTEM, mapOf("session" to session.sessionId))
        session
    }
        fun endSession(sessionId: String): TelemetrySession? {
        val session = currentSession ?: return null
        if (session.sessionId != sessionId) return null
        session.isActive = false
        session.endTimeMs = System.currentTimeMillis()
        record(EventType.SESSION_END, EventCategory.SYSTEM, mapOf(
            "session" to session.sessionId,
            "duration_ms" to (session.endTimeMs!! - session.startTimeMs),
            "events" to session.eventCount
        ))
        flush()
        currentSession
    }
        fun flush(): TelemetryReport? {
        if (events.isEmpty()) return null

        return mutex.withLock {
            val batch = events.take(config.maxReportSize).toList()
            events.removeAll(batch)
        val summary = computeSummary(batch)
        val report = TelemetryReport(
                reportId = UUID.randomUUID().toString(),
                sessionId = currentSession?.sessionId ?: "no_session",
                events = batch,
                summary = summary
            )
            storedReports.add(report)
        if (storedReports.size > config.maxStoredEvents / config.batchSize) {
                storedReports.removeAt(0)
            }
            lastFlushTimeMs = System.currentTimeMillis()
            flushCount.incrementAndGet()
            persistReport(report)
            report
        }
    }
        fun getSession(): TelemetrySession? = currentSession

    fun getPendingEvents(): List<TelemetryEvent> = events.toList()
        fun getStoredReports(): List<TelemetryReport> = storedReports.toList()
        fun getPendingReportCount(): Int = storedReports.size

    fun getSnapshot(): TelemetrySnapshot {
        val session = currentSession
        TelemetrySnapshot(
            sessionActive = session?.isActive ?: false,
            sessionDurationMs = if (session != null) System.currentTimeMillis() - session.startTimeMs else 0L,
            totalEventsCollected = eventCount.get(),
            eventsSinceLastFlush = events.size,
            storedEventCount = events.size + storedReports.sumOf { it.events.size },
            lastFlushTimeMs = lastFlushTimeMs,
            pendingReports = storedReports.size,
            crashDetected = session?.crashDetected ?: false
        )
    }
        fun clear() {
        events.clear()
        storedReports.clear()
        latencyAccumulator.clear()
    }
        fun updateConfig(newConfig: TelemetryConfig) {
        newConfig
    }
        fun exportAsJson(report: TelemetryReport): String {
        val arr = JSONArray()
        for (event in report.events) {
            val obj = JSONObject()
            obj.put("id", event.id)
            obj.put("type", event.type.code)
            obj.put("category", event.category.code)
            obj.put("timestamp", event.timestampMs)
            obj.put("session_id", event.sessionId)
            event.durationMs?.let { obj.put("duration_ms", it) }
            event.success?.let { obj.put("success", it) }
            event.errorCode?.let { obj.put("error_code", it) }
        if (event.metadata.isNotEmpty()) {
                obj.put("metadata", JSONObject(event.metadata as Map<String, Any>))
            }
            arr.put(obj)
        }
        JSONObject().apply {
            put("report_id", report.reportId)
            put("session_id", report.sessionId)
            put("created_ms", report.createdMs)
            put("format_version", report.formatVersion)
            put("summary", JSONObject().apply {
                put("total_events", report.summary.totalEvents)
                put("error_count", report.summary.errorCount)
                put("avg_latency_ms", report.summary.averageLatencyMs)
            })
            put("events", arr)
        }.toString(2)
    }
        private fun computeSummary(batch: List<TelemetryEvent>): TelemetrySummary {
        val errors = batch.count { it.success == false }
        val durations = batch.mapNotNull { it.durationMs }
        val avgLatency = if (durations.isNotEmpty()) durations.average() else 0.0
        val sorted = durations.sorted()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val typeDist = batch.groupBy { it.type }.mapValues { it.value.size }
        val catDist = batch.groupBy { it.category }.mapValues { it.value.size }
        TelemetrySummary(
            totalEvents = batch.size,
            errorCount = errors,
            averageLatencyMs = avgLatency,
            p95LatencyMs = p95,
            eventTypeDistribution = typeDist,
            categoryDistribution = catDist,
            durationMs = batch.sumOf { it.durationMs ?: 0L },
            memoryUsageMb = Runtime.getRuntime().totalMemory() / 1024 / 1024,
            cpuUsagePercent = 0.0
        )
    }
        private fun persistReport(report: TelemetryReport) {
        try {
            val dir = File(context?.filesDir, config.storageDirectory)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "report_${report.reportId}.json")
            file.writeText(exportAsJson(report))
        } catch (_: Exception) {}
    }
        private fun installCrashHandler(ctx: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordCrash(throwable, thread)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
        private fun checkSessionHeartbeat() {
        val session = currentSession ?: return
        if (System.currentTimeMillis() - session.lastActivityMs > SESSION_TIMEOUT_MS) {
            endSession(session.sessionId)
            startNewSession()
        }
    }
        private fun getAppVersion(ctx: Context): String {
        try {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pkg.versionName ?: "unknown"} (${pkg.versionCode})"
        } catch (_: Exception) { "unknown" }
    }
        private fun anonymize(snapshot: DeviceSnapshot?): DeviceSnapshot? {
        if (snapshot == null) return null
        val hash = { s: String -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(4).joinToString("") { "%02x".format(it) } }
        snapshot.copy(fingerprint = hash(snapshot.fingerprint), display = hash(snapshot.display), board = "unknown", cpuAbi = "unknown")
    }
        private fun logEvent(event: TelemetryEvent) {
        android.util.Log.d("Telemetry", "[${event.type.code}] ${event.category.code} | ${event.metadata.take(3)}")
    }
}
