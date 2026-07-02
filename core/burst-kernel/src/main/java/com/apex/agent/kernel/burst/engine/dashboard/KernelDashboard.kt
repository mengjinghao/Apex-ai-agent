package com.apex.agent.kernel.burst.engine.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * E7: 内核指标仪表盘
 *
 * 实时内核运行指标：
 * - 20+ 指标维度
 * - 实时更新
 * - 历史趋势
 * - 导出格式
 */
class KernelDashboard {

    data class KernelMetrics(
        val uptimeMs: Long,
        val state: String,
        val totalTasksExecuted: Long,
        val tasksSucceeded: Long,
        val tasksFailed: Long,
        val tasksCancelled: Long,
        val currentConcurrency: Int,
        val peakConcurrency: Int,
        val avgTaskDurationMs: Long,
        val successRate: Float,
        val throughputPerMinute: Float,
        val activeSkills: Int,
        val loadedPlugins: Int,
        val memoryUsageMb: Long,
        val cpuUsage: Float,
        val queueSize: Int,
        val cacheHitRate: Float,
        val errorRate: Float,
        val avgLatencyMs: Long,
        val totalTokensProcessed: Long
    )

    data class MetricSnapshot(
        val timestamp: Long,
        val metrics: KernelMetrics
    )

    private val _currentMetrics = MutableStateFlow(KernelMetrics(
        0, "STOPPED", 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0f, 0, 0f, 0f, 0, 0
    ))
    val currentMetrics: StateFlow<KernelMetrics> = _currentMetrics.asStateFlow()

    private val history = mutableListOf<MetricSnapshot>()
    private val startedAt = AtomicLong(System.currentTimeMillis())
    private val totalTasks = AtomicLong(0)
    private val succeededTasks = AtomicLong(0)
    private val failedTasks = AtomicLong(0)
    private val cancelledTasks = AtomicLong(0)
    private val peakConcurrency = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val taskDurations = mutableListOf<Long>()
    private var currentConcurrency = 0
    private var lastMinuteTasks = 0L
    private var lastMinuteTime = System.currentTimeMillis()

    fun updateMetrics(
        state: String,
        concurrency: Int,
        activeSkills: Int,
        loadedPlugins: Int,
        memoryMb: Long,
        cpuUsage: Float,
        queueSize: Int,
        cacheHitRate: Float,
        errorRate: Float,
        avgLatencyMs: Long
    ) {
        if (concurrency > peakConcurrency.get()) peakConcurrency.set(concurrency.toLong())
        currentConcurrency = concurrency

        val now = System.currentTimeMillis()
        if (now - lastMinuteTime > 60_000) {
            lastMinuteTasks = 0
            lastMinuteTime = now
        }

        val uptime = now - startedAt.get()
        val total = totalTasks.get()
        val success = succeededTasks.get()
        val avgDuration = if (taskDurations.isNotEmpty()) taskDurations.takeLast(100).average().toLong() else 0
        val throughput = if (uptime > 0) (total.toFloat() / uptime * 60_000) else 0f

        val metrics = KernelMetrics(
            uptimeMs = uptime, state = state,
            totalTasksExecuted = total, tasksSucceeded = success,
            tasksFailed = failedTasks.get(), tasksCancelled = cancelledTasks.get(),
            currentConcurrency = concurrency, peakConcurrency = peakConcurrency.get().toInt(),
            avgTaskDurationMs = avgDuration,
            successRate = if (total > 0) success.toFloat() / total else 0f,
            throughputPerMinute = throughput,
            activeSkills = activeSkills, loadedPlugins = loadedPlugins,
            memoryUsageMb = memoryMb, cpuUsage = cpuUsage,
            queueSize = queueSize, cacheHitRate = cacheHitRate,
            errorRate = errorRate, avgLatencyMs = avgLatencyMs,
            totalTokensProcessed = totalTokens.get()
        )
        _currentMetrics.value = metrics

        synchronized(history) {
            history.add(MetricSnapshot(now, metrics))
            while (history.size > 500) history.removeAt(0)
        }
    }

    fun recordTaskComplete(success: Boolean, durationMs: Long, tokens: Long = 0) {
        totalTasks.incrementAndGet()
        if (success) succeededTasks.incrementAndGet() else failedTasks.incrementAndGet()
        totalTokens.addAndGet(tokens)
        lastMinuteTasks++
        synchronized(taskDurations) {
            taskDurations.add(durationMs)
            while (taskDurations.size > 200) taskDurations.removeAt(0)
        }
    }

    fun recordTaskCancel() { cancelledTasks.incrementAndGet() }

    fun getHistory(): List<MetricSnapshot> = synchronized(history) { history.toList() }

    fun resetUptime() {
        startedAt.set(System.currentTimeMillis())
        totalTasks.set(0); succeededTasks.set(0); failedTasks.set(0); cancelledTasks.set(0)
        peakConcurrency.set(0); totalTokens.set(0)
        taskDurations.clear()
    }

    fun generateDashboard(): String {
        val m = _currentMetrics.value
        val sb = StringBuilder()
        sb.appendLine("═══ 内核仪表盘 ═══")
        sb.appendLine("状态: ${m.state}")
        sb.appendLine("运行时间: ${m.uptimeMs / 1000}s")
        sb.appendLine("并发: ${m.currentConcurrency}/${m.peakConcurrency} (峰值)")
        sb.appendLine("任务: ${m.totalTasksExecuted} (成功 ${m.tasksSucceeded}, 失败 ${m.tasksFailed}, 取消 ${m.tasksCancelled})")
        sb.appendLine("成功率: ${(m.successRate * 100).toInt()}%")
        sb.appendLine("吞吐量: ${m.throughputPerMinute}/min")
        sb.appendLine("平均耗时: ${m.avgTaskDurationMs}ms")
        sb.appendLine("技能/插件: ${m.activeSkills}/${m.loadedPlugins}")
        sb.appendLine("内存: ${m.memoryUsageMb}MB")
        sb.appendLine("CPU: ${(m.cpuUsage * 100).toInt()}%")
        sb.appendLine("队列: ${m.queueSize}")
        sb.appendLine("缓存命中: ${(m.cacheHitRate * 100).toInt()}%")
        sb.appendLine("错误率: ${(m.errorRate * 100).toInt()}%")
        sb.appendLine("Token: ${m.totalTokensProcessed}")
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
