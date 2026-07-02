package com.apex.agent.kernel.burst.enhanced.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

/**
 * B28: 指标收集增强
 *
 * 增强现有 BurstMetrics：
 * - 30+ 指标维度
 * - 百分位统计（P50/P90/P95/P99）
 * - 时间序列
 * - 自定义指标
 * - 导出 Prometheus 格式
 */
class EnhancedMetricsCollector(
    private val timeSeriesSize: Int = 1000
) {

    data class ComprehensiveMetrics(
        // 基础指标（增强自 BurstMetrics）
        val totalTasks: Long,
        val successfulTasks: Long,
        val failedTasks: Long,
        val cancelledTasks: Long,
        val retryingTasks: Long,
        val currentConcurrency: Int,
        val peakConcurrency: Int,
        // 性能指标
        val avgExecutionTimeMs: Long,
        val p50ExecutionTimeMs: Long,
        val p90ExecutionTimeMs: Long,
        val p95ExecutionTimeMs: Long,
        val p99ExecutionTimeMs: Long,
        val minExecutionTimeMs: Long,
        val maxExecutionTimeMs: Long,
        // 吞吐量
        val tasksPerMinute: Float,
        val tokensPerSecond: Float,
        val totalTokensProcessed: Long,
        // 成功率
        val successRate: Float,
        val failureRate: Float,
        val retryRate: Float,
        // 资源
        val avgMemoryUsageMb: Long,
        val avgCpuUsage: Float,
        val totalEnergyUsedJoules: Float,
        // 技能
        val activeSkills: Int,
        val totalSkillInvocations: Long,
        val avgSkillExecutionTimeMs: Long,
        // 错误
        val totalErrors: Long,
        val errorsByType: Map<String, Long>,
        // 自定义
        val customMetrics: Map<String, Double>
    )

    data class MetricSnapshot(
        val timestamp: Long,
        val concurrency: Int,
        val cpuUsage: Float,
        val memoryMb: Long,
        val tps: Float
    )

    private val totalTasks = AtomicLong(0)
    private val successfulTasks = AtomicLong(0)
    private val failedTasks = AtomicLong(0)
    private val cancelledTasks = AtomicLong(0)
    private val retryingTasks = AtomicLong(0)
    private val totalErrors = AtomicLong(0)
    private val totalSkillInvocations = AtomicLong(0)
    private val totalTokensProcessed = AtomicLong(0)
    private val peakConcurrency = AtomicLong(0)

    private val executionTimes = mutableListOf<Long>()
    private val skillExecutionTimes = mutableListOf<Long>()
    private val errorsByType = ConcurrentHashMap<String, AtomicLong>()
    private val customMetrics = ConcurrentHashMap<String, DoubleAdder>()

    private val timeSeries = mutableListOf<MetricSnapshot>()
    private var currentConcurrency = 0
    private var lastMinuteTaskCount = 0L
    private var lastMinuteTimestamp = System.currentTimeMillis()

    private val _currentMetrics = MutableStateFlow<ComprehensiveMetrics?>(null)
    val currentMetrics: StateFlow<ComprehensiveMetrics?> = _currentMetrics.asStateFlow()

    /**
     * 记录任务完成
     */
    fun recordTaskComplete(success: Boolean, durationMs: Long, tokens: Long = 0, retried: Boolean = false) {
        totalTasks.incrementAndGet()
        if (success) successfulTasks.incrementAndGet()
        else failedTasks.incrementAndGet()
        if (retried) retryingTasks.incrementAndGet()
        totalTokensProcessed.addAndGet(tokens)
        lastMinuteTaskCount++

        synchronized(executionTimes) {
            executionTimes.add(durationMs)
            while (executionTimes.size > 1000) executionTimes.removeAt(0)
        }
    }

    /**
     * 记录任务取消
     */
    fun recordTaskCancel() {
        cancelledTasks.incrementAndGet()
    }

    /**
     * 记录技能调用
     */
    fun recordSkillInvocation(skillId: String, durationMs: Long, success: Boolean) {
        totalSkillInvocations.incrementAndGet()
        synchronized(skillExecutionTimes) {
            skillExecutionTimes.add(durationMs)
            while (skillExecutionTimes.size > 1000) skillExecutionTimes.removeAt(0)
        }
    }

    /**
     * 记录错误
     */
    fun recordError(errorType: String) {
        totalErrors.incrementAndGet()
        errorsByType.computeIfAbsent(errorType) { AtomicLong(0) }.incrementAndGet()
    }

    /**
     * 更新并发
     */
    fun updateConcurrency(count: Int) {
        currentConcurrency = count
        if (count > peakConcurrency.get()) peakConcurrency.set(count.toLong())
    }

    /**
     * 更新资源
     */
    fun updateResourceUsage(cpuUsage: Float, memoryMb: Long) {
        val now = System.currentTimeMillis()
        synchronized(timeSeries) {
            timeSeries.add(MetricSnapshot(now, currentConcurrency, cpuUsage, memoryMb, computeTPS()))
            while (timeSeries.size > timeSeriesSize) timeSeries.removeAt(0)
        }

        // 每分钟重置 TPS 计数
        if (now - lastMinuteTimestamp > 60_000L) {
            lastMinuteTaskCount = 0
            lastMinuteTimestamp = now
        }
    }

    /**
     * 记录自定义指标
     */
    fun recordCustomMetric(name: String, value: Double) {
        customMetrics.computeIfAbsent(name) { DoubleAdder() }.add(value)
    }

    /**
     * 收集指标
     */
    fun collect(): ComprehensiveMetrics {
        val execTimes = synchronized(executionTimes) { executionTimes.toList() }
        val skillTimes = synchronized(skillExecutionTimes) { skillExecutionTimes.toList() }
        val series = synchronized(timeSeries) { timeSeries.toList() }

        val total = totalTasks.get()
        val success = successfulTasks.get()
        val fail = failedTasks.get()

        return ComprehensiveMetrics(
            totalTasks = total,
            successfulTasks = success,
            failedTasks = fail,
            cancelledTasks = cancelledTasks.get(),
            retryingTasks = retryingTasks.get(),
            currentConcurrency = currentConcurrency,
            peakConcurrency = peakConcurrency.get().toInt(),
            avgExecutionTimeMs = if (execTimes.isNotEmpty()) execTimes.average().toLong() else 0,
            p50ExecutionTimeMs = percentile(execTimes, 50),
            p90ExecutionTimeMs = percentile(execTimes, 90),
            p95ExecutionTimeMs = percentile(execTimes, 95),
            p99ExecutionTimeMs = percentile(execTimes, 99),
            minExecutionTimeMs = execTimes.minOrNull() ?: 0,
            maxExecutionTimeMs = execTimes.maxOrNull() ?: 0,
            tasksPerMinute = computeTPS(),
            tokensPerSecond = if (series.isNotEmpty()) totalTokensProcessed.get().toFloat() / ((System.currentTimeMillis() - (series.first().timestamp)) / 1000f) else 0f,
            totalTokensProcessed = totalTokensProcessed.get(),
            successRate = if (total > 0) success.toFloat() / total else 0f,
            failureRate = if (total > 0) fail.toFloat() / total else 0f,
            retryRate = if (total > 0) retryingTasks.get().toFloat() / total else 0f,
            avgMemoryUsageMb = if (series.isNotEmpty()) series.map { it.memoryMb }.average().toLong() else 0,
            avgCpuUsage = if (series.isNotEmpty()) series.map { it.cpuUsage }.average().toFloat() else 0f,
            totalEnergyUsedJoules = 0f,  // 需硬件支持
            activeSkills = 0,  // 由外部注入
            totalSkillInvocations = totalSkillInvocations.get(),
            avgSkillExecutionTimeMs = if (skillTimes.isNotEmpty()) skillTimes.average().toLong() else 0,
            totalErrors = totalErrors.get(),
            errorsByType = errorsByType.mapValues { it.value.get() },
            customMetrics = customMetrics.mapValues { it.value.sum() }
        ).also { _currentMetrics.value = it }
    }

    /**
     * 导出 Prometheus 格式
     */
    fun exportPrometheus(): String {
        val metrics = collect()
        val sb = StringBuilder()
        sb.appendLine("# Burst Mode Metrics")
        sb.appendLine("burst_tasks_total{status=\"success\"} ${metrics.successfulTasks}")
        sb.appendLine("burst_tasks_total{status=\"failed\"} ${metrics.failedTasks}")
        sb.appendLine("burst_tasks_total{status=\"cancelled\"} ${metrics.cancelledTasks}")
        sb.appendLine("burst_concurrency_current ${metrics.currentConcurrency}")
        sb.appendLine("burst_concurrency_peak ${metrics.peakConcurrency}")
        sb.appendLine("burst_execution_time_avg_ms ${metrics.avgExecutionTimeMs}")
        sb.appendLine("burst_execution_time_p95_ms ${metrics.p95ExecutionTimeMs}")
        sb.appendLine("burst_execution_time_p99_ms ${metrics.p99ExecutionTimeMs}")
        sb.appendLine("burst_tasks_per_minute ${metrics.tasksPerMinute}")
        sb.appendLine("burst_tokens_per_second ${metrics.tokensPerSecond}")
        sb.appendLine("burst_success_rate ${metrics.successRate}")
        sb.appendLine("burst_failure_rate ${metrics.failureRate}")
        sb.appendLine("burst_errors_total ${metrics.totalErrors}")
        metrics.errorsByType.forEach { (type, count) ->
            sb.appendLine("burst_errors_by_type{type=\"$type\"} $count")
        }
        metrics.customMetrics.forEach { (name, value) ->
            sb.appendLine("burst_custom_${name} $value")
        }
        return sb.toString()
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val sortedList = sorted.sorted()
        val idx = (sortedList.size * p / 100.0).toInt().coerceIn(0, sortedList.size - 1)
        return sortedList[idx]
    }

    private fun computeTPS(): Float {
        val elapsed = System.currentTimeMillis() - lastMinuteTimestamp
        return if (elapsed > 0) lastMinuteTaskCount.toFloat() / (elapsed / 1000f) * 60f else 0f
    }
}
