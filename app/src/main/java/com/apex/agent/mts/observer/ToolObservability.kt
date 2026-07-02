package com.apex.agent.mts.observer

import com.apex.agent.mts.schema.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

data class ToolCallRecord(
    val toolName: String,
    val success: Boolean,
    val durationMs: Long,
    val errorCode: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolMetrics(
    val toolName: String,
    val callCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val successRate: Double,
    val avgLatencyMs: Double,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val totalDurationMs: Long,
    val errorDistribution: Map<String, Int>,
    val lastCalledAt: Long,
    val callsPerMinute: Double
)

data class ToolInsights(
    val mostUsed: List<ToolMetrics>,
    val mostFailed: List<ToolMetrics>,
    val slowest: List<ToolMetrics>,
    val recommendations: List<String>
)

data class ToolTrend(
    val toolName: String,
    val hourlyUsage: List<Int>,
    val successTrend: Double,
    val latencyTrend: Double
)

class ToolObservability(
    private val maxHistoryPerTool: Int = 1000
) {
    private val history = ConcurrentHashMap<String, ConcurrentLinkedDeque<ToolCallRecord>>()
    private val totalCalls = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    private val listeners = mutableListOf<ToolObservabilityListener>()

    fun recordCall(record: ToolCallRecord) {
        val deque = history.getOrPut(record.toolName) {
            ConcurrentLinkedDeque()
        }
        deque.addFirst(record)
        while (deque.size > maxHistoryPerTool) {
            deque.pollLast()
        }
        totalCalls.incrementAndGet()
        if (record.success.not()) {
            totalFailures.incrementAndGet()
        }
        listeners.forEach { it.onToolCallRecorded(record) }
    }

    fun recordBatch(records: List<ToolCallRecord>) {
        records.forEach { recordCall(it) }
    }

    fun getMetrics(toolName: String): ToolMetrics? {
        val records = history[toolName] ?: return null
        val list = records.toList()
        if (list.isEmpty()) return null

        val callCount = list.size.toLong()
        val successCount = list.count { it.success }.toLong()
        val failureCount = callCount - successCount
        val latencies = list.map { it.durationMs }.sorted()
        val totalDuration = list.sumOf { it.durationMs }
        val lastCalled = list.maxOf { it.timestamp }

        val errorDist = list.filter { it.success.not() }
            .groupBy { it.errorCode ?: "UNKNOWN" }
            .mapValues { it.value.size }

        val now = System.currentTimeMillis()
        val lastMinCalls = list.count { now - it.timestamp < 60000 }

        return ToolMetrics(
            toolName = toolName,
            callCount = callCount,
            successCount = successCount,
            failureCount = failureCount,
            successRate = if (callCount > 0) successCount.toDouble() / callCount else 0.0,
            avgLatencyMs = if (callCount > 0) totalDuration.toDouble() / callCount else 0.0,
            p50LatencyMs = latencies.getOrNull((latencies.size * 0.5).toInt()) ?: 0,
            p95LatencyMs = latencies.getOrNull((latencies.size * 0.95).toInt()) ?: 0,
            p99LatencyMs = latencies.getOrNull((latencies.size * 0.99).toInt()) ?: 0,
            totalDurationMs = totalDuration,
            errorDistribution = errorDist,
            lastCalledAt = lastCalled,
            callsPerMinute = lastMinCalls.toDouble()
        )
    }

    fun getAllMetrics(): List<ToolMetrics> {
        return history.keys.mapNotNull { getMetrics(it) }.sortedByDescending { it.callCount }
    }

    fun getInsights(): ToolInsights {
        val all = getAllMetrics()
        return ToolInsights(
            mostUsed = all.sortedByDescending { it.callCount }.take(10),
            mostFailed = all.filter { it.failureCount > 0 }
                .sortedByDescending { it.failureCount }.take(5),
            slowest = all.filter { it.callCount > 0 }
                .sortedByDescending { it.avgLatencyMs }.take(5),
            recommendations = generateRecommendations(all)
        )
    }

    fun getRecentCalls(toolName: String, limit: Int = 20): List<ToolCallRecord> {
        return history[toolName]?.toList()?.take(limit) ?: emptyList()
    }

    fun getRecentCalls(limit: Int = 100): List<ToolCallRecord> {
        return history.values.flatMap { it.toList() }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    fun getTrend(toolName: String): ToolTrend? {
        val records = history[toolName]?.toList()?.sortedBy { it.timestamp } ?: return null
        if (records.size < 2) return null

        val now = System.currentTimeMillis()
        val hourlyBuckets = (0..23).map { hour ->
            val start = now - (23 - hour) * 3600000L
            val end = start + 3600000L
            records.count { it.timestamp in start until end }
        }

        val mid = records.size / 2
        val firstHalf = records.take(mid)
        val secondHalf = records.drop(mid)
        val firstSuccess = if (firstHalf.isNotEmpty()) firstHalf.count { it.success }.toDouble() / firstHalf.size else 0.0
        val secondSuccess = if (secondHalf.isNotEmpty()) secondHalf.count { it.success }.toDouble() / secondHalf.size else 0.0
        val successTrend = secondSuccess - firstSuccess

        val firstLatency = if (firstHalf.isNotEmpty()) firstHalf.sumOf { it.durationMs }.toDouble() / firstHalf.size else 0.0
        val secondLatency = if (secondHalf.isNotEmpty()) secondHalf.sumOf { it.durationMs }.toDouble() / secondHalf.size else 0.0
        val latencyTrend = firstLatency - secondLatency

        return ToolTrend(
            toolName = toolName,
            hourlyUsage = hourlyBuckets,
            successTrend = successTrend,
            latencyTrend = latencyTrend
        )
    }

    fun addListener(listener: ToolObservabilityListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ToolObservabilityListener) {
        listeners.remove(listener)
    }

    fun reset() {
        history.clear()
        totalCalls.set(0)
        totalFailures.set(0)
    }

    val summary: String
        get() {
            val metrics = getAllMetrics()
            val total = totalCalls.get()
            val failed = totalFailures.get()
            val successRate = if (total > 0) "%.1f%%".format((total - failed).toDouble() / total * 100) else "N/A"
            return """
Tool Observability Summary
==========================
Total calls: $total
Total failures: $failed
Overall success rate: $successRate
Registered tools: ${metrics.size}
Most used: ${metrics.take(3).joinToString(", ") { "${it.toolName}(${it.callCount})" }}
Most failed: ${metrics.filter { it.failureCount > 0 }.take(3).joinToString(", ") { "${it.toolName}(${it.failureCount})" }}
            """.trimIndent()
        }

    private fun generateRecommendations(metrics: List<ToolMetrics>): List<String> {
        val recs = mutableListOf<String>()
        for (m in metrics) {
            if (m.successRate < 0.5 && m.failureCount > 5) {
                recs.add("Consider deprecating or fixing '${m.toolName}' (${m.successRate.formatPercent()} success rate)")
            }
            if (m.p95LatencyMs > 10000 && m.callCount > 10) {
                recs.add("'${m.toolName}' has high P95 latency (${m.p95LatencyMs}ms), consider optimization")
            }
            if (m.callsPerMinute > 30) {
                recs.add("'${m.toolName}' is called ${m.callsPerMinute.formatDecimal()}/min, may need rate limiting")
            }
        }
        return recs
    }

    private fun Double.formatPercent(): String = "%.1f%%".format(this * 100)
    private fun Double.formatDecimal(): String = "%.1f".format(this)
}

interface ToolObservabilityListener {
    fun onToolCallRecorded(record: ToolCallRecord) {}
}
