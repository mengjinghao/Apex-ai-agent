package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.data.model.StringResultData

/**
 * 日志查询工具执行�? */
object LogQueryToolExecutor {
    
    /**
     * 智能查询日志
     */
    suspend fun smartQueryLogs(context: Context, tool: AITool): ToolResult {
        return try {
            val logQueryManager = SmartLogQueryManager(context)
            
            // 解析参数
            val keyword = tool.parameters.find { it.name == "keyword" }?.value
            val logTypeStr = tool.parameters.find { it.name == "log_type" }?.value ?: "auto"
            val levelStr = tool.parameters.find { it.name == "level" }?.value
            val tag = tool.parameters.find { it.name == "tag" }?.value
            val maxResults = tool.parameters.find { it.name == "max_results" }?.value?.toIntOrNull() ?: 100
            val taskId = tool.parameters.find { it.name == "task_id" }?.value
            val agentId = tool.parameters.find { it.name == "agent_id" }?.value
            
            // 映射日志类型
            val logType = when (logTypeStr.lowercase()) {
                "system", "logcat" -> SmartLogQueryManager.LogType.SYSTEM_LOGCAT
                "app", "applogger" -> SmartLogQueryManager.LogType.APP_LOGGER
                "gepa" -> SmartLogQueryManager.LogType.GEPA_LOGS
                "execution", "burst" -> SmartLogQueryManager.LogType.EXECUTION_LOGS
                "reasoning" -> SmartLogQueryManager.LogType.REASONING_HISTORY
                "workflow" -> SmartLogQueryManager.LogType.WORKFLOW_LOGS
                "build" -> SmartLogQueryManager.LogType.BUILD_LOGS
                "terminal" -> SmartLogQueryManager.LogType.TERMINAL_AGENT_LOGS
                else -> SmartLogQueryManager.LogType.AUTO_DETECT
            }
            
            // 映射日志级别
            val level = when (levelStr?.uppercase()) {
                "VERBOSE" -> SmartLogQueryManager.LogLevel.VERBOSE
                "DEBUG" -> SmartLogQueryManager.LogLevel.DEBUG
                "INFO" -> SmartLogQueryManager.LogLevel.INFO
                "WARNING", "WARN" -> SmartLogQueryManager.LogLevel.WARNING
                "ERROR" -> SmartLogQueryManager.LogLevel.ERROR
                "FATAL" -> SmartLogQueryManager.LogLevel.FATAL
                else -> null
            }
            
            // 构建过滤�?            val filter = SmartLogQueryManager.LogFilter(
                logType = logType,
                level = level,
                tag = tag,
                keyword = keyword,
                maxResults = maxResults,
                taskId = taskId,
                agentId = agentId
            )
            
            // 执行查询
            val result = logQueryManager.queryLogs(filter)
            
            if (result.success) {
                // 格式化输�?                val output = buildString {
                    appendLine("=== 日志查询结果 ===")
                    appendLine("日志类型: ${result.logType}")
                    appendLine("总条�?${result.totalCount}")
                    appendLine("返回条数: ${result.filteredCount}")
                    appendLine("查询耗时: ${result.queryTime}ms")
                    if (result.message.isNotEmpty()) {
                        appendLine("消息: ${result.message}")
                    }
                    appendLine()
                    appendLine("--- 日志内容 ---")
                    
                    result.entries.forEach { entry ->
                        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
                            .format(java.util.Date(entry.timestamp))
                        val levelChar = entry.level.name[0]
                        val tagStr = entry.tag ?: "Unknown"
                        
                        appendLine("[${timestamp}] ${levelChar}/${tagStr}: ${entry.message}")
                        
                        if (entry.metadata.isNotEmpty()) {
                            entry.metadata.forEach { (key, value) ->
                                appendLine("  ${key}: ${value}")
                            }
                        }
                        appendLine()
                    }
                    
                    appendLine("==================")
                }
                
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(output)
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(result.message),
                    error = result.message
                )
            }
            
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "日志查询失败: ${e.message}"
            )
        }
    }
    
    /**
     * 导出日志到文�?     */
    suspend fun exportLogsToFile(context: Context, tool: AITool): ToolResult {
        return try {
            val logQueryManager = SmartLogQueryManager(context)
            
            val outputPath = tool.parameters.find { it.name == "output_path" }?.value ?: "/sdcard/Download/logistra/exported_logs.txt"
            val keyword = tool.parameters.find { it.name == "keyword" }?.value
            val logTypeStr = tool.parameters.find { it.name == "log_type" }?.value ?: "auto"
            val maxResults = tool.parameters.find { it.name == "max_results" }?.value?.toIntOrNull() ?: 1000
            
            val logType = when (logTypeStr.lowercase()) {
                "system", "logcat" -> SmartLogQueryManager.LogType.SYSTEM_LOGCAT
                "app", "applogger" -> SmartLogQueryManager.LogType.APP_LOGGER
                "gepa" -> SmartLogQueryManager.LogType.GEPA_LOGS
                "execution", "burst" -> SmartLogQueryManager.LogType.EXECUTION_LOGS
                "reasoning" -> SmartLogQueryManager.LogType.REASONING_HISTORY
                "workflow" -> SmartLogQueryManager.LogType.WORKFLOW_LOGS
                "build" -> SmartLogQueryManager.LogType.BUILD_LOGS
                "terminal" -> SmartLogQueryManager.LogType.TERMINAL_AGENT_LOGS
                else -> SmartLogQueryManager.LogType.AUTO_DETECT
            }
            
            val filter = SmartLogQueryManager.LogFilter(
                logType = logType,
                keyword = keyword,
                maxResults = maxResults
            )
            
            val outputFile = java.io.File(outputPath)
            
            // 确保目录存在
            outputFile.parentFile?.mkdirs()
            
            val success = logQueryManager.exportLogsToFile(filter, outputFile)
            
            if (success) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData("日志已导出到: ${outputPath}\n文件大小: ${outputFile.length()} bytes")
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "导出失败"
                )
            }
            
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "导出日志失败: ${e.message}"
            )
        }
    }
    
    /**
     * 获取日志统计信息
     */
    suspend fun getLogStatistics(context: Context, tool: AITool): ToolResult {
        return try {
            val logQueryManager = SmartLogQueryManager(context)
            
            val stats = logQueryManager.getLogStatistics()
            
            val output = buildString {
                appendLine("=== 日志统计信息 ===")
                appendLine()
                
                if (stats.containsKey("app_logger_file_size")) {
                    appendLine("AppLogger 日志:")
                    appendLine("  文件大小: ${formatFileSize(stats["app_logger_file_size"] as Long)}")
                    appendLine("  行数: ${stats["app_logger_lines"]}")
                    appendLine()
                }
                
                if (stats.containsKey("gepa_log_count")) {
                    appendLine("GEPA 日志:")
                    appendLine("  条目�?${stats["gepa_log_count"]}")
                    appendLine()
                }
                
                if (stats.containsKey("build_logs")) {
                    appendLine("构建日志:")
                    @Suppress("UNCHECKED_CAST")
                    val buildLogs = stats["build_logs"] as List<Map<String, Any>>
                    buildLogs.forEach { logInfo ->
                        appendLine("  ${logInfo["file"]}:")
                        appendLine("    大小: ${formatFileSize(logInfo["size"] as Long)}")
                        appendLine("    行数: ${logInfo["lines"]}")
                    }
                    appendLine()
                }
                
                appendLine("==================")
            }
            
            ToolResult(
                toolName = tool.name,
                success = true,
                result = StringResultData(output)
            )
            
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取统计信息失败: ${e.message}"
            )
        }
    }
    
    /**
     * 格式化文件大�?     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "${size} B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
