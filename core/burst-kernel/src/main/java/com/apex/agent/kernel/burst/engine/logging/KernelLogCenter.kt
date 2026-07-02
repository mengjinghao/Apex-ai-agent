package com.apex.agent.kernel.burst.engine.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * E13: 内核日志中心
 *
 * 结构化日志系统：
 * - 6 个日志级别
 * - 结构化字段
 * - 日志过滤
 * - 日志流
 */
class KernelLogCenter(
    private val maxHistorySize: Int = 5000
) {

    enum class LogLevel(val priority: Int) {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), FATAL(5)
    }

    data class LogEntry(
        val id: Long,
        val level: LogLevel,
        val component: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val thread: String = Thread.currentThread().name,
        val fields: Map<String, Any> = emptyMap(),
        val exception: String? = null
    )

    data class LogFilter(
        val minLevel: LogLevel = LogLevel.INFO,
        val components: Set<String> = emptySet(),
        val messageContains: String? = null
    )

    private val logHistory = mutableListOf<LogEntry>()
    private val idCounter = AtomicLong(0)
    private val _logStream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 512)
    val logStream: SharedFlow<LogEntry> = _logStream.asSharedFlow()
    private var globalFilter: LogFilter = LogFilter()
    private val componentFilters = ConcurrentHashMap<String, LogFilter>()

    fun trace(component: String, message: String, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.TRACE, component, message, fields)

    fun debug(component: String, message: String, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.DEBUG, component, message, fields)

    fun info(component: String, message: String, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.INFO, component, message, fields)

    fun warn(component: String, message: String, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.WARN, component, message, fields)

    fun error(component: String, message: String, exception: Throwable? = null, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.ERROR, component, message, fields, exception?.stackTraceToString()?.take(500))

    fun fatal(component: String, message: String, exception: Throwable? = null, fields: Map<String, Any> = emptyMap()) =
        log(LogLevel.FATAL, component, message, fields, exception?.stackTraceToString()?.take(500))

    fun log(level: LogLevel, component: String, message: String, fields: Map<String, Any> = emptyMap(), exception: String? = null) {
        // 全局过滤
        if (level.priority < globalFilter.minLevel.priority) return
        // 组件过滤
        val compFilter = componentFilters[component]
        if (compFilter != null && level.priority < compFilter.minLevel.priority) return
        // 消息过滤
        if (globalFilter.messageContains != null && !message.contains(globalFilter.messageContains!!, ignoreCase = true)) return

        val entry = LogEntry(idCounter.incrementAndGet(), level, component, message, fields = fields, exception = exception)

        synchronized(logHistory) {
            logHistory.add(entry)
            while (logHistory.size > maxHistorySize) logHistory.removeAt(0)
        }
    }

    suspend fun emitLog(level: LogLevel, component: String, message: String, fields: Map<String, Any> = emptyMap()) {
        log(level, component, message, fields)
        val entry = logHistory.lastOrNull() ?: return
        _logStream.emit(entry)
    }

    fun getHistory(filter: LogFilter = LogFilter(LogLevel.TRACE), limit: Int = 100): List<LogEntry> {
        return synchronized(logHistory) {
            logHistory.filter { entry ->
                entry.level.priority >= filter.minLevel.priority &&
                (filter.components.isEmpty() || entry.component in filter.components) &&
                (filter.messageContains == null || entry.message.contains(filter.messageContains, ignoreCase = true))
            }.takeLast(limit)
        }
    }

    fun setGlobalFilter(filter: LogFilter) { globalFilter = filter }
    fun setComponentFilter(component: String, filter: LogFilter) { componentFilters[component] = filter }

    fun getStats(): LogStats {
        val byLevel = synchronized(logHistory) { logHistory.groupingBy { it.level }.eachCount() }
        val byComponent = synchronized(logHistory) { logHistory.groupingBy { it.component }.eachCount() }
        return LogStats(logHistory.size, byLevel, byComponent)
    }

    data class LogStats(val totalLogs: Int, val byLevel: Map<LogLevel, Int>, val byComponent: Map<String, Int>)

    fun clear() {
        synchronized(logHistory) { logHistory.clear() }
    }

    /**
     * 导出日志
     */
    fun export(filter: LogFilter = LogFilter(), limit: Int = 1000): String {
        val entries = getHistory(filter, limit)
        val sb = StringBuilder()
        entries.forEach { entry ->
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(entry.timestamp))
            val levelStr = entry.level.name.padEnd(5)
            val compStr = entry.component.padEnd(20)
            sb.append("[$timeStr] $levelStr $compStr ${entry.message}")
            if (entry.exception != null) sb.append("\n  ${entry.exception}")
            sb.appendLine()
        }
        return sb.toString()
    }
}
