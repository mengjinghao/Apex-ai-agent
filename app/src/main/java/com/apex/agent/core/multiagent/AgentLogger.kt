package com.apex.agent.core.multiagent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val agentId: String? = null
)

class AgentLogger(
    private val context: Context,
    private val minLevel: LogLevel = LogLevel.INFO
) {

    companion object {
        private const val MAX_QUEUE_SIZE = 1000
        private const val LOG_FILE = "agent_system_logs.json"
    }

    private val gson = Gson()
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun debug(tag: String, message: String, agentId: String? = null) { log(LogLevel.DEBUG, tag, message, agentId) }
    fun info(tag: String, message: String, agentId: String? = null) { log(LogLevel.INFO, tag, message, agentId) }
    fun warning(tag: String, message: String, agentId: String? = null) { log(LogLevel.WARNING, tag, message, agentId) }
    fun error(tag: String, message: String, agentId: String? = null, error: Throwable? = null) {
        val fullMessage = if (error != null) "${message}\n${Log.getStackTraceString(error)}" else message
        log(LogLevel.ERROR, tag, fullMessage, agentId)
    }

    private fun log(level: LogLevel, tag: String, message: String, agentId: String? = null) {
        if (level.ordinal < minLevel.ordinal) return
        val entry = LogEntry(timestamp = System.currentTimeMillis(), level = level.name, tag = tag, message = message, agentId = agentId)
        logQueue.add(entry)
        while (logQueue.size > MAX_QUEUE_SIZE) { logQueue.poll() }
        androidLog(level, tag, message)
    }

    private fun androidLog(level: LogLevel, tag: String, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }

    fun getRecentLogs(limit: Int = 100): List<LogEntry> = logQueue.takeLast(limit)
    fun getLogsForAgent(agentId: String, limit: Int = 100): List<LogEntry> = logQueue.filter { it.agentId == agentId }.takeLast(limit)

    suspend fun exportLogs(): String {
        val logs = logQueue.toList()
        return gson.toJson(mapOf("count" to logs.size, "exported_at" to dateFormat.format(Date()), "logs" to logs))
    }

    fun clearLogs() { logQueue.clear() }

    fun formatForDisplay(entry: LogEntry): String {
        val time = dateFormat.format(Date(entry.timestamp))
        val agentStr = entry.agentId?.let { "[${it}]" } ?: ""
        return "[${time}] [${entry.level}] ${agentStr} ${entry.tag}: ${entry.message}"
    }
}
