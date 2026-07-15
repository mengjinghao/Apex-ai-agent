package com.apex.agent.telemetry

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class CrashReport(
    val id: String,
    val timestampMs: Long,
    val exceptionClass: String,
    val message: String,
    val stackTrace: String,
    val threadName: String,
    val threadId: Long,
    val isMainThread: Boolean,
    val causeChain: List<String> = emptyList(),
    val deviceInfo: DeviceSnapshot = DeviceSnapshot(),
    val appVersion: String = "",
    val sessionId: String = "",
    val logTail: List<String> = emptyList(),
    val memoryAtCrash: MemorySnapshot = MemorySnapshot(),
    val threads: List<ThreadDump> = emptyList(),
    val flags: Map<String, Any> = emptyMap()
)

data class MemorySnapshot(
    val totalMemoryBytes: Long = 0L,
    val freeMemoryBytes: Long = 0L,
    val maxMemoryBytes: Long = 0L,
    val allocatedObjects: Long = 0L,
    val pendingFinalization: Long = 0L,
    val nativeHeapSize: Long = 0L,
    val nativeHeapAllocated: Long = 0L,
    val nativeHeapFree: Long = 0L
)

data class ThreadDump(
    val threadId: Long,
    val threadName: String,
    val priority: Int,
    val state: String,
    val stackTrace: List<String>,
    val isDaemon: Boolean,
    val isAlive: Boolean
)

data class CrashStatistics(
    val totalCrashes: Long,
    val lastCrash: CrashReport? = null,
    val crashRatePerDay: Double,
    val topExceptions: List<Pair<String, Int>>,
    val timeSinceLastCrashMs: Long,
    val uniqueCrashTypes: Int
)

data class CrashConfig(
    val enabled: Boolean = true,
    val maxStoredReports: Int = 50,
    val collectLogTail: Boolean = true,
    val logTailLines: Int = 50,
    val collectThreadDumps: Boolean = true,
    val collectMemorySnapshot: Boolean = true,
    val maxStackTraceLength: Int = 10000,
    val enableAutoSubmit: Boolean = false,
    val submitEndpoint: String = ""
)

class CrashReporter private constructor() {

    private val crashReports = CopyOnWriteArrayList<CrashReport>()
        private val config = CrashConfig()
        private val totalCrashes = AtomicLong(0)
        private val exceptionTypeCounts = mutableMapOf<String, AtomicInteger>()
        private var context: Context? = null
    private var scope: CoroutineScope? = null
    private val mutex = Mutex()
        private val collector: TelemetryCollector = TelemetryCollector.getInstance()

    companion object {
        @Volatile
        private var instance: CrashReporter? = null

        fun getInstance(): CrashReporter {
            return instance ?: synchronized(this) {
                instance ?: CrashReporter().also { instance = it }
            }
        }
        private const val TAG = "CrashReporter"
    }
        fun initialize(ctx: Context, coroutineScope: CoroutineScope) {
        context = ctx
        scope = coroutineScope
        if (!config.enabled) return
        installCrashHandler()
    }
        fun shutdown() {}
        fun reportCrash(throwable: Throwable, thread: Thread? = null): CrashReport {
        totalCrashes.incrementAndGet()
        val className = throwable.javaClass.name
        exceptionTypeCounts.computeIfAbsent(className) { AtomicInteger(0) }.incrementAndGet()
        val report = buildCrashReport(throwable, thread)
        crashReports.add(report)
        if (crashReports.size > config.maxStoredReports) {
            crashReports.removeAt(0)
        }

        persistCrashReport(report)

        collector.recordCrash(throwable, thread)

        report
    }
        fun getRecentCrashes(limit: Int = 20): List<CrashReport> = crashReports.takeLast(limit)
        fun getCrash(id: String): CrashReport? = crashReports.find { it.id == id }
        fun getCrashCount(): Long = totalCrashes.get()
        fun getStatistics(): CrashStatistics {
        val now = System.currentTimeMillis()
        val lastCrash = crashReports.lastOrNull()
        val timeSinceLast = if (lastCrash != null) now - lastCrash.timestampMs else -1L
        val topExceptions = exceptionTypeCounts.entries
            .sortedByDescending { it.value.get() }
            .take(10)
            .map { it.key to it.value.get() }
        val uptimeDays = if (crashReports.isNotEmpty()) {
            val first = crashReports.first().timestampMs
            val duration = now - first
            if (duration > 0) crashReports.size.toDouble() / (duration / 86400000.0) else 0.0
        } else 0.0
        CrashStatistics(
            totalCrashes = totalCrashes.get(),
            lastCrash = lastCrash,
            crashRatePerDay = uptimeDays,
            topExceptions = topExceptions,
            timeSinceLastCrashMs = timeSinceLast,
            uniqueCrashTypes = exceptionTypeCounts.size
        )
    }
        fun clearReports() {
        crashReports.clear()
        totalCrashes.set(0)
        exceptionTypeCounts.clear()
    }
        fun deleteReport(id: String): Boolean {
        return crashReports.removeAll { it.id == id }
    }
        fun exportReports(destinationPath: String): Int {
        var count = 0
        for (report in crashReports) {
            try {
                val file = File(destinationPath, "crash_${report.id}.txt")
                file.parentFile?.mkdirs()
                file.writeText(formatCrashReport(report))
                count++
            } catch (_: Exception) {}
        }
        count
    }
        fun formatCrashReport(report: CrashReport): String {
        val sb = StringBuilder()
        sb.appendLine("=== Crash Report ===")
        sb.appendLine("ID: ${report.id}")
        sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(report.timestampMs))}")
        sb.appendLine("App Version: ${report.appVersion}")
        sb.appendLine("Session: ${report.sessionId}")
        sb.appendLine()
        sb.appendLine("Exception: ${report.exceptionClass}")
        sb.appendLine("Message: ${report.message}")
        sb.appendLine()
        sb.appendLine("Thread: ${report.threadName} (id=${report.threadId}, main=${report.isMainThread})")
        sb.appendLine()
        sb.appendLine("Stack Trace:")
        sb.appendLine(report.stackTrace)
        if (report.causeChain.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Cause Chain:")
        for (cause in report.causeChain) {
                sb.appendLine("  -> $cause")
            }
        }
        if (report.memoryAtCrash.totalMemoryBytes > 0) {
            sb.appendLine()
            sb.appendLine("Memory at Crash:")
            sb.appendLine("  Total: ${report.memoryAtCrash.totalMemoryBytes / 1024 / 1024}MB")
            sb.appendLine("  Free: ${report.memoryAtCrash.freeMemoryBytes / 1024 / 1024}MB")
            sb.appendLine("  Max: ${report.memoryAtCrash.maxMemoryBytes / 1024 / 1024}MB")
        }
        if (report.threads.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Threads (${report.threads.size}):")
        for (t in report.threads.take(10)) {
                sb.appendLine("  ${t.threadName} (id=${t.threadId}, state=${t.state})")
        for (st in t.stackTrace.take(5)) {
                    sb.appendLine("    at $st")
                }
            }
        }
        if (report.logTail.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Log Tail:")
        for (line in report.logTail.take(20)) {
                sb.appendLine("  $line")
            }
        }
        sb.toString()
    }
        private fun buildCrashReport(throwable: Throwable, thread: Thread? = null): CrashReport {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString().take(config.maxStackTraceLength)
        val causeChain = mutableListOf<String>()
        var cause = throwable.cause
        while (cause != null) {
            causeChain.add("${cause.javaClass.name}: ${cause.message ?: ""}")
            cause = cause.cause
        }
        val activeThread = thread ?: Thread.currentThread()
        val threads = if (config.collectThreadDumps) dumpThreads() else emptyList()
        val memory = if (config.collectMemorySnapshot) captureMemorySnapshot() else MemorySnapshot()
        val logTail = if (config.collectLogTail) captureLogTail() else emptyList()

        CrashReport(
            id = java.util.UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            exceptionClass = throwable.javaClass.name,
            message = throwable.message ?: "",
            stackTrace = stackTrace,
            threadName = activeThread.name,
            threadId = activeThread.id,
            isMainThread = activeThread.name == "main",
            causeChain = causeChain,
            deviceInfo = DeviceSnapshot(),
            appVersion = getAppVersion(),
            sessionId = collector.getSession()?.sessionId ?: "",
            logTail = logTail,
            memoryAtCrash = memory,
            threads = threads
        )
    }
        private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            reportCrash(throwable, thread)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
        private fun dumpThreads(): List<ThreadDump> {
        val threads = mutableListOf<ThreadDump>()
        val threadMap = Thread.getAllStackTraces()
        for ((thread, stack) in threadMap) {
            val frames = stack.map { it.toString() }
            threads.add(ThreadDump(
                threadId = thread.id,
                threadName = thread.name,
                priority = thread.priority,
                state = thread.state.name,
                stackTrace = frames,
                isDaemon = thread.isDaemon,
                isAlive = thread.isAlive
            ))
        }
        threads.sortedBy { it.threadId }
    }
        private fun captureMemorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        MemorySnapshot(
            totalMemoryBytes = runtime.totalMemory(),
            freeMemoryBytes = runtime.freeMemory(),
            maxMemoryBytes = runtime.maxMemory()
        )
    }
        private fun captureLogTail(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -t ${config.logTailLines}")
            process.inputStream.bufferedReader().readLines()
        } catch (_: Exception) { emptyList() }
    }
        private fun persistCrashReport(report: CrashReport) {
        try {
            val dir = File(context?.filesDir, "crash_reports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "crash_${report.id}.txt")
            file.writeText(formatCrashReport(report))
        } catch (_: Exception) {}
    }
        private fun getAppVersion(): String {
        return try {
            val pkg = context?.packageManager?.getPackageInfo(context?.packageName ?: "", 0)
            "${pkg?.versionName ?: "unknown"} (${pkg?.versionCode ?: 0})"
        } catch (_: Exception) { "unknown" }
    }
}
