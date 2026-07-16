package com.apex.agent.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryUsage
import com.apex.util.AppLogger

data class PerformanceMetrics(
    val sttLatencyMs: Float = 0f,
    val ttsLatencyMs: Float = 0f,
    val totalLatencyMs: Float = 0f,
    val cpuUsagePercent: Float = 0f,
    val memoryUsageMb: Float = 0f,
    val memoryUsagePercent: Float = 0f,
    val threadCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

class PerformanceMonitor private constructor(
    private val context: Context
) {
    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()

    private var sttStartTime: Long = 0L
    private var ttsStartTime: Long = 0L
    private var totalStartTime: Long = 0L

    companion object {
        private const val TAG = "PerformanceMonitor"
        private var instance: PerformanceMonitor? = null

        fun getInstance(context: Context): PerformanceMonitor {
            return instance ?: synchronized(this) {
                instance ?: PerformanceMonitor(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun startSttTimer() {
        sttStartTime = System.currentTimeMillis()
    }

    fun endSttTimer() {
        if (sttStartTime > 0) {
            val elapsed = System.currentTimeMillis() - sttStartTime
            _metrics.value = _metrics.value.copy(
                sttLatencyMs = elapsed,
                timestamp = System.currentTimeMillis()
            )
            sttStartTime = 0L
        }
    }

    fun startTtsTimer() {
        ttsStartTime = System.currentTimeMillis()
    }

    fun endTtsTimer() {
        if (ttsStartTime > 0) {
            val elapsed = System.currentTimeMillis() - ttsStartTime
            _metrics.value = _metrics.value.copy(
                ttsLatencyMs = elapsed,
                timestamp = System.currentTimeMillis()
            )
            ttsStartTime = 0L
        }
    }

    fun startTotalTimer() {
        totalStartTime = System.currentTimeMillis()
    }

    fun endTotalTimer() {
        if (totalStartTime > 0) {
            val elapsed = System.currentTimeMillis() - totalStartTime
            _metrics.value = _metrics.value.copy(
                totalLatencyMs = elapsed,
                timestamp = System.currentTimeMillis()
            )
            totalStartTime = 0L
        }
    }

    fun updateMetrics() {
        try {
            val memoryUsage = memoryBean.heapMemoryUsage
            val usedMemoryMb = memoryUsage.used / (1024 * 1024).toFloat()
            val maxMemoryMb = memoryUsage.max / (1024 * 1024).toFloat()
            val memoryPercent = if (maxMemoryMb > 0) usedMemoryMb / maxMemoryMb else 0f

            val threadCount = Thread.activeCount()

            _metrics.value = _metrics.value.copy(
                memoryUsageMb = usedMemoryMb,
                memoryUsagePercent = memoryPercent,
                threadCount = threadCount,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update metrics", e)
        }
    }

    fun getMemoryInfo(): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemMb = memoryInfo.totalMem / (1024 * 1024).toFloat()
        val availMemMb = memoryInfo.availMem / (1024 * 1024).toFloat()
        val usedMemMb = totalMemMb - availMemMb
        val usedPercent = (usedMemMb / totalMemMb) * 100f

        return MemoryInfo(
            totalMb = totalMemMb,
            availableMb = availMemMb,
            usedMb = usedMemMb,
            usagePercent = usedPercent,
            isLowMemory = memoryInfo.lowMemory
        )
    }

    fun isPerformanceModeEnabled(): Boolean {
        return _metrics.value.sttLatencyMs < 500f && _metrics.value.ttsLatencyMs < 300f
    }

    fun getHealthStatus(): HealthStatus {
        val metrics = _metrics.value
        val memoryInfo = getMemoryInfo()

        return when {
            metrics.sttLatencyMs > 1000f || metrics.ttsLatencyMs > 800f -> HealthStatus.CRITICAL
            metrics.sttLatencyMs > 500f || metrics.ttsLatencyMs > 300f -> HealthStatus.DEGRADED
            memoryInfo.isLowMemory || memoryInfo.usagePercent > 90f -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
    }

    fun resetMetrics() {
        _metrics.value = PerformanceMetrics()
    }
}

data class MemoryInfo(
    val totalMb: Float,
    val availableMb: Float,
    val usedMb: Float,
    val usagePercent: Float,
    val isLowMemory: Boolean
)

enum class HealthStatus {
    HEALTHY,
    WARNING,
    DEGRADED,
    CRITICAL
}

object PerformanceUtils {
    fun formatLatency(ms: Float): String {
        return when {
            ms < 100 -> "极快"
            ms < 300 -> "快?
            ms < 500 -> "正常"
            ms < 1000 -> "较慢"
            else -> "很慢"
        }
    }

    fun formatMemory(mb: Float): String {
        return when {
            mb < 50 -> "极低"
            mb < 100 -> "较低"
            mb < 200 -> "正常"
            mb < 500 -> "较高"
            else -> "很高"
        }
    }

    fun getDeviceMemoryClass(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.memoryClass
    }

    fun isLowMemoryDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isLowRamDevice
    }

    fun getAvailableProcessors(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    fun getMaxMemoryMb(): Float {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024).toFloat()
    }

    fun getTotalMemoryMb(): Float {
        return Runtime.getRuntime().totalMemory() / (1024 * 1024).toFloat()
    }

    fun getFreeMemoryMb(): Float {
        return Runtime.getRuntime().freeMemory() / (1024 * 1024).toFloat()
    }
}
