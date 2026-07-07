package com.apex.agent.kernel.burst.enhanced.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B20: 资源监控增强
 *
 * 增强现有 BurstHealthMonitor：
 * - 15+ 维度指标（vs 现有 9）
 * - 历史时间序列
 * - 异常检测
 * - 预警系统
 * - 资源画像
 */
class EnhancedResourceMonitor(
    private val historySize: Int = 1000,
    private val alertThreshold: Float = 0.85f
) {

    data class EnhancedMetrics(
        // 基础指标（增强自 HealthMetrics）
        val cpuUsage: Float,
        val memoryUsageMb: Long,
        val memoryUsagePercent: Float,
        val taskQueueSize: Int,
        val runningTaskCount: Int,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val thermalStatus: Int,
        val maxConcurrency: Int,
        // 新增指标
        val networkLatencyMs: Long,
        val diskIoOpsPerSec: Int,
        val llmTokensPerSec: Float,
        val skillSuccessRate: Float,
        val avgTaskDurationMs: Long,
        val p99TaskDurationMs: Long,
        val errorRate: Float,
        val retryRate: Float,
        val activeConnections: Int,
        val cacheHitRate: Float,
        val gcTimeMs: Long,
        val threadCount: Int,
        val fileDescriptorCount: Int
    )

    data class MetricSnapshot(
        val timestamp: Long,
        val metrics: EnhancedMetrics
    )

    data class Alert(
        val id: String,
        val type: AlertType,
        val severity: AlertSeverity,
        val message: String,
        val value: Float,
        val threshold: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class AlertType {
        CPU_HIGH, MEMORY_HIGH, BATTERY_LOW, THERMAL_HIGH,
        ERROR_RATE_HIGH, LATENCY_HIGH, QUEUE_BACKLOG,
        DISK_FULL, NETWORK_SLOW, CACHE_LOW
    }

    enum class AlertSeverity { INFO, WARNING, CRITICAL }

    data class ResourceProfile(
        val cpuCores: Int,
        val totalMemoryMb: Long,
        val isLowEnd: Boolean,
        val recommendedConcurrency: Int,
        val recommendedTimeoutMs: Long
    )

    private val history = mutableListOf<MetricSnapshot>()
    private val _currentMetrics = MutableStateFlow<EnhancedMetrics?>(null)
    val currentMetrics: StateFlow<EnhancedMetrics?> = _currentMetrics.asStateFlow()

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    private val recentAlerts = mutableListOf<Alert>()
    private val skillSuccessCounts = ConcurrentHashMap<String, Pair<Int, Int>>()  // skillId -> (success, total)
    private val taskDurations = mutableListOf<Long>()
    private val alertCooldowns = ConcurrentHashMap<AlertType, Long>()
    private var resourceProfile: ResourceProfile? = null

    /**
     * 更新指标
     */
    fun updateMetrics(metrics: EnhancedMetrics) {
        _currentMetrics.value = metrics

        // 记录历史
        synchronized(history) {
            history.add(MetricSnapshot(System.currentTimeMillis(), metrics))
            while (history.size > historySize) history.removeAt(0)
        }

        // 记录任务耗时
        if (metrics.avgTaskDurationMs > 0) {
            taskDurations.add(metrics.avgTaskDurationMs)
            while (taskDurations.size > 100) taskDurations.removeAt(0)
        }

        // 检查预警
        checkAlerts(metrics)
    }

    /**
     * 记录技能执行
     */
    fun recordSkillExecution(skillId: String, success: Boolean, durationMs: Long) {
        val (successCount, total) = skillSuccessCounts[skillId] ?: (0 to 0)
        skillSuccessCounts[skillId] = (if (success) successCount + 1 else successCount) to total + 1
    }

    /**
     * 获取资源画像
     */
    fun computeResourceProfile(): ResourceProfile {
        val metrics = _currentMetrics.value
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val totalMem = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val isLowEnd = cpuCores <= 4 || totalMem < 256
        val recommendedConcurrency = if (isLowEnd) 2 else minOf(cpuCores * 2, 8)
        val recommendedTimeout = if (isLowEnd) 60_000L else 30_000L

        val profile = ResourceProfile(cpuCores, totalMem, isLowEnd, recommendedConcurrency, recommendedTimeout)
        resourceProfile = profile
        return profile
    }

    /**
     * 获取历史时间序列
     */
    fun getHistory(): List<MetricSnapshot> = synchronized(history) { history.toList() }

    /**
     * 获取指标趋势
     */
    fun getTrend(metric: String, windowSize: Int = 10): Trend {
        val recent = synchronized(history) { history.takeLast(windowSize) }
        if (recent.size < 2) return Trend.FLAT

        val values = recent.map { snapshot ->
            when (metric) {
                "cpu" -> snapshot.metrics.cpuUsage
                "memory" -> snapshot.metrics.memoryUsagePercent
                "errorRate" -> snapshot.metrics.errorRate
                "latency" -> snapshot.metrics.networkLatencyMs.toFloat()
                "successRate" -> snapshot.metrics.skillSuccessRate
                else -> 0f
            }
        }

        val firstHalf = values.take(values.size / 2).average()
        val secondHalf = values.drop(values.size / 2).average()
        val diff = secondHalf - firstHalf

        return when {
            diff > 0.1 -> Trend.UP
            diff < -0.1 -> Trend.DOWN
            else -> Trend.FLAT
        }
    }

    enum class Trend { UP, DOWN, FLAT }

    /**
     * 获取统计
     */
    fun getStats(): MonitorStats {
        val recent = synchronized(history) { history.takeLast(100) }
        val avgCpu = if (recent.isNotEmpty()) recent.map { it.metrics.cpuUsage }.average().toFloat() else 0f
        val avgMem = if (recent.isNotEmpty()) recent.map { it.metrics.memoryUsagePercent }.average().toFloat() else 0f
        val maxCpu = recent.maxOfOrNull { it.metrics.cpuUsage } ?: 0f
        val maxMem = recent.maxOfOrNull { it.metrics.memoryUsagePercent } ?: 0f

        return MonitorStats(
            totalSnapshots = history.size,
            avgCpuUsage = avgCpu,
            avgMemoryUsage = avgMem,
            maxCpuUsage = maxCpu,
            maxMemoryUsage = maxMem,
            totalAlerts = recentAlerts.size,
            criticalAlerts = recentAlerts.count { it.severity == AlertSeverity.CRITICAL }
        )
    }

    data class MonitorStats(
        val totalSnapshots: Int,
        val avgCpuUsage: Float,
        val avgMemoryUsage: Float,
        val maxCpuUsage: Float,
        val maxMemoryUsage: Float,
        val totalAlerts: Int,
        val criticalAlerts: Int
    )

    // ============ 预警 ============

    private fun checkAlerts(metrics: EnhancedMetrics) {
        val now = System.currentTimeMillis()

        if (metrics.cpuUsage > alertThreshold) {
            emitAlert(AlertType.CPU_HIGH, AlertSeverity.WARNING,
                "CPU 使用率 ${metrics.cpuUsage} 超过阈值 $alertThreshold",
                metrics.cpuUsage, alertThreshold)
        }

        if (metrics.memoryUsagePercent > alertThreshold) {
            emitAlert(AlertType.MEMORY_HIGH, AlertSeverity.WARNING,
                "内存使用率 ${metrics.memoryUsagePercent} 超过阈值 $alertThreshold",
                metrics.memoryUsagePercent, alertThreshold)
        }

        if (metrics.batteryLevel in 1..20 && !metrics.isCharging) {
            emitAlert(AlertType.BATTERY_LOW, AlertSeverity.WARNING,
                "电量低: ${metrics.batteryLevel}%",
                metrics.batteryLevel.toFloat(), 20f)
        }

        if (metrics.thermalStatus >= 3) {
            emitAlert(AlertType.THERMAL_HIGH, AlertSeverity.CRITICAL,
                "设备过热: thermal=$metrics.thermalStatus",
                metrics.thermalStatus.toFloat(), 3f)
        }

        if (metrics.errorRate > 0.3f) {
            emitAlert(AlertType.ERROR_RATE_HIGH, AlertSeverity.CRITICAL,
                "错误率 ${metrics.errorRate} 过高",
                metrics.errorRate, 0.3f)
        }

        if (metrics.networkLatencyMs > 5000) {
            emitAlert(AlertType.NETWORK_SLOW, AlertSeverity.WARNING,
                "网络延迟 ${metrics.networkLatencyMs}ms 过高",
                metrics.networkLatencyMs.toFloat(), 5000f)
        }

        if (metrics.taskQueueSize > 100) {
            emitAlert(AlertType.QUEUE_BACKLOG, AlertSeverity.WARNING,
                "任务队列积压: ${metrics.taskQueueSize}",
                metrics.taskQueueSize.toFloat(), 100f)
        }
    }

    private fun emitAlert(type: AlertType, severity: AlertSeverity, message: String, value: Float, threshold: Float) {
        val now = System.currentTimeMillis()
        val cooldownEnd = alertCooldowns[type] ?: 0
        if (now < cooldownEnd) return  // 冷却中

        val alert = Alert("alert_${now}_${type.name}", type, severity, message, value, threshold)
        recentAlerts.add(alert)
        while (recentAlerts.size > 100) recentAlerts.removeAt(0)
        _alerts.value = recentAlerts.toList()

        // 设置冷却（CRITICAL 30秒，WARNING 5分钟）
        alertCooldowns[type] = now + if (severity == AlertSeverity.CRITICAL) 30_000L else 300_000L
    }

    /**
     * 生成监控报告
     */
    fun generateReport(): String {
        val metrics = _currentMetrics.value ?: return "无监控数据"
        val stats = getStats()
        val sb = StringBuilder()
        sb.appendLine("═══ 资源监控 ═══")
        sb.appendLine("CPU: ${metrics.cpuUsage}% (avg=${stats.avgCpuUsage}%, max=${stats.maxCpuUsage}%)")
        sb.appendLine("内存: ${metrics.memoryUsageMb}MB (${metrics.memoryUsagePercent}%)")
        sb.appendLine("电池: ${metrics.batteryLevel}% ${if (metrics.isCharging) "充电中" else ""}")
        sb.appendLine("温度: ${metrics.thermalStatus}")
        sb.appendLine("并发: ${metrics.runningTaskCount}/${metrics.maxConcurrency}")
        sb.appendLine("队列: ${metrics.taskQueueSize}")
        sb.appendLine("成功率: ${metrics.skillSuccessRate}")
        sb.appendLine("错误率: ${metrics.errorRate}")
        sb.appendLine("缓存命中: ${metrics.cacheHitRate}")
        sb.appendLine()
        if (recentAlerts.isNotEmpty()) {
            sb.appendLine("预警 (${recentAlerts.size}):")
            recentAlerts.takeLast(5).forEach { alert ->
                sb.appendLine("  [${alert.severity}] ${alert.message}")
            }
            sb.appendLine()
        }
        resourceProfile?.let {
            sb.appendLine("资源画像: ${it.cpuCores}核 / ${it.totalMemoryMb}MB ${if (it.isLowEnd) "(低端)" else ""}")
            sb.appendLine("  建议并发: ${it.recommendedConcurrency}")
            sb.appendLine("  建议超时: ${it.recommendedTimeoutMs}ms")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
