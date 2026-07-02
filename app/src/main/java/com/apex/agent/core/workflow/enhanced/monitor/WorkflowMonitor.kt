package com.apex.agent.core.workflow.enhanced.monitor

import com.apex.agent.core.workflow.enhanced.EnhancedWorkflowExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 工作流监控仪表盘
 *
 * 参照 Airflow 的 Metrics、Temporal 的 Worker Metrics、
 * Prometheus + Grafana 的指标模型
 *
 * 提供实时指标统计：
 * - 工作流执行次数（成功/失败/进行中）
 * - 节点执行统计（按类型）
 * - 平均/最大/最小耗时
 * - 失败率与错误分布
 * - 吞吐量（QPS）
 * - 活跃工作流列表
 * - 资源使用（并发数、内存估算）
 */
class WorkflowMonitor {

    /**
     * 监控快照
     */
    data class MonitorSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val totals: ExecutionTotals,
        val byWorkflow: Map<String, WorkflowStats>,
        val byNodeType: Map<String, NodeTypeStats>,
        val byActionType: Map<String, ActionTypeStats>,
        val errorDistribution: Map<String, Int>,
        val recentExecutions: List<ExecutionSummary>,
        val activeExecutions: List<ActiveExecution>,
        val performance: PerformanceMetrics
    )

    data class ExecutionTotals(
        val totalExecutions: Long,
        val successCount: Long,
        val failureCount: Long,
        val runningCount: Int,
        val cancelledCount: Long,
        val successRate: Float,
        val failureRate: Float
    )

    data class WorkflowStats(
        val workflowId: String,
        val workflowName: String,
        val executionCount: Long,
        val successCount: Long,
        val failureCount: Long,
        val avgDurationMs: Long,
        val maxDurationMs: Long,
        val minDurationMs: Long,
        val lastExecutionAt: Long?
    )

    data class NodeTypeStats(
        val nodeType: String,
        val executionCount: Long,
        val successCount: Long,
        val failureCount: Long,
        val avgDurationMs: Long,
        val retryCount: Long
    )

    data class ActionTypeStats(
        val actionType: String,
        val executionCount: Long,
        val successCount: Long,
        val avgDurationMs: Long,
        val errorRate: Float
    )

    data class ExecutionSummary(
        val threadId: String,
        val workflowId: String,
        val workflowName: String,
        val success: Boolean,
        val durationMs: Long,
        val nodeCount: Int,
        val startedAt: Long,
        val completedAt: Long,
        val error: String?
    )

    data class ActiveExecution(
        val threadId: String,
        val workflowId: String,
        val workflowName: String,
        val startedAt: Long,
        val currentNodeId: String?,
        val progress: Float
    )

    data class PerformanceMetrics(
        val throughputPerMinute: Float,
        val avgLatencyMs: Long,
        val p50LatencyMs: Long,
        val p95LatencyMs: Long,
        val p99LatencyMs: Long,
        val concurrentExecutions: Int,
        val maxConcurrency: Int
    )

    // ============ 内部状态 ============

    private val totalExecutions = AtomicLong(0)
    private val totalSuccess = AtomicLong(0)
    private val totalFailure = AtomicLong(0)
    private val totalCancelled = AtomicLong(0)
    private val activeCount = AtomicLong(0)
    private val maxConcurrency = AtomicLong(0)

    private val workflowStats = ConcurrentHashMap<String, WorkflowStatsInternal>()
    private val nodeTypeStats = ConcurrentHashMap<String, NodeTypeStatsInternal>()
    private val actionTypeStats = ConcurrentHashMap<String, ActionTypeStatsInternal>()
    private val errorDistribution = ConcurrentHashMap<String, AtomicLong>()
    private val recentExecutions = java.util.Collections.synchronizedList(mutableListOf<ExecutionSummary>())
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecutionInternal>()

    private val latencyHistory = java.util.Collections.synchronizedList(mutableListOf<Long>())

    private val _snapshot = MutableStateFlow<MonitorSnapshot?>(null)
    val snapshot: StateFlow<MonitorSnapshot?> = _snapshot.asStateFlow()

    private val maxRecentExecutions = 100
    private val maxLatencyHistory = 10_000

    // ============ 内部数据结构 ============

    private data class WorkflowStatsInternal(
        val workflowId: String,
        val workflowName: String,
        var executionCount: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var totalDurationMs: Long = 0,
        var maxDurationMs: Long = 0,
        var minDurationMs: Long = Long.MAX_VALUE,
        var lastExecutionAt: Long? = null
    )

    private data class NodeTypeStatsInternal(
        val nodeType: String,
        var executionCount: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var totalDurationMs: Long = 0,
        var retryCount: Long = 0
    )

    private data class ActionTypeStatsInternal(
        val actionType: String,
        var executionCount: Long = 0,
        var successCount: Long = 0,
        var totalDurationMs: Long = 0
    )

    private data class ActiveExecutionInternal(
        val threadId: String,
        val workflowId: String,
        val workflowName: String,
        val startedAt: Long,
        var currentNodeId: String? = null,
        var progress: Float = 0f
    )

    // ============ 公共 API ============

    /**
     * 记录工作流开始
     */
    fun onExecutionStarted(threadId: String, workflowId: String, workflowName: String) {
        val current = activeCount.incrementAndGet()
        maxConcurrency.accumulateAndGet(current.toLong()) { a, b -> maxOf(a, b) }
        activeExecutions[threadId] = ActiveExecutionInternal(
            threadId = threadId,
            workflowId = workflowId,
            workflowName = workflowName,
            startedAt = System.currentTimeMillis()
        )
        refreshSnapshot()
    }

    /**
     * 记录工作流完成
     */
    fun onExecutionCompleted(
        threadId: String,
        workflowId: String,
        workflowName: String,
        success: Boolean,
        durationMs: Long,
        nodeCount: Int,
        error: String?
    ) {
        totalExecutions.incrementAndGet()
        if (success) totalSuccess.incrementAndGet() else totalFailure.incrementAndGet()
        activeCount.decrementAndGet()
        activeExecutions.remove(threadId)

        // 更新工作流统计
        workflowStats.compute(workflowId) { _, v ->
            (v ?: WorkflowStatsInternal(workflowId, workflowName)).apply {
                executionCount++
                if (success) successCount++ else failureCount++
                totalDurationMs += durationMs
                if (durationMs > maxDurationMs) maxDurationMs = durationMs
                if (durationMs < minDurationMs) minDurationMs = durationMs
                lastExecutionAt = System.currentTimeMillis()
            }
        }

        // 记录延迟
        latencyHistory.add(durationMs)
        if (latencyHistory.size > maxLatencyHistory) {
            synchronized(latencyHistory) {
                while (latencyHistory.size > maxLatencyHistory) latencyHistory.removeAt(0)
            }
        }

        // 记录最近执行
        recentExecutions.add(ExecutionSummary(
            threadId = threadId,
            workflowId = workflowId,
            workflowName = workflowName,
            success = success,
            durationMs = durationMs,
            nodeCount = nodeCount,
            startedAt = System.currentTimeMillis() - durationMs,
            completedAt = System.currentTimeMillis(),
            error = error
        ))
        if (recentExecutions.size > maxRecentExecutions) {
            synchronized(recentExecutions) {
                while (recentExecutions.size > maxRecentExecutions) recentExecutions.removeAt(0)
            }
        }

        // 错误分布
        if (!success && error != null) {
            val errorType = classifyError(error)
            errorDistribution.computeIfAbsent(errorType) { AtomicLong(0) }.incrementAndGet()
        }

        refreshSnapshot()
    }

    /**
     * 记录工作流取消
     */
    fun onExecutionCancelled(threadId: String) {
        totalCancelled.incrementAndGet()
        activeCount.decrementAndGet()
        activeExecutions.remove(threadId)
        refreshSnapshot()
    }

    /**
     * 记录节点完成
     */
    fun onNodeCompleted(
        threadId: String,
        nodeId: String,
        nodeType: String,
        actionType: String?,
        success: Boolean,
        durationMs: Long,
        retries: Int = 0
    ) {
        nodeTypeStats.compute(nodeType) { _, v ->
            (v ?: NodeTypeStatsInternal(nodeType)).apply {
                executionCount++
                if (success) successCount++ else failureCount++
                totalDurationMs += durationMs
                retryCount += retries
            }
        }

        if (actionType != null && nodeType == "EXECUTE") {
            actionTypeStats.compute(actionType) { _, v ->
                (v ?: ActionTypeStatsInternal(actionType)).apply {
                    executionCount++
                    if (success) successCount++
                    totalDurationMs += durationMs
                }
            }
        }

        // 更新活跃执行的当前节点
        activeExecutions[threadId]?.let { it.currentNodeId = nodeId }

        refreshSnapshot()
    }

    /**
     * 更新进度
     */
    fun updateProgress(threadId: String, progress: Float) {
        activeExecutions[threadId]?.let { it.progress = progress }
    }

    /**
     * 获取当前快照
     */
    fun currentSnapshot(): MonitorSnapshot? = _snapshot.value

    /**
     * 重置所有指标
     */
    fun reset() {
        totalExecutions.set(0)
        totalSuccess.set(0)
        totalFailure.set(0)
        totalCancelled.set(0)
        activeCount.set(0)
        maxConcurrency.set(0)
        workflowStats.clear()
        nodeTypeStats.clear()
        actionTypeStats.clear()
        errorDistribution.clear()
        recentExecutions.clear()
        activeExecutions.clear()
        latencyHistory.clear()
        refreshSnapshot()
    }

    /**
     * 导出 Prometheus 格式指标
     */
    fun exportPrometheusMetrics(): String {
        val sb = StringBuilder()
        sb.appendLine("# HELP workflow_executions_total Total workflow executions")
        sb.appendLine("# TYPE workflow_executions_total counter")
        sb.appendLine("workflow_executions_total{status=\"success\"} ${totalSuccess.get()}")
        sb.appendLine("workflow_executions_total{status=\"failure\"} ${totalFailure.get()}")
        sb.appendLine("workflow_executions_total{status=\"cancelled\"} ${totalCancelled.get()}")
        sb.appendLine()
        sb.appendLine("# HELP workflow_active_executions Currently running workflows")
        sb.appendLine("# TYPE workflow_active_executions gauge")
        sb.appendLine("workflow_active_executions ${activeCount.get()}")
        sb.appendLine()
        sb.appendLine("# HELP workflow_max_concurrency Max concurrent executions")
        sb.appendLine("# TYPE workflow_max_concurrency gauge")
        sb.appendLine("workflow_max_concurrency ${maxConcurrency.get()}")
        sb.appendLine()
        sb.appendLine("# HELP workflow_avg_latency_ms Average workflow latency in ms")
        sb.appendLine("# TYPE workflow_avg_latency_ms gauge")
        val avgLatency = if (latencyHistory.isNotEmpty()) latencyHistory.sum() / latencyHistory.size else 0L
        sb.appendLine("workflow_avg_latency_ms $avgLatency")
        sb.appendLine()
        sb.appendLine("# HELP node_executions_total Total node executions by type")
        sb.appendLine("# TYPE node_executions_total counter")
        nodeTypeStats.forEach { (type, stats) ->
            sb.appendLine("node_executions_total{type=\"$type\",status=\"success\"} ${stats.successCount}")
            sb.appendLine("node_executions_total{type=\"$type\",status=\"failure\"} ${stats.failureCount}")
        }
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun refreshSnapshot() {
        val totals = ExecutionTotals(
            totalExecutions = totalExecutions.get(),
            successCount = totalSuccess.get(),
            failureCount = totalFailure.get(),
            runningCount = activeCount.toInt(),
            cancelledCount = totalCancelled.get(),
            successRate = if (totalExecutions.get() > 0) totalSuccess.get().toFloat() / totalExecutions.get() else 0f,
            failureRate = if (totalExecutions.get() > 0) totalFailure.get().toFloat() / totalExecutions.get() else 0f
        )

        val byWorkflow = workflowStats.mapValues { (_, v) ->
            WorkflowStats(
                workflowId = v.workflowId,
                workflowName = v.workflowName,
                executionCount = v.executionCount,
                successCount = v.successCount,
                failureCount = v.failureCount,
                avgDurationMs = if (v.executionCount > 0) v.totalDurationMs / v.executionCount else 0,
                maxDurationMs = v.maxDurationMs,
                minDurationMs = if (v.minDurationMs == Long.MAX_VALUE) 0 else v.minDurationMs,
                lastExecutionAt = v.lastExecutionAt
            )
        }

        val byNodeType = nodeTypeStats.mapValues { (_, v) ->
            NodeTypeStats(
                nodeType = v.nodeType,
                executionCount = v.executionCount,
                successCount = v.successCount,
                failureCount = v.failureCount,
                avgDurationMs = if (v.executionCount > 0) v.totalDurationMs / v.executionCount else 0,
                retryCount = v.retryCount
            )
        }

        val byActionType = actionTypeStats.mapValues { (_, v) ->
            ActionTypeStats(
                actionType = v.actionType,
                executionCount = v.executionCount,
                successCount = v.successCount,
                avgDurationMs = if (v.executionCount > 0) v.totalDurationMs / v.executionCount else 0,
                errorRate = if (v.executionCount > 0) (v.executionCount - v.successCount).toFloat() / v.executionCount else 0f
            )
        }

        val errors = errorDistribution.mapValues { it.value.get() }

        val recent = recentExecutions.toList().reversed()

        val active = activeExecutions.values.map {
            ActiveExecution(
                threadId = it.threadId,
                workflowId = it.workflowId,
                workflowName = it.workflowName,
                startedAt = it.startedAt,
                currentNodeId = it.currentNodeId,
                progress = it.progress
            )
        }

        val sortedLatencies = latencyHistory.sorted()
        val performance = PerformanceMetrics(
            throughputPerMinute = computeThroughput(),
            avgLatencyMs = if (sortedLatencies.isNotEmpty()) sortedLatencies.average().toLong() else 0,
            p50LatencyMs = percentile(sortedLatencies, 50),
            p95LatencyMs = percentile(sortedLatencies, 95),
            p99LatencyMs = percentile(sortedLatencies, 99),
            concurrentExecutions = activeCount.toInt(),
            maxConcurrency = maxConcurrency.toInt()
        )

        _snapshot.value = MonitorSnapshot(
            totals = totals,
            byWorkflow = byWorkflow,
            byNodeType = byNodeType,
            byActionType = byActionType,
            errorDistribution = errors,
            recentExecutions = recent,
            activeExecutions = active,
            performance = performance
        )
    }

    private fun computeThroughput(): Float {
        if (recentExecutions.isEmpty()) return 0f
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - 60_000L
        val count = recentExecutions.count { it.completedAt >= oneMinuteAgo }
        return count.toFloat()
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = (sorted.size * p / 100).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun classifyError(error: String): String {
        val e = error.lowercase()
        return when {
            e.contains("timeout") -> "TIMEOUT"
            e.contains("network") || e.contains("connection") -> "NETWORK"
            e.contains("permission") || e.contains("auth") -> "PERMISSION"
            e.contains("validation") || e.contains("invalid") -> "VALIDATION"
            e.contains("rate limit") || e.contains("429") -> "RATE_LIMIT"
            e.contains("not found") || e.contains("404") -> "NOT_FOUND"
            e.contains("saga") -> "SAGA_FAILURE"
            e.contains("human") || e.contains("rejected") -> "HUMAN_REJECTED"
            else -> "UNKNOWN"
        }
    }

    companion object {
        @Volatile
        private var instance: WorkflowMonitor? = null

        fun getInstance(): WorkflowMonitor {
            return instance ?: synchronized(this) {
                instance ?: WorkflowMonitor().also { instance = it }
            }
        }
    }
}
