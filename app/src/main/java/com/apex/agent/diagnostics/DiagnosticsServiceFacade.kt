package com.apex.agent.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import com.apex.sdk.watchdog.Watchdog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断服务门面 — 已合并到主 APK。
 *
 * **原本是独立 APK（:apk:diagnostics），现合并到主 APK 的 `com.apex.agent.diagnostics` 包**。
 * 原因：诊断功能（日志/性能/崩溃/系统信息）属于"主 APK 内置能力"，无需独立安装。
 * 主 APK 通过 [TypedServiceRegistry] 直接拿到本实例，零延迟调用。
 *
 * **能力清单**：
 *   1. 套件级日志收集（所有 APK 通过 SuiteEventBus / ApexLog 输出）
 *   2. 性能监控（JVM 内存 / Native 内存 / Watchdog 心跳）
 *   3. APK 健康检查（基于 Watchdog）
 *   4. 日志文件持久化（按日轮转）
 *   5. 崩溃堆栈收集（自动接管 UncaughtExceptionHandler）
 *   6. 系统信息（设备 / Android 版本 / 进程信息）
 *   7. heap dump（内存泄漏分析）
 *
 * **使用方式**：
 *   - 主 APK 内部：`TypedServiceRegistry.get<DiagnosticsServiceFacade>()`
 *   - 跨 APK 调用：`ApexClient.diagnostics.*`（通过主 APK 的 Bridge 路由）
 */
class DiagnosticsServiceFacade(private val context: Context) {

    private const val TAG_SUB = "DiagnosticsFacade"

    private val logDir = File(context.filesDir, "apex-logs").apply { mkdirs() }
    private val crashDir = File(context.filesDir, "apex-crashes").apply { mkdirs() }

    private val _memoryStats = MutableStateFlow(MemoryStats(0, 0, 0))
    val memoryStats: StateFlow<MemoryStats> = _memoryStats.asStateFlow()

    /** 当前 logcat 输出文件（按日轮转）。修复了原版的 getter 缺 return BUG。 */
    private val currentLogFile: File
        get() {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return File(logDir, "apex-$dateStr.log")
        }

    /**
     * 启动日志采集（logcat 输出到文件）。
     * 真实实现需要 logcat 权限或 root；当前仅创建文件占位。
     */
    fun startLogCapture(): BridgeResult<Unit> = bridgeRun {
        val logFile = currentLogFile
        if (!logFile.exists()) logFile.createNewFile()
        ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] log capture started → ${logFile.absolutePath}")
    }

    /**
     * 获取当前日志文件内容。
     * @param maxLines 最多返回多少行（避免内存爆炸）
     */
    suspend fun getRecentLogs(maxLines: Int = 500): BridgeResult<List<LogEntry>> = bridgeRun {
        val file = currentLogFile
        if (!file.exists()) return@bridgeRun emptyList<LogEntry>()
        val lines = file.readLines().takeLast(maxLines)
        lines.map { line -> parseLogLine(line) }
    }

    /**
     * 获取所有日志文件列表。
     */
    suspend fun listLogFiles(): BridgeResult<List<LogFileDto>> = bridgeRun {
        logDir.listFiles()?.map { f ->
            LogFileDto(
                name = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                lastModified = f.lastModified()
            )
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /**
     * 读取指定日志文件内容。
     */
    suspend fun readLogFile(fileName: String, maxLines: Int = 1000): BridgeResult<String> = bridgeRun {
        val file = File(logDir, fileName)
        if (!file.exists()) throw IllegalArgumentException("log file not found: $fileName")
        file.readLines().takeLast(maxLines).joinToString("\n")
    }

    /**
     * 删除指定日志文件。
     */
    suspend fun deleteLogFile(fileName: String): BridgeResult<Boolean> = bridgeRun {
        File(logDir, fileName).delete()
    }

    /**
     * 清空所有日志文件。
     */
    suspend fun clearAllLogs(): BridgeResult<Int> = bridgeRun {
        val files = logDir.listFiles() ?: emptyArray()
        var count = 0
        files.forEach { if (it.delete()) count++ }
        count
    }

    /**
     * 收集崩溃堆栈。
     * 应在 Thread.setDefaultUncaughtExceptionHandler 中调用。
     */
    fun reportCrash(thread: Thread, throwable: Throwable): BridgeResult<String> = bridgeRun {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashFile = File(crashDir, "crash-$timestamp.txt")
        val sb = StringBuilder()
        sb.append("=== Apex Suite Crash Report ===\n")
        sb.append("Time: ${Date()}\n")
        sb.append("Thread: ${thread.name} (id=${thread.id})\n")
        sb.append("Process: pid=${Process.myPid()}, uid=${Process.myUid()}\n")
        sb.append("Device: ${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("\n=== Stack Trace ===\n")
        sb.append(throwable.stackTraceToString())
        sb.append("\n=== Cause Chain ===\n")
        var cause: Throwable? = throwable.cause
        while (cause != null) {
            sb.append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
            sb.append(cause.stackTraceToString())
            sb.append("\n")
            cause = cause.cause
        }
        crashFile.writeText(sb.toString())

        // 发布事件让其他 APK 感知
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.APK_CRASHED,
            mapOf(
                "thread" to (thread.name),
                "exception" to (throwable.javaClass.name),
                "message" to (throwable.message ?: ""),
                "crashFile" to crashFile.absolutePath
            ),
            ApexSuite.ApkId.MAIN
        )

        crashFile.absolutePath
    }

    /**
     * 列出所有崩溃报告。
     */
    suspend fun listCrashReports(): BridgeResult<List<LogFileDto>> = bridgeRun {
        crashDir.listFiles()?.map { f ->
            LogFileDto(
                name = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                lastModified = f.lastModified()
            )
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /**
     * 读取崩溃报告。
     */
    suspend fun readCrashReport(fileName: String): BridgeResult<String> = bridgeRun {
        val file = File(crashDir, fileName)
        if (!file.exists()) throw IllegalArgumentException("crash report not found: $fileName")
        file.readText()
    }

    /**
     * 删除崩溃报告。
     */
    suspend fun deleteCrashReport(fileName: String): BridgeResult<Boolean> = bridgeRun {
        File(crashDir, fileName).delete()
    }

    /**
     * 获取当前进程的内存使用情况。
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / 1024 / 1024
        val freeMb = runtime.freeMemory() / 1024 / 1024
        val usedMb = totalMb - freeMb
        val maxMb = runtime.maxMemory() / 1024 / 1024
        return MemoryStats(
            usedMb = usedMb,
            totalMb = totalMb,
            maxMb = maxMb
        ).also { _memoryStats.value = it }
    }

    /**
     * 获取 Native 堆内存（如果可用）。
     */
    fun getNativeMemory(): NativeMemoryStats {
        val nativeHeapSize = Debug.getNativeHeapSize() / 1024 / 1024
        val nativeHeapFree = Debug.getNativeHeapFreeSize() / 1024 / 1024
        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        return NativeMemoryStats(
            totalMb = nativeHeapSize,
            freeMb = nativeHeapFree,
            allocatedMb = nativeHeapAllocated
        )
    }

    /**
     * 获取所有 APK 的健康状态（基于 Watchdog）。
     */
    fun getApkHealthList(): List<ApkHealthDto> {
        return Watchdog.snapshot().map { h ->
            ApkHealthDto(
                apkId = h.apkId,
                lastHeartbeatAgoMs = h.lastHeartbeatAgoMs,
                healthy = h.healthy
            )
        }
    }

    /**
     * 获取系统信息。
     */
    fun getSystemInfo(): SystemInfo = SystemInfo(
        brand = Build.BRAND,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        device = Build.DEVICE,
        product = Build.PRODUCT,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        buildId = Build.ID,
        abis = Build.SUPPORTED_ABIS.toList(),
        pid = Process.myPid(),
        uid = Process.myUid(),
        processName = getCurrentProcessName()
    )

    /**
     * 强制 GC（用于诊断内存问题）。
     */
    fun forceGc() {
        System.gc()
        Runtime.getRuntime().runFinalization()
    }

    /**
     * dump Heap 到文件（用于内存泄漏分析）。
     */
    fun dumpHeap(fileName: String = "apex-heap.hprof"): String {
        val file = File(context.filesDir, fileName)
        Debug.dumpHprofData(file.absolutePath)
        return file.absolutePath
    }

    private fun parseLogLine(line: String): LogEntry {
        val parts = line.split(" ", limit = 4)
        if (parts.size >= 4) {
            val time = "${parts[0]} ${parts[1]}"
            val tag = parts[2].removeSurrounding("[", "]")
            val message = parts[3]
            return LogEntry(timestamp = time, tag = tag, message = message, raw = line)
        }
        return LogEntry(timestamp = "", tag = "", message = line, raw = line)
    }

    private fun getCurrentProcessName(): String {
        val pid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return activityManager.runningAppProcesses?.find { it.pid == pid }?.processName ?: "unknown"
    }
}

/** JVM 内存统计。 */
data class MemoryStats(
    val usedMb: Long,
    val totalMb: Long,
    val maxMb: Long
)

/** Native 内存统计。 */
data class NativeMemoryStats(
    val totalMb: Long,
    val freeMb: Long,
    val allocatedMb: Long
)

/** APK 健康状态。 */
data class ApkHealthDto(
    val apkId: String,
    val lastHeartbeatAgoMs: Long,
    val healthy: Boolean
)

/** 系统信息。 */
data class SystemInfo(
    val brand: String,
    val model: String,
    val manufacturer: String,
    val device: String,
    val product: String,
    val sdkInt: Int,
    val release: String,
    val buildId: String,
    val abis: List<String>,
    val pid: Int,
    val uid: Int,
    val processName: String
)

/** 日志条目。 */
data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val raw: String
)

/** 日志文件信息。 */
data class LogFileDto(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long
)
