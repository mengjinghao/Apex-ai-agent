package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.agent.data.burstmode.observability.ExecutionLogger
import com.apex.agent.data.burstmode.reasoning.logging.ReasoningHistoryLogger
import com.apex.agent.gepa.GepaLogger
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 智能日志查询管理。
 * 
 * 功能。
 * 1. 自动识别和选择要查询的日志类型
 * 2. 支持多种日志源的统一查询接口
 * 3. 智能过滤和搜。
 * 4. 狂暴模式增强支持（大文件流式处理。
 */
class SmartLogQueryManager(private val context: Context) {

    private val executionLogger = ExecutionLogger()
    private val reasoningHistoryLogger = ReasoningHistoryLogger()
    
    companion object {
        private const val TAG = "SmartLogQuery"
        
        // 日志类型枚举
        enum class LogType {
            SYSTEM_LOGCAT,          // Android 系统日志
            APP_LOGGER,             // 应用日志 (AppLogger)
            GEPA_LOGS,              // GEPA 系统日志
            EXECUTION_LOGS,         // 狂暴模式执行日志
            REASONING_HISTORY,      // 推理历史日志
            WORKFLOW_LOGS,          // 工作流日志
            BUILD_LOGS,             // 构建日志
            TERMINAL_AGENT_LOGS,    // Terminal Agent 日志
            AUTO_DETECT             // 自动检测
        }
        
        // 日志级别
        enum class LogLevel {
            VERBOSE, DEBUG, INFO, WARNING, ERROR, FATAL
        }
    }
    
    /**
     * 日志查询结果
     */
    data class LogQueryResult(
        val success: Boolean,
        val logType: LogType,
        val entries: List<LogEntry>,
        val totalCount: Int,
        val filteredCount: Int,
        val queryTime: Long,
        val message: String = "",
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * 日志条目
     */
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String?,
        val message: String,
        val source: String,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * 查询过滤。
     */
    data class LogFilter(
        val logType: LogType = LogType.AUTO_DETECT,
        val level: LogLevel? = null,
        val tag: String? = null,
        val keyword: String? = null,
        val startTime: Long? = null,
        val endTime: Long? = null,
        val maxResults: Int = 100,
        val taskId: String? = null,
        val agentId: String? = null,
        val workflowId: String? = null
    )
    
    /**
     * 智能查询日志 - 主入口
     * 自动识别最合适的日志源并返回结果
     */
    suspend fun queryLogs(filter: LogFilter = LogFilter()): LogQueryResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 自动检测日志类型
    val detectedType = if (filter.logType == LogType.AUTO_DETECT) {
                detectBestLogSource(filter)
            } else {
                filter.logType
            }
            
            AppLogger.i(TAG, "Querying logs: type=${detectedType}, keyword=${filter.keyword}")
            
            // 根据类型执行查询
    val result = when (detectedType) {
                LogType.SYSTEM_LOGCAT -> querySystemLogcat(filter)
                LogType.APP_LOGGER -> queryAppLogger(filter)
                LogType.GEPA_LOGS -> queryGepaLogs(filter)
                LogType.EXECUTION_LOGS -> queryExecutionLogs(filter)
                LogType.REASONING_HISTORY -> queryReasoningHistory(filter)
                LogType.WORKFLOW_LOGS -> queryWorkflowLogs(filter)
                LogType.BUILD_LOGS -> queryBuildLogs(filter)
                LogType.TERMINAL_AGENT_LOGS -> queryTerminalAgentLogs(filter)
                LogType.AUTO_DETECT -> querySystemLogcat(filter) // fallback
            }
            
            val queryTime = System.currentTimeMillis() - startTime
            
            result.copy(queryTime = queryTime)
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Log query failed", e)
            LogQueryResult(
                success = false,
                logType = filter.logType,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = System.currentTimeMillis() - startTime,
                message = "查询失败: ${e.message}"
            )
        }
    }
    
    /**
     * 自动检测最佳日志源
     */
    private fun detectBestLogSource(filter: LogFilter): LogType {
        // 日志类型枚举
    val keyword = filter.keyword?.lowercase() ?: ""
        val tag = filter.tag?.lowercase() ?: ""
        
        return when {
            // GEPA 相关
            keyword.contains("gepa") || keyword.contains("skill") || 
            keyword.contains("agent") || tag.contains("gepa") -> LogType.GEPA_LOGS
            
            // 狂暴模式相关
            keyword.contains("burst") || keyword.contains("execution") || 
            keyword.contains("task") || filter.taskId != null -> LogType.EXECUTION_LOGS
            
            // 推理相关
            keyword.contains("reason") || keyword.contains("think") || 
            keyword.contains("strategy") -> LogType.REASONING_HISTORY
            
            // 自动检测日志类型
            keyword.contains("workflow") || keyword.contains("node") || 
            filter.workflowId != null -> LogType.WORKFLOW_LOGS
            
            // 构建相关
            keyword.contains("build") || keyword.contains("compile") || 
            keyword.contains("gradle") -> LogType.BUILD_LOGS
            
            // Terminal Agent 相关
            keyword.contains("terminal") || keyword.contains("command") || 
            keyword.contains("shell") -> LogType.TERMINAL_AGENT_LOGS
            
            // 默认使用应用日志
            else -> LogType.APP_LOGGER
        }
    }
    
    /**
     * 查询系统 Logcat
     */
    private suspend fun querySystemLogcat(filter: LogFilter): LogQueryResult {
        return try {
            // 构建 logcat 命令
    val command = buildLogcatCommand(filter)
            
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                return LogQueryResult(
                    success = false,
                    logType = LogType.SYSTEM_LOGCAT,
                    entries = emptyList(),
                    totalCount = 0,
                    filteredCount = 0,
                    queryTime = 0,
                    message = "logcat 命令执行失败: exit code ${exitCode}"
                )
            }
            
            // 解析输出
    val entries = parseLogcatOutput(output, filter)
            
            LogQueryResult(
                success = true,
                logType = LogType.SYSTEM_LOGCAT,
                entries = entries.take(filter.maxResults),
                totalCount = entries.size,
                filteredCount = entries.size,
                queryTime = 0,
                message = "成功获取 ${entries.size} 条系统日志"
            )
            
        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.SYSTEM_LOGCAT,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询系统日志失败: ${e.message}"
            )
        }
    }
    
    /**
     * 构建 logcat 命令
     */
    private fun buildLogcatCommand(filter: LogFilter): Array<String> {
        val cmd = mutableListOf("logcat", "-d")
        
        // 添加行数限制
        cmd.add("-t")
        cmd.add("${filter.maxResults * 2}") // 多取一些，后面再过滤
        
        // 添加标签过滤
    if (filter.tag != null) {
            cmd.add("-s")
            cmd.add("${filter.tag}:*")
        }
        
        // 添加级别过滤
    if (filter.level != null) {
            val levelStr = when (filter.level) {
                LogLevel.VERBOSE -> "V"
                LogLevel.DEBUG -> "D"
                LogLevel.INFO -> "I"
                LogLevel.WARNING -> "W"
                LogLevel.ERROR -> "E"
                LogLevel.FATAL -> "F"
            }
            if (filter.tag == null) {
                cmd.add("*:${levelStr}")
            }
        }
        
        return cmd.toTypedArray()
    }
    
    /**
     * 解析 logcat 输出
     */
    private fun parseLogcatOutput(output: String, filter: LogFilter): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        val lines = output.lines()
        
        // logcat 格式: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG : Message
    val logPattern = Regex("(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s+(.*)")
        
        for (line in lines) {
            val match = logPattern.find(line)
            if (match != null) {
                val (timestamp, pid, tid, level, tag, message) = match.destructured
                
                // 应用关键词过滤
    if (filter.keyword != null && !message.contains(filter.keyword, ignoreCase = true)) {
                    continue
                }
                
                val logLevel = when (level) {
                    "V" -> LogLevel.VERBOSE
                    "D" -> LogLevel.DEBUG
                    "I" -> LogLevel.INFO
                    "W" -> LogLevel.WARNING
                    "E" -> LogLevel.ERROR
                    "F" -> LogLevel.FATAL
                    else -> LogLevel.INFO
                }
                
                entries.add(LogEntry(
                    timestamp = System.currentTimeMillis(), // logcat 时间需要特殊解析
                    level = logLevel,
                    tag = tag.trim(),
                    message = message.trim(),
                    source = "logcat",
                    metadata = mapOf("pid" to pid, "tid" to tid)
                ))
            }
        }
        
        return entries
    }
    
    /**
     * 查询 AppLogger 日志
     */
    private suspend fun queryAppLogger(filter: LogFilter): LogQueryResult {
        return try {
            val logFile = AppLogger.getLogFile()
            if (logFile == null || !logFile.exists()) {
                return LogQueryResult(
                    success = false,
                    logType = LogType.APP_LOGGER,
                    entries = emptyList(),
                    totalCount = 0,
                    filteredCount = 0,
                    queryTime = 0,
                    message = "日志文件不存在"
                )
            }
            
            // 读取日志文件
    val lines = logFile.readLines()
            val entries = parseAppLoggerLines(lines, filter)
            
            LogQueryResult(
                success = true,
                logType = LogType.APP_LOGGER,
                entries = entries.take(filter.maxResults),
                totalCount = lines.size,
                filteredCount = entries.size,
                queryTime = 0,
                message = "成功获取 ${entries.size} 条应用日志（共 ${lines.size} 条）"
            )
            
        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.APP_LOGGER,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询应用日志失败: ${e.message}"
            )
        }
    }
    
    /**
     * 查询 AppLogger 日志
     */
    private fun parseAppLoggerLines(lines: List<String>, filter: LogFilter): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        
        // AppLogger 格式: yyyy-MM-dd HH:mm:ss.SSS L/TAG: message
    val logPattern = Regex("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEAF])/([^:]+):\\s+(.*)")
        
        for (line in lines) {
            val match = logPattern.find(line)
            if (match != null) {
                val (timestampStr, level, tag, message) = match.destructured
                
                // 应用过滤条件
    if (filter.tag != null && !tag.contains(filter.tag, ignoreCase = true)) continue
                if (filter.keyword != null && !message.contains(filter.keyword, ignoreCase = true)) continue
                
                val logLevel = when (level) {
                    "V" -> LogLevel.VERBOSE
                    "D" -> LogLevel.DEBUG
                    "I" -> LogLevel.INFO
                    "W" -> LogLevel.WARNING
                    "E" -> LogLevel.ERROR
                    "A" -> LogLevel.FATAL
                    else -> LogLevel.INFO
                }
                
                // 级别过滤
    if (filter.level != null && logLevel != filter.level) continue
                
                val timestamp = try {
                    dateFormat.parse(timestampStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }
                
                entries.add(LogEntry(
                    timestamp = timestamp,
                    level = logLevel,
                    tag = tag.trim(),
                    message = message.trim(),
                    source = "AppLogger"
                ))
            }
        }
        
        return entries
    }
    
    /**
     * 查询 GEPA 日志
     */
    private suspend fun queryGepaLogs(filter: LogFilter): LogQueryResult {
        return try {
            val gepaLogs = if (filter.tag != null) {
                GepaLogger.getLogsByTag(filter.tag, filter.maxResults)
            } else if (filter.taskId != null) {
                GepaLogger.getLogsByTaskId(filter.taskId, filter.maxResults)
            } else {
                GepaLogger.getLogs(filter.maxResults, null)
            }
            
            val entries = gepaLogs.map { log ->
                val level = when (log.level) {
                    GepaLogger.LogLevel.VERBOSE -> LogLevel.VERBOSE
                    GepaLogger.LogLevel.DEBUG -> LogLevel.DEBUG
                    GepaLogger.LogLevel.INFO -> LogLevel.INFO
                    GepaLogger.LogLevel.WARNING -> LogLevel.WARNING
                    GepaLogger.LogLevel.ERROR -> LogLevel.ERROR
                }
                
                LogEntry(
                    timestamp = log.timestamp,
                    level = level,
                    tag = log.tag,
                    message = log.message,
                    source = "GEPA",
                    metadata = log.context ?: emptyMap()
                )
            }.filter { entry ->
                // 应用关键词过滤
                filter.keyword == null || entry.message.contains(filter.keyword, ignoreCase = true)
            }
            
            LogQueryResult(
                success = true,
                logType = LogType.GEPA_LOGS,
                entries = entries,
                totalCount = entries.size,
                filteredCount = entries.size,
                queryTime = 0,
                message = "成功获取 ${entries.size} 条 GEPA 日志"
            )
            
        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.GEPA_LOGS,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询 GEPA 日志失败: ${e.message}"
            )
        }
    }
    
    /**
     * 查询执行日志（狂暴模式）
     */
    private suspend fun queryExecutionLogs(filter: LogFilter): LogQueryResult {
        return try {
            val executionLogs = executionLogger.query(
                taskId = filter.taskId,
                limit = filter.maxResults
            )

            val entries = executionLogs.map { log ->
                LogEntry(
                    timestamp = log.timestamp,
                    level = if (log.status == "error") LogLevel.ERROR else LogLevel.INFO,
                    tag = log.skillId,
                    message = "[${log.taskId}] ${log.message}",
                    source = "ExecutionLogger"
                )
            }

            val filtered = if (filter.keyword != null) {
                entries.filter { it.message.contains(filter.keyword, ignoreCase = true) }
            } else entries

            LogQueryResult(
                success = true,
                logType = LogType.EXECUTION_LOGS,
                entries = filtered.take(filter.maxResults),
                totalCount = entries.size,
                filteredCount = filtered.size,
                queryTime = 0,
                message = "成功获取 ${filtered.size} 条执行日志"
            )

        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.EXECUTION_LOGS,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询执行日志失败: ${e.message}"
            )
        }
    }

    /**
     * 查询推理历史日志
     */
    private suspend fun queryReasoningHistory(filter: LogFilter): LogQueryResult {
        return try {
            val historyLogs = reasoningHistoryLogger.query(
                taskId = filter.taskId,
                limit = filter.maxResults
            )

            val entries = historyLogs.map { entry ->
                LogEntry(
                    timestamp = entry.timestamp,
                    level = LogLevel.DEBUG,
                    tag = "Reasoning",
                    message = "[${entry.taskId}] ${entry.reasoningPath}",
                    source = "ReasoningHistoryLogger"
                )
            }

            val filtered = if (filter.keyword != null) {
                entries.filter { it.message.contains(filter.keyword, ignoreCase = true) }
            } else entries

            LogQueryResult(
                success = true,
                logType = LogType.REASONING_HISTORY,
                entries = filtered.take(filter.maxResults),
                totalCount = entries.size,
                filteredCount = filtered.size,
                queryTime = 0,
                message = "成功获取 ${filtered.size} 条推理历史日志"
            )

        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.REASONING_HISTORY,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询推理历史失败: ${e.message}"
            )
        }
    }
    
    /**
     * 查询工作流日志
     *
     * 工作流日志存放在 `<filesDir>/workflow_logs/<workflowId>.log` 下，
     * 每行格式：`<ISO8601 timestamp> [LEVEL] [workflowId] [nodeId] message`。
     *
     * 当 [LogFilter.workflowId] 为空时，扫描 workflow_logs/ 下所有 .log 文件，
     * 合并后再按时间排序。
     */
    private suspend fun queryWorkflowLogs(filter: LogFilter): LogQueryResult {
        val queryStart = System.currentTimeMillis()
        return try {
            val logsDir = File(context.filesDir, "workflow_logs")
            if (!logsDir.exists() || !logsDir.isDirectory) {
                return LogQueryResult(
                    success = true,
                    logType = LogType.WORKFLOW_LOGS,
                    entries = emptyList(),
                    totalCount = 0,
                    filteredCount = 0,
                    queryTime = System.currentTimeMillis() - queryStart,
                    message = "Workflow logs directory does not exist yet"
                )
            }

            val targetFiles = if (!filter.workflowId.isNullOrBlank()) {
                listOf(File(logsDir, "${filter.workflowId}.log")).filter { it.exists() }
            } else {
                logsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                    ?.toList() ?: emptyList()
            }

            if (targetFiles.isEmpty()) {
                return LogQueryResult(
                    success = true,
                    logType = LogType.WORKFLOW_LOGS,
                    entries = emptyList(),
                    totalCount = 0,
                    filteredCount = 0,
                    queryTime = System.currentTimeMillis() - queryStart,
                    message = "No workflow log files found"
                )
            }

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
            val altFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val lineRegex = Regex(
                """(\S+)\s+\[(\w+)\]\s+\[([^\]]+)\](?:\s+\[([^\]]+)\])?\s+(.*)"""
            )

            val rawEntries = mutableListOf<LogEntry>()
            var totalCount = 0

            targetFiles.forEach { file ->
                file.useLines { lines ->
                    lines.forEach { line ->
                        totalCount++
                        val m = lineRegex.find(line) ?: return@forEach
                        val (tsStr, levelStr, wfId, nodeId, msg) = m.destructured
                        val ts = parseTimestamp(tsStr, isoFormat, altFormat)

                        // workflowId 过滤（双保险：file 名 + 行内）
    if (!filter.workflowId.isNullOrBlank() && filter.workflowId != wfId) return@forEach

                        // 时间窗口过滤
    if (filter.startTime != null && ts < filter.startTime) return@forEach
                        if (filter.endTime != null && ts > filter.endTime) return@forEach

                        val level = parseLogLevel(levelStr) ?: LogLevel.INFO

                        // 级别过滤
    if (filter.level != null && level != filter.level) return@forEach

                        // tag 过滤：nodeId 视作 tag
    if (filter.tag != null && !nodeId.contains(filter.tag, ignoreCase = true)) return@forEach

                        // 关键词过滤
    if (filter.keyword != null && !msg.contains(filter.keyword, ignoreCase = true)) return@forEach

                        rawEntries.add(
                            LogEntry(
                                timestamp = ts,
                                level = level,
                                tag = nodeId.ifBlank { null },
                                message = msg.trim(),
                                source = "workflow:$wfId",
                                metadata = mapOf(
                                    "workflowId" to wfId,
                                    "nodeId" to nodeId,
                                    "file" to file.name
                                )
                            )
                        )
                    }
                }
            }

            // 排序 + 截断
    val sorted = rawEntries.sortedBy { it.timestamp }
            val limited = if (filter.maxResults > 0) sorted.takeLast(filter.maxResults) else sorted

            LogQueryResult(
                success = true,
                logType = LogType.WORKFLOW_LOGS,
                entries = limited,
                totalCount = totalCount,
                filteredCount = limited.size,
                queryTime = System.currentTimeMillis() - queryStart,
                message = "Queried ${targetFiles.size} workflow log file(s); ${limited.size} entries"
            )
        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.WORKFLOW_LOGS,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = System.currentTimeMillis() - queryStart,
                message = "查询工作流日志失败: ${e.message}"
            )
        }
    }

    private fun parseTimestamp(
        tsStr: String,
        vararg formats: SimpleDateFormat
    ): Long {
        for (fmt in formats) {
            try {
                return fmt.parse(tsStr)?.time ?: continue
            } catch (_: Exception) {
                // try next format
            }
        }
        return System.currentTimeMillis()
    }

    private fun parseLogLevel(s: String): LogLevel? = when (s.uppercase()) {
        "V", "VERBOSE" -> LogLevel.VERBOSE
        "D", "DEBUG" -> LogLevel.DEBUG
        "I", "INFO" -> LogLevel.INFO
        "W", "WARN", "WARNING" -> LogLevel.WARNING
        "E", "ERROR" -> LogLevel.ERROR
        "F", "FATAL" -> LogLevel.FATAL
        else -> null
    }
    
    /**
     * 查询构建日志
     */
    private suspend fun queryBuildLogs(filter: LogFilter): LogQueryResult {
        return try {
            // 查找构建日志文件
    val buildLogFiles = listOf(
                File("build_error.log"),
                File("build_log.txt"),
                File("ai_terminal_build.log"),
                File("full_build.log")
            )
            
            val allEntries = mutableListOf<LogEntry>()
            
            for (logFile in buildLogFiles) {
                if (logFile.exists()) {
                    val lines = logFile.readLines()
                    
                    // 简单解析构建日志（通常是纯文本）
                    lines.forEachIndexed { index, line ->
                        if (filter.keyword == null || line.contains(filter.keyword, ignoreCase = true)) {
                            allEntries.add(LogEntry(
                                timestamp = System.currentTimeMillis(),
                                level = if (line.contains("error", ignoreCase = true)) LogLevel.ERROR else LogLevel.INFO,
                                tag = logFile.name,
                                message = line,
                                source = "build_log",
                                metadata = mapOf("line_number" to index + 1, "file" to logFile.name)
                            ))
                        }
                    }
                }
            }
            
            LogQueryResult(
                success = true,
                logType = LogType.BUILD_LOGS,
                entries = allEntries.take(filter.maxResults),
                totalCount = allEntries.size,
                filteredCount = allEntries.size,
                queryTime = 0,
                message = "成功获取 ${allEntries.size} 条构建日志"
            )
            
        } catch (e: Exception) {
            LogQueryResult(
                success = false,
                logType = LogType.BUILD_LOGS,
                entries = emptyList(),
                totalCount = 0,
                filteredCount = 0,
                queryTime = 0,
                message = "查询构建日志失败: ${e.message}"
            )
        }
    }
    
    /**
     * 查询 Terminal Agent 日志
     */
    private suspend fun queryTerminalAgentLogs(filter: LogFilter): LogQueryResult {
        // Terminal Agent 日志主要通过 logcat 获取
    val enhancedFilter = filter.copy(tag = filter.tag ?: "TerminalAgent")
        return querySystemLogcat(enhancedFilter)
    }
    
    /**
     * 导出日志到文。
     */
    suspend fun exportLogsToFile(
        filter: LogFilter = LogFilter(),
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = queryLogs(filter)
            
            if (!result.success) {
                return@withContext false
            }
            
            // 写入文件
            outputFile.bufferedWriter().use { writer ->
                writer.write("=== 日志导出报告 ===\n")
                writer.write("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("日志类型: ${result.logType}\n")
                writer.write("总条数: ${result.totalCount}\n")
                writer.write("过滤后: ${result.filteredCount}\n")
                writer.write("查询耗时: ${result.queryTime}ms\n")
                writer.write("\n==================\n\n")
                
                result.entries.forEach { entry ->
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        .format(Date(entry.timestamp))
                    val level = entry.level.name[0]
                    val tag = entry.tag ?: "Unknown"
                    
                    writer.write("[${timestamp}] ${level}/${tag}: ${entry.message}\n")
                    
                    if (entry.metadata.isNotEmpty()) {
                        entry.metadata.forEach { (key, value) ->
                            writer.write("  ${key}: ${value}\n")
                        }
                    }
                    writer.write("\n")
                }
            }
            
            true
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "Export logs failed", e)
            false
        }
    }
    
    /**
     * 获取日志统计信息
     */
    suspend fun getLogStatistics(): Map<String, Any> = withContext(Dispatchers.IO) {
        val stats = mutableMapOf<String, Any>()
        
        // AppLogger 统计
    val appLogFile = AppLogger.getLogFile()
        if (appLogFile != null && appLogFile.exists()) {
            stats["app_logger_file_size"] = appLogFile.length()
            stats["app_logger_lines"] = appLogFile.readLines().size
        }
        
        // GEPA 日志统计
        stats["gepa_log_count"] = GepaLogger.getLogs(Int.MAX_VALUE).size
        
        // 构建日志统计
    val buildLogFiles = listOf(
            File("build_error.log"),
            File("build_log.txt"),
            File("ai_terminal_build.log")
        )
        
        val buildStats = buildLogFiles.filter { it.exists() }.map { file ->
            mapOf(
                "file" to file.name,
                "size" to file.length(),
                "lines" to file.readLines().size
            )
        }
        stats["build_logs"] = buildStats
        
        stats
    }
}
