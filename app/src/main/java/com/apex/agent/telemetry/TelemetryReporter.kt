package com.apex.agent.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class ExportConfig(
    val endpointUrl: String = "",
    val apiKey: String = "",
    val batchSize: Int = 100,
    val retryCount: Int = 3,
    val retryDelayMs: Long = 5000L,
    val enableCompression: Boolean = true,
    val maxExportSizeBytes: Long = 1024 * 1024,
    val exportDir: String = "telemetry_exports",
    val autoExport: Boolean = false,
    val exportIntervalMs: Long = 300000L,
    val headers: Map<String, String> = emptyMap()
)

data class ExportResult(
    val exportId: String,
    val eventsExported: Int,
    val bytesSent: Long,
    val durationMs: Long,
    val success: Boolean,
    val errorMessage: String? = null,
    val statusCode: Int = 0,
    val reportsIncluded: Int = 0
)

data class ExportMetrics(
    val totalExports: Long,
    val successfulExports: Long,
    val failedExports: Long,
    val totalEventsExported: Long,
    val totalBytesSent: Long,
    val averageLatencyMs: Double,
    val lastExportResult: ExportResult? = null,
    val pendingExports: Int
)

enum class ExportFormat {
    JSON, JSON_LINES, NDJSON, CSV
}

class TelemetryReporter private constructor() {

    private val exportHistory = CopyOnWriteArrayList<ExportResult>()
    private val pendingExports = mutableListOf<TelemetryReport>()
    private val config = ExportConfig()
    private val exportCount = AtomicLong(0)
    private val successCount = AtomicLong(0)
    private val failCount = AtomicLong(0)
    private val totalBytesSent = AtomicLong(0)
    private val totalEventsExported = AtomicLong(0)
    private var scope: CoroutineScope? = null
    private var exportJob: Job? = null
    private val mutex = Mutex()
    private val collector: TelemetryCollector = TelemetryCollector.getInstance()

    companion object {
        @Volatile
        private var instance: TelemetryReporter? = null

        fun getInstance(): TelemetryReporter {
            return instance ?: synchronized(this) {
                instance ?: TelemetryReporter().also { instance = it }
            }
        }

        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 15000
        private const val MAX_PENDING = 50
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        if (config.autoExport && config.endpointUrl.isNotBlank()) {
            exportJob = coroutineScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(config.exportIntervalMs)
                    autoExportCycle()
                }
            }
        }
    }

    fun shutdown() {
        exportJob?.cancel()
        flushPending()
    }

    suspend fun exportToEndpoint(reports: List<TelemetryReport>): ExportResult {
        val startTime = System.currentTimeMillis()
        val exportId = UUID.randomUUID().toString()

        return try {
            val payload = buildPayload(reports)
            val bytes = payload.toByteArray(Charsets.UTF_8)

            if (bytes.size.toLong() > config.maxExportSizeBytes) {
                return ExportResult(exportId, 0, 0, 0, false, "Payload too large: ${bytes.size} > ${config.maxExportSizeBytes}", 413)
            }

            var lastError: String? = null
            var statusCode = 0

            for (attempt in 0 until config.retryCount) {
                try {
                    val url = URL(config.endpointUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = CONNECT_TIMEOUT
                    conn.readTimeout = READ_TIMEOUT
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Content-Length", bytes.size.toString())
                    if (config.apiKey.isNotBlank()) {
                        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                    config.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                    conn.outputStream.use { it.write(bytes) }
                    statusCode = conn.responseCode
                    conn.disconnect()

                    if (statusCode in 200..299) {
                        val eventCount = reports.sumOf { it.events.size }
                        val result = ExportResult(
                            exportId = exportId,
                            eventsExported = eventCount,
                            bytesSent = bytes.size.toLong(),
                            durationMs = System.currentTimeMillis() - startTime,
                            success = true,
                            statusCode = statusCode,
                            reportsIncluded = reports.size
                        )
                        recordResult(result)
                        return result
                    } else {
                        lastError = "HTTP $statusCode"
                        if (attempt < config.retryCount - 1) {
                            delay(config.retryDelayMs * (attempt + 1))
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    if (attempt < config.retryCount - 1) {
                        delay(config.retryDelayMs * (attempt + 1))
                    }
                }
            }

            val result = ExportResult(exportId, 0, 0, System.currentTimeMillis() - startTime, false, lastError, statusCode)
            recordResult(result)
            result
        } catch (e: Exception) {
            val result = ExportResult(exportId, 0, 0, System.currentTimeMillis() - startTime, false, e.message)
            recordResult(result)
            result
        }
    }

    suspend fun exportReports(reports: List<TelemetryReport>, destinationPath: String? = null): ExportResult {
        val startTime = System.currentTimeMillis()
        val exportId = UUID.randomUUID().toString()
        val payload = buildPayload(reports)
        val bytes = payload.toByteArray(Charsets.UTF_8)

        return try {
            if (destinationPath != null) {
                val file = File(destinationPath, "telemetry_export_${System.currentTimeMillis()}.json")
                file.parentFile?.mkdirs()
                file.writeText(payload)
            }
            val eventCount = reports.sumOf { it.events.size }
            val result = ExportResult(
                exportId = exportId,
                eventsExported = eventCount,
                bytesSent = bytes.size.toLong(),
                durationMs = System.currentTimeMillis() - startTime,
                success = true,
                statusCode = 200,
                reportsIncluded = reports.size
            )
            recordResult(result)
            result
        } catch (e: Exception) {
            val result = ExportResult(exportId, 0, 0, System.currentTimeMillis() - startTime, false, e.message)
            recordResult(result)
            result
        }
    }

    suspend fun exportPendingReports(): ExportResult {
        val reports = collector.getStoredReports()
        if (reports.isEmpty()) {
            return ExportResult("none", 0, 0, 0, true)
        }
        if (config.endpointUrl.isNotBlank()) {
            exportToEndpoint(reports)
        } else {
            exportReports(reports)
        }
    }

    fun queueForExport(report: TelemetryReport) {
        pendingExports.add(report)
        if (pendingExports.size >= MAX_PENDING) {
            scope?.launch { flushPending() }
        }
    }

    fun flushPending(): ExportResult? {
        if (pendingExports.isEmpty()) return null
        val batch = pendingExports.toList()
        pendingExports.clear()
        val result = runBlocking { exportReports(batch) }
        result
    }

    fun getMetrics(): ExportMetrics {
        ExportMetrics(
            totalExports = exportCount.get(),
            successfulExports = successCount.get(),
            failedExports = failCount.get(),
            totalEventsExported = totalEventsExported.get(),
            totalBytesSent = totalBytesSent.get(),
            averageLatencyMs = if (exportHistory.isNotEmpty()) exportHistory.map { it.durationMs.toDouble() }.average() else 0.0,
            lastExportResult = exportHistory.lastOrNull(),
            pendingExports = pendingExports.size
        )
    }

    fun getHistory(limit: Int = 20): List<ExportResult> = exportHistory.takeLast(limit)

    fun updateConfig(newConfig: ExportConfig) {
        newConfig
    }

    fun resetMetrics() {
        exportCount.set(0)
        successCount.set(0)
        failCount.set(0)
        totalBytesSent.set(0)
        totalEventsExported.set(0)
        exportHistory.clear()
    }

    fun buildPayload(reports: List<TelemetryReport>): String {
        val root = JSONObject()
        root.put("export_id", UUID.randomUUID().toString())
        root.put("exported_at", System.currentTimeMillis())
        root.put("format_version", 1)

        val allEvents = JSONArray()
        var totalEvents = 0
        for (report in reports) {
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
                    obj.put("metadata", JSONObject(event.metadata))
                }
                event.deviceInfo?.let { di ->
                    val dObj = JSONObject()
                    dObj.put("manufacturer", di.manufacturer)
                    dObj.put("model", di.model)
                    dObj.put("os_version", di.osVersion)
                    dObj.put("sdk_int", di.sdkInt)
                    obj.put("device", dObj)
                }
                allEvents.put(obj)
                totalEvents++
            }
        }
        root.put("total_events", totalEvents)
        root.put("events", allEvents)
        root.toString(2)
    }

    private fun recordResult(result: ExportResult) {
        exportHistory.add(result)
        exportCount.incrementAndGet()
        totalEventsExported.addAndGet(result.eventsExported.toLong())
        totalBytesSent.addAndGet(result.bytesSent)
        if (result.success) successCount.incrementAndGet() else failCount.incrementAndGet()
        if (exportHistory.size > 100) exportHistory.removeAt(0)
    }

    private suspend fun autoExportCycle() {
        val reports = collector.getStoredReports()
        if (reports.isEmpty()) return
        if (config.endpointUrl.isNotBlank()) {
            exportToEndpoint(reports)
        }
    }
}
