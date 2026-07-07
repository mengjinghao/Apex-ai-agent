package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogAnalyticsManager {

    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    data class LogEntry(
        val id: String,
        val timestamp: Long,
        val level: LogLevel,
        val component: String,
        val sessionId: String?,
        val agentId: String?,
        val taskId: String?,
        val message: String,
        val details: Map<String, Any>?,
        val duration: Long? // 操作耗时（毫秒）
    )

    data class AnalyticsResult(
        val totalRequests: Int,
        val successfulRequests: Int,
        val failedRequests: Int,
        val averageResponseTime: Double,
        val errorRate: Double,
        val componentStats: Map<String, ComponentStats>,
        val agentStats: Map<String, AgentStats>,
        val taskTypeStats: Map<String, TaskTypeStats>
    )

    data class ComponentStats(
        val totalRequests: Int,
        val errorCount: Int,
        val averageResponseTime: Double
    )

    data class AgentStats(
        val totalTasks: Int,
        val successfulTasks: Int,
        val failedTasks: Int,
        val averageResponseTime: Double,
        val successRate: Double
    )

    data class TaskTypeStats(
        val totalTasks: Int,
        val averageDifficulty: Double,
        val averageResponseTime: Double,
        val successRate: Double
    )

    private val logs = ConcurrentHashMap<String, LogEntry>()
    private val logIdCounter = AtomicLong(0)
    private val componentStats = ConcurrentHashMap<String, MutableList<Long>>()
    private val agentStats = ConcurrentHashMap<String, MutableList<Pair<Boolean, Long>>>()
    private val taskTypeStats = ConcurrentHashMap<String, MutableList<Triple<Int, Long, Boolean>>>()

    fun log(level: LogLevel, component: String, message: String, sessionId: String? = null, agentId: String? = null, taskId: String? = null, details: Map<String, Any>? = null, duration: Long? = null) {
        val id = "log_${logIdCounter.incrementAndGet()}"
        val entry = LogEntry(
            id = id,
            timestamp = System.currentTimeMillis(),
            level = level,
            component = component,
            sessionId = sessionId,
            agentId = agentId,
            taskId = taskId,
            message = message,
            details = details,
            duration = duration
        )
        logs[id] = entry

        // 更新统计数据
        updateComponentStats(component, duration)
        updateAgentStats(agentId, level == LogLevel.ERROR, duration)
        updateTaskTypeStats(taskId, details?.get("difficulty") as? Int, duration, level != LogLevel.ERROR)
    }

    private fun updateComponentStats(component: String, duration: Long) {
        if (duration != null) {
            componentStats.computeIfAbsent(component) { mutableListOf() }.add(duration)
        }
    }

    private fun updateAgentStats(agentId: String?, isError: Boolean, duration: Long) {
        if (agentId != null && duration != null) {
            agentStats.computeIfAbsent(agentId) { mutableListOf() }.add(Pair(!isError, duration))
        }
    }

    private fun updateTaskTypeStats(taskId: String?, difficulty: Int?, duration: Long?, success: Boolean) {
        if (taskId != null && duration != null) {
            val taskType = taskId.split("_")[0] // 简单提取任务类�?           taskTypeStats.computeIfAbsent(taskType) { mutableListOf() }.add(Triple(difficulty ?: 1, duration, success))
        }
    }

    fun getLogs(level: LogLevel? = null, component: String? = null, sessionId: String? = null, agentId: String? = null): List<LogEntry> {
        return logs.values.filter { entry ->
            (level == null || entry.level == level) &&
            (component == null || entry.component == component) &&
            (sessionId == null || entry.sessionId == sessionId) &&
            (agentId == null || entry.agentId == agentId)
        }.sortedByDescending { it.timestamp }
    }

    fun getLog(id: String): LogEntry? {
        return logs[id]
    }

    fun analyze(): AnalyticsResult {
        val allLogs = logs.values
        val totalRequests = allLogs.size
        val successfulRequests = allLogs.count { it.level != LogLevel.ERROR && it.level != LogLevel.FATAL }
        val failedRequests = allLogs.count { it.level == LogLevel.ERROR || it.level == LogLevel.FATAL }
        val averageResponseTime = allLogs.mapNotNull { it.duration }.average()
        val errorRate = if (totalRequests > 0) failedRequests.toDouble() / totalRequests else 0.0

        val componentStatsMap = componentStats.mapValues { (_, durations) ->
            ComponentStats(
                totalRequests = durations.size,
                errorCount = allLogs.count { it.component == it.key && (it.level == LogLevel.ERROR || it.level == LogLevel.FATAL) },
                averageResponseTime = durations.average()
            )
        }

        val agentStatsMap = agentStats.mapValues { (_, data) ->
            val total = data.size
            val successful = data.count { it.first }
            val totalDuration = data.sumOf { it.second }
            AgentStats(
                totalTasks = total,
                successfulTasks = successful,
                failedTasks = total - successful,
                averageResponseTime = totalDuration.toDouble() / total,
                successRate = successful.toDouble() / total
            )
        }

        val taskTypeStatsMap = taskTypeStats.mapValues { (_, data) ->
            val total = data.size
            val successful = data.count { it.third }
            val totalDifficulty = data.sumOf { it.first }
            val totalDuration = data.sumOf { it.second }
            TaskTypeStats(
                totalTasks = total,
                averageDifficulty = totalDifficulty.toDouble() / total,
                averageResponseTime = totalDuration.toDouble() / total,
                successRate = successful.toDouble() / total
            )
        }

        return AnalyticsResult(
            totalRequests = totalRequests,
            successfulRequests = successfulRequests,
            failedRequests = failedRequests,
            averageResponseTime = averageResponseTime,
            errorRate = errorRate,
            componentStats = componentStatsMap,
            agentStats = agentStatsMap,
            taskTypeStats = taskTypeStatsMap
        )
    }

    fun exportLogs(format: String = "json"): String {
        return when (format.lowercase()) {
            "json" -> exportLogsAsJson()
            "csv" -> exportLogsAsCsv()
            else -> exportLogsAsText()
        }
    }

    private fun exportLogsAsJson(): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        logs.values.forEachIndexed { index, entry ->
            sb.appendLine("  {")
            sb.appendLine("    \"id\": \"${entry.id}",")
            sb.appendLine("    \"timestamp\": ${entry.timestamp},")
            sb.appendLine("    \"level\": \"${entry.level}",")
            sb.appendLine("    \"component\": \"${entry.component}",")
            sb.appendLine("    \"sessionId\": ${if (entry.sessionId != null) "\"${entry.sessionId}\"" else "null"},")
            sb.appendLine("    \"agentId\": ${if (entry.agentId != null) "\"${entry.agentId}\"" else "null"},")
            sb.appendLine("    \"taskId\": ${if (entry.taskId != null) "\"${entry.taskId}\"" else "null"},")
            sb.appendLine("    \"message\": \"${entry.message.replace("\"", "\\\"")}",")
            sb.appendLine("    \"details\": ${if (entry.details != null) entry.details else "null"},")
            sb.appendLine("    \"duration\": ${entry.duration ?: "null"}")
            sb.appendLine("  }${if (index < logs.size - 1) "," else ""}")
        }
        sb.appendLine("]")
        return sb.toString()
    }

    private fun exportLogsAsCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,Timestamp,Level,Component,SessionID,AgentID,TaskID,Message,Duration")
        logs.values.forEach { entry ->
            sb.appendLine("${entry.id},${entry.timestamp},${entry.level},${entry.component},${entry.sessionId ?: ""},${entry.agentId ?: ""},${entry.taskId ?: ""},${entry.message.replace(",", " ")},${entry.duration ?: ""}")
        }
        return sb.toString()
    }

    private fun exportLogsAsText(): String {
        val sb = StringBuilder()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        logs.values.sortedBy { it.timestamp }.forEach { entry ->
            val time = LocalDateTime.ofEpochSecond(entry.timestamp / 1000, ((entry.timestamp % 1000) * 1_000_000).toInt(), java.time.ZoneOffset.UTC)
            sb.appendLine("[${time.format(formatter)}] [${entry.level}] [${entry.component}] ${entry.message}")
            if (entry.sessionId != null) sb.appendLine("  Session: ${entry.sessionId}")
            if (entry.agentId != null) sb.appendLine("  Agent: ${entry.agentId}")
            if (entry.taskId != null) sb.appendLine("  Task: ${entry.taskId}")
            if (entry.duration != null) sb.appendLine("  Duration: ${entry.duration}ms")
            if (entry.details != null) sb.appendLine("  Details: ${entry.details}")
            sb.appendLine()
        }
        return sb.toString()
    }

    fun getErrorLogs(): List<LogEntry> {
        return getLogs(level = LogLevel.ERROR)
    }

    fun getWarningLogs(): List<LogEntry> {
        return getLogs(level = LogLevel.WARN)
    }

    fun getSessionLogs(sessionId: String): List<LogEntry> {
        return getLogs(sessionId = sessionId)
    }

    fun getAgentLogs(agentId: String): List<LogEntry> {
        return getLogs(agentId = agentId)
    }

    fun clearLogs() {
        logs.clear()
        componentStats.clear()
        agentStats.clear()
        taskTypeStats.clear()
    }

    fun getLogCount(): Int {
        return logs.size
    }

    fun getLogCountByLevel(): Map<LogLevel, Int> {
        return logs.values.groupBy { it.level }.mapValues { it.value.size }
    }

    fun getLogCountByComponent(): Map<String, Int> {
        return logs.values.groupBy { it.component }.mapValues { it.value.size }
    }

    // 高级分析功能：识别系统瓶�?   fun identifyBottlenecks(): List<Pair<String, Double>> {
        return componentStats.map { (component, durations) ->
            val avgDuration = durations.average()
            Pair(component, avgDuration)
        }.sortedByDescending { it.second }
    }

    // 高级分析功能：Agent性能排名
    fun rankAgentsByPerformance(): List<Pair<String, Double>> {
        return agentStats.map { (agentId, data) ->
            val successRate = data.count { it.first }.toDouble() / data.size
            val avgDuration = data.sumOf { it.second }.toDouble() / data.size
            val performanceScore = successRate * (1000 / (avgDuration + 1)) // 分数越高越好
            Pair(agentId, performanceScore)
        }.sortedByDescending { it.second }
    }
}
