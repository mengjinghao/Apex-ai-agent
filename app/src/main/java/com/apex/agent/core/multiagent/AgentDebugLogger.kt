package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentDebugLogger {

    enum class LogLevel(val priority: Int, val tag: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E")
    }

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val agentId: String?,
        val taskId: String?,
        val message: String,
        val throwable: Throwable? = null,
        val metadata: Map<String, Any> = emptyMap()
    ) {
        fun toFormattedString(): String {
            val timeStr = dateFormat.format(Date(timestamp))
            val agentInfo = if (agentId != null) "[${agentId}] " else ""
            val taskInfo = if (taskId != null) "[Task:${taskId}] " else ""
            val throwableStr = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            return "${timeStr} ${level.tag}/${tag}: ${agentInfo}${taskInfo}${message}${throwableStr}"
        }
    }

    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val agentLogs = ConcurrentHashMap<String, CopyOnWriteArrayList<LogEntry>>()
    private val taskLogs = ConcurrentHashMap<String, CopyOnWriteArrayList<LogEntry>>()

    private var currentAgentId: String? = null
    private var currentTaskId: String? = null

    var minLevel = LogLevel.DEBUG
        private set

    private val maxLogs = 10000
    private val maxLogsPerAgent = 1000
    private val maxLogsPerTask = 2000

    private val listeners = CopyOnWriteArrayList<LogListener>()

    interface LogListener {
        fun onLogAdded(entry: LogEntry)
        fun onLogCleared()
    }

    fun setContext(agentId: String? = null, taskId: String? = null) {
        currentAgentId = agentId
        currentTaskId = taskId
    }

    fun verbose(tag: String, message: String, agentId: String? = currentAgentId, taskId: String? = currentTaskId, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.VERBOSE, tag, message, agentId, taskId, metadata = metadata)
    }

    fun debug(tag: String, message: String, agentId: String? = currentAgentId, taskId: String? = currentTaskId, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.DEBUG, tag, message, agentId, taskId, metadata = metadata)
    }

    fun info(tag: String, message: String, agentId: String? = currentAgentId, taskId: String? = currentTaskId, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.INFO, tag, message, agentId, taskId, metadata = metadata)
    }

    fun warn(tag: String, message: String, agentId: String? = currentAgentId, taskId: String? = currentTaskId, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.WARN, tag, message, agentId, taskId, metadata = metadata)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null, agentId: String? = currentAgentId, taskId: String? = currentTaskId, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.ERROR, tag, message, agentId, taskId, throwable, metadata)
    }

    private fun log(level: LogLevel, tag: String, message: String, agentId: String?, taskId: String?, throwable: Throwable? = null, metadata: Map<String, Any> = emptyMap()) {
        if (level.priority < minLevel.priority) {
            return
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            agentId = agentId,
            taskId = taskId,
            message = message,
            throwable = throwable,
            metadata = metadata
        )

        logs.add(entry)
        trimLogsIfNeeded()

        agentId?.let { id ->
            agentLogs.getOrPut(id) { CopyOnWriteArrayList() }.add(entry)
            trimAgentLogsIfNeeded(id)
        }

        taskId?.let { id ->
            taskLogs.getOrPut(id) { CopyOnWriteArrayList() }.add(entry)
            trimTaskLogsIfNeeded(id)
        }

        notifyListeners(entry)
    }

    private fun trimLogsIfNeeded() {
        while (logs.size > maxLogs) {
            logs.removeAt(0)
        }
    }

    private fun trimAgentLogsIfNeeded(agentId: String) {
        agentLogs[agentId]?.let { agentLogList ->
            while (agentLogList.size > maxLogsPerAgent) {
                agentLogList.removeAt(0)
            }
        }
    }

    private fun trimTaskLogsIfNeeded(taskId: String) {
        taskLogs[taskId]?.let { taskLogList ->
            while (taskLogList.size > maxLogsPerTask) {
                taskLogList.removeAt(0)
            }
        }
    }

    fun getAllLogs(): List<LogEntry> = logs.toList()

    fun getLogsByLevel(level: LogLevel): List<LogEntry> {
        return logs.filter { it.level == level }
    }

    fun getLogsByAgent(agentId: String): List<LogEntry> {
        return agentLogs[agentId]?.toList() ?: emptyList()
    }

    fun getLogsByTask(taskId: String): List<LogEntry> {
        return taskLogs[taskId]?.toList() ?: emptyList()
    }

    fun getLogsByTag(tag: String): List<LogEntry> {
        return logs.filter { it.tag == tag }
    }

    fun getLogs(startTime: Long, endTime: Long): List<LogEntry> {
        return logs.filter { it.timestamp in startTime..endTime }
    }

    fun searchLogs(query: String, caseSensitive: Boolean = false): List<LogEntry> {
        val searchQuery = if (caseSensitive) query else query.lowercase()
        return logs.filter { entry ->
            val message = if (caseSensitive) entry.message else entry.message.lowercase()
            val tag = if (caseSensitive) entry.tag else entry.tag.lowercase()
            message.contains(searchQuery) || tag.contains(searchQuery)
        }
    }

    fun getLogsGroupedByTimeInterval(intervalMs: Long): Map<Long, List<LogEntry>> {
        return logs.groupBy { (it.timestamp / intervalMs) * intervalMs }
    }

    fun getLogStatistics(): LogStatistics {
        val levelCounts = logs.groupBy { it.level }.mapValues { it.value.size }
        val agentCounts = agentLogs.mapValues { it.value.size }
        val taskCounts = taskLogs.mapValues { it.value.size }

        val errorLogs = logs.filter { it.level == LogLevel.ERROR }
        val errorWithThrowables = errorLogs.count { it.throwable != null }

        return LogStatistics(
            totalLogs = logs.size,
            levelCounts = levelCounts,
            agentCounts = agentCounts,
            taskCounts = taskCounts,
            errorCount = errorLogs.size,
            errorWithThrowableCount = errorWithThrowables,
            oldestTimestamp = logs.firstOrNull()?.timestamp,
            newestTimestamp = logs.lastOrNull()?.timestamp
        )
    }

    data class LogStatistics(
        val totalLogs: Int,
        val levelCounts: Map<LogLevel, Int>,
        val agentCounts: Map<String, Int>,
        val taskCounts: Map<String, Int>,
        val errorCount: Int,
        val errorWithThrowableCount: Int,
        val oldestTimestamp: Long?,
        val newestTimestamp: Long?
    )

    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    fun clear() {
        logs.clear()
        agentLogs.clear()
        taskLogs.clear()
        listeners.forEach { listener ->
            try {
                listener.onLogCleared()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearAgentLogs(agentId: String) {
        agentLogs.remove(agentId)
    }

    fun clearTaskLogs(taskId: String) {
        taskLogs.remove(taskId)
    }

    fun addListener(listener: LogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(entry: LogEntry) {
        listeners.forEach { listener ->
            try {
                listener.onLogAdded(entry)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun exportLogs(format: ExportFormat = ExportFormat.TEXT): String {
        return when (format) {
            ExportFormat.TEXT -> exportAsText()
            ExportFormat.JSON -> exportAsJson()
            ExportFormat.CSV -> exportAsCsv()
        }
    }

    enum class ExportFormat {
        TEXT, JSON, CSV
    }

    private fun exportAsText(): String {
        return buildString {
            appendLine("=== Agent调试日志 ===")
            appendLine("导出时间: ${dateFormat.format(Date())}")
            appendLine("日志总数: ${logs.size}")
            appendLine()
            logs.forEach { entry ->
                appendLine(entry.toFormattedString())
            }
        }
    }

    private fun exportAsJson(): String {
        val jsonBuilder = StringBuilder()
        jsonBuilder.appendLine("{")
        jsonBuilder.appendLine("  \"exportTime\": \"${dateFormat.format(Date())}\",")
        jsonBuilder.appendLine("  \"totalLogs\": ${logs.size},")
        jsonBuilder.appendLine("  \"logs\": [")

        logs.forEachIndexed { index, entry ->
            jsonBuilder.appendLine("    {")
            jsonBuilder.appendLine("      \"timestamp\": ${entry.timestamp},")
            jsonBuilder.appendLine("      \"level\": \"${entry.level.name}\",")
            jsonBuilder.appendLine("      \"tag\": \"${escapeJson(entry.tag)}\",")
            jsonBuilder.appendLine("      \"agentId\": ${entry.agentId?.let { "\"${it}\"" } ?: "null"},")
            jsonBuilder.appendLine("      \"taskId\": ${entry.taskId?.let { "\"${it}\"" } ?: "null"},")
            jsonBuilder.appendLine("      \"message\": \"${escapeJson(entry.message)}\",")
            jsonBuilder.appendLine("      \"hasThrowable\": ${entry.throwable != null}")
            jsonBuilder.append("    }")
            if (index < logs.size - 1) jsonBuilder.appendLine(",") else jsonBuilder.appendLine()
        }

        jsonBuilder.appendLine("  ]")
        jsonBuilder.appendLine("}")
        return jsonBuilder.toString()
    }

    private fun exportAsCsv(): String {
        return buildString {
            appendLine("Timestamp,Level,Tag,AgentId,TaskId,Message,HasThrowable")
            logs.forEach { entry ->
                appendLine("${entry.timestamp},${entry.level.name},${escapeCsv(entry.tag)},${entry.agentId ?: ""},${entry.taskId ?: ""},${escapeCsv(entry.message)},${entry.throwable != null}")
            }
        }
    }

    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun escapeCsv(str: String): String {
        return if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            "\"${str.replace("\"", "\"\"")}\""
        } else {
            str
        }
    }

    fun getRecentLogs(count: Int, level: LogLevel? = null): List<LogEntry> {
        val filtered = level?.let { logs.filter { entry -> entry.level == level } } ?: logs
        return filtered.takeLast(count)
    }

    fun getErrorLogs(): List<LogEntry> = logs.filter { it.level == LogLevel.ERROR }

    fun getWarningLogs(): List<LogEntry> = logs.filter { it.level == LogLevel.WARN }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        private var instance: AgentDebugLogger? = null

        fun getInstance(): AgentDebugLogger {
            return instance ?: synchronized(this) {
                instance ?: AgentDebugLogger().also { instance = it }
            }
        }
    }
}
