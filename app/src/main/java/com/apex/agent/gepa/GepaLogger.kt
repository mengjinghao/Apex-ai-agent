package com.apex.gepa

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object GepaLogger {

    private const val TAG = "GEPA"

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val isEnabled = AtomicBoolean(true)
    private val maxLogSize = 1000

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }

    fun v(message: String, tag: String = TAG, context: Map<String, Any>? = null) {
        log(LogLevel.VERBOSE, message, tag, context)
    }

    fun d(message: String, tag: String = TAG, context: Map<String, Any>? = null) {
        log(LogLevel.DEBUG, message, tag, context)
    }

    fun i(message: String, tag: String = TAG, context: Map<String, Any>? = null) {
        log(LogLevel.INFO, message, tag, context)
    }

    fun w(message: String, tag: String = TAG, context: Map<String, Any>? = null) {
        log(LogLevel.WARNING, message, tag, context)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = TAG, context: Map<String, Any>? = null) {
        log(LogLevel.ERROR, message, tag, context, throwable)
    }

    fun skillExtracted(skillId: Int, taskType: String, successRate: Float) {
        i("Skill extracted: id=${skillId}, type=${taskType}, rate=${successRate}", tag = "SkillExtractor")
    }

    fun skillMatched(taskType: String, matched: Boolean, skillId: Int? = null) {
        val msg = if (matched) "Skill matched: type=${taskType}, id=${skillId}" else "No skill matched: type=${taskType}"
        i(msg, tag = "SkillMatcher")
    }

    fun taskStarted(taskId: String, taskType: String, subtaskCount: Int) {
        i("Task started: id=${taskId}, type=${taskType}, subtasks=${subtaskCount}", tag = "TaskScheduler")
    }

    fun taskCompleted(taskId: String, success: Boolean, duration: Long) {
        val msg = if (success) "Task completed: id=${taskId}, duration=${duration} ms" else "Task failed: id=${taskId}, duration=${duration} ms"
        i(msg, tag = "TaskScheduler")
    }

    fun subtaskCompleted(subtaskId: String, agentId: String, success: Boolean, duration: Long) {
        val msg = "Subtask completed: id=${subtaskId}, agent=${agentId}, success=${success}, duration=${duration} ms"
        d(msg, tag = "TaskScheduler")
    }

    fun agentRegistered(agentId: String, agentType: String) {
        i("Agent registered: id=${agentId}, type=${agentType}", tag = "AgentRegistry")
    }

    fun agentUnregistered(agentId: String) {
        i("Agent unregistered: id=${agentId}", tag = "AgentRegistry")
    }

    fun agentCircuitBreakerOpened(agentId: String) {
        w("Circuit breaker opened: agent=${agentId}", tag = "CircuitBreaker")
    }

    fun agentCircuitBreakerClosed(agentId: String) {
        i("Circuit breaker closed: agent=${agentId}", tag = "CircuitBreaker")
    }

    fun cacheHit(key: String) {
        d("Cache hit: key=${key}", tag = "Cache")
    }

    fun cacheMiss(key: String) {
        d("Cache miss: key=${key}", tag = "Cache")
    }

    fun cacheEviction(key: String) {
        d("Cache eviction: key=${key}", tag = "Cache")
    }

    private fun log(
        level: LogLevel,
        message: String,
        tag: String,
        context: Map<String, Any>? = null,
        throwable: Throwable? = null
    ) {
        if (!isEnabled.get()) return

        val timestamp = System.currentTimeMillis()
        val entry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            context = context,
            throwable = throwable
        )

        logQueue.offer(entry)
        trimLogQueue()

        val androidLogLevel = when (level) {
            LogLevel.VERBOSE -> Log.VERBOSE
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARNING -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }

        val fullMessage = if (context != null) {
            "${message} | context=${context}"
        } else {
            message
        }

        Log.println(androidLogLevel, tag, fullMessage)

        if (throwable != null) {
            Log.println(androidLogLevel, tag, Log.getStackTraceString(throwable))
        }

        updateLogsState()
    }

    private fun trimLogQueue() {
        while (logQueue.size > maxLogSize) {
            logQueue.poll()
        }
    }

    private fun updateLogsState() {
        _logs.value = logQueue.toList()
    }

    fun clearLogs() {
        logQueue.clear()
        updateLogsState()
    }

    fun getLogs(limit: Int = 100, level: LogLevel? = null): List<LogEntry> {
        val filtered = if (level != null) {
            logQueue.filter { it.level >= level }
        } else {
            logQueue.toList()
        }
        return filtered.takeLast(limit)
    }

    fun getLogsByTag(tag: String, limit: Int = 100): List<LogEntry> {
        return logQueue.filter { it.tag == tag }.takeLast(limit)
    }

    fun getLogsByTaskId(taskId: String, limit: Int = 100): List<LogEntry> {
        return logQueue.filter { it.context?.get("taskId") == taskId }.takeLast(limit)
    }

    fun exportLogs(): String {
        return buildString {
            appendLine("GEPA System Logs")
            appendLine("=================")
            appendLine("Exported at: ${dateFormat.format(Date())}")
            appendLine("Total logs: ${logQueue.size}")
            appendLine()

            logQueue.forEach { entry ->
                appendLine("[${dateFormat.format(Date(entry.timestamp))}] ${entry.level}: ${entry.tag}: ${entry.message}")
                entry.context?.let { ctx ->
                    appendLine("  Context: ${ctx}")
                }
                entry.throwable?.let { t ->
                    appendLine("  Exception: ${Log.getStackTraceString(t)}")
                }
            }
        }
    }

    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val context: Map<String, Any>? = null,
        val throwable: Throwable? = null
    )
}

class GepaPerformanceMonitor {

    private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
    val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics.asStateFlow()

    private val operationTimings = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxSamplesPerOperation = 100

    fun recordOperation(operationName: String, durationMs: Long) {
        val timings = operationTimings.computeIfAbsent(operationName) { mutableListOf() }
        synchronized(timings) {
            timings.add(durationMs)
            if (timings.size > maxSamplesPerOperation) {
                timings.removeAt(0)
            }
        }
        updateMetrics()
    }

    fun recordTaskExecution(success: Boolean, durationMs: Long) {
        updateMetrics { metrics ->
            if (success) {
                metrics.copy(
                    totalSuccessfulTasks = metrics.totalSuccessfulTasks + 1,
                    totalTaskDurationMs = metrics.totalTaskDurationMs + durationMs,
                    averageTaskDurationMs = (metrics.totalTaskDurationMs + durationMs) / (metrics.totalSuccessfulTasks + 1)
                )
            } else {
                metrics.copy(
                    totalFailedTasks = metrics.totalFailedTasks + 1,
                    totalTaskDurationMs = metrics.totalTaskDurationMs + durationMs
                )
            }
        }
    }

    fun recordCacheAccess(hit: Boolean) {
        updateMetrics { metrics ->
            if (hit) {
                metrics.copy(
                    cacheHits = metrics.cacheHits + 1,
                    cacheHitRate = (metrics.cacheHits + 1).toFloat() / (metrics.cacheHits + metrics.cacheMisses + 1)
                )
            } else {
                metrics.copy(
                    cacheMisses = metrics.cacheMisses + 1,
                    cacheHitRate = metrics.cacheHits.toFloat() / (metrics.cacheHits + metrics.cacheMisses + 1)
                )
            }
        }
    }

    fun recordAgentExecution(agentId: String, success: Boolean, durationMs: Long) {
        updateMetrics { metrics ->
            metrics.copy(
                totalAgentExecutions = metrics.totalAgentExecutions + 1,
                totalAgentDurationMs = metrics.totalAgentDurationMs + durationMs
            )
        }
    }

    private fun updateMetrics(update: (PerformanceMetrics) -> PerformanceMetrics) {
        _performanceMetrics.value = update(_performanceMetrics.value)
    }

    private fun updateMetrics() {
        val timings = operationTimings.mapValues { (_, list) ->
            synchronized(list) {
                if (list.isEmpty()) 0L else list.average().toLong()
            }
        }

        _performanceMetrics.value = _performanceMetrics.value.copy(
            operationAverages = timings
        )
    }

    fun reset() {
        operationTimings.clear()
        _performanceMetrics.value = PerformanceMetrics()
    }

    fun getMetrics(): PerformanceMetrics = _performanceMetrics.value

    data class PerformanceMetrics(
        var totalSuccessfulTasks: Int = 0,
        var totalFailedTasks: Int = 0,
        var totalTaskDurationMs: Long = 0,
        var averageTaskDurationMs: Long = 0,
        var cacheHits: Int = 0,
        var cacheMisses: Int = 0,
        var cacheHitRate: Float = 0f,
        var totalAgentExecutions: Int = 0,
        var totalAgentDurationMs: Long = 0,
        var operationAverages: Map<String, Long> = emptyMap()
    ) {
        val overallSuccessRate: Float
            get() = if (totalSuccessfulTasks + totalFailedTasks > 0) {
                totalSuccessfulTasks.toFloat() / (totalSuccessfulTasks + totalFailedTasks)
            } else 0f

        val averageAgentDurationMs: Long
            get() = if (totalAgentExecutions > 0) totalAgentDurationMs / totalAgentExecutions else 0L
    }
}
