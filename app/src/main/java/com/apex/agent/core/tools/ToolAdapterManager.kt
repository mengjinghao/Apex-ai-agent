package com.apex.core.tools

import com.apex.agent.core.tools.integration.IntegrationManager
import com.apex.agent.core.tools.integration.tool.IntegrationTool
import com.apex.core.tools.adapters.DatabaseToolAdapter
import com.apex.core.tools.adapters.ExternalApiToolAdapter
import com.apex.core.tools.adapters.QuickSearchToolAdapter

/**
 * 工具适配器管理器，用于管理所有的工具适配?* 增强版本：添加工具执行历史、状态跟踪、性能统计等功?*/
object ToolAdapterManager {

    // 工具执行历史
    private val executionHistory = mutableListOf<ToolExecutionRecord>()
    private val MAX_HISTORY_SIZE = 100
    
    // 工具使用统计
    private val usageStats = mutableMapOf<String, ToolUsageStat>()

    init {
        // 初始化时注册默认工具适配?       registerDefaultAdapters()
    }

    /**
     * 初始化工具适配器（需要在 Application 中调用，传入应用 Context?
     * @param context 应用 Context
     */
    fun initialize(context: android.content.Context) {
        IntegrationManager.initialize(context)
        ToolRegistry.register(IntegrationTool(context))
    }

    /**
     * 注册默认工具适配?    */
    private fun registerDefaultAdapters() {
        // 注册数据库工具适配?       ToolRegistry.register(DatabaseToolAdapter())
        // 注册外部API工具适配?       ToolRegistry.register(ExternalApiToolAdapter())
        // 注册快捷搜索工具适配?       ToolRegistry.register(QuickSearchToolAdapter())
    }

    /**
     * 注册工具适配?    */
    fun registerAdapter(adapter: ToolAdapter) {
        ToolRegistry.register(adapter)
    }

    /**
     * 执行工具调用（带统计和历史记录）
     */
    suspend fun executeTool(toolName: String, parameters: Map<String, Any>): ToolResultData {
        val startTime = System.currentTimeMillis()
        
        val tool = ToolRegistry.getTool(toolName)
        if (tool == null) {
            recordExecution(toolName, parameters, false, startTime, "工具不存?
            return StringResultData("错误：工具不存在，toolName")
        }

        if (!tool.isAvailable()) {
            recordExecution(toolName, parameters, false, startTime, "工具不可的）
            return StringResultData("错误：工具不可用了toolName")
        }

        return try {
            val result = tool.execute(parameters)
            recordExecution(toolName, parameters, true, startTime)
            updateUsageStats(toolName, true)
            result
        } catch (e: Exception) {
            recordExecution(toolName, parameters, false, startTime, e.message ?: "未知错误")
            updateUsageStats(toolName, false)
            StringResultData("工具执行失败，{e.message}")
        }
    }

    /**
     * 获取工具列表
     */
    fun getTools(): List<ToolAdapter> {
        return ToolRegistry.getAllTools()
    }

    /**
     * 获取可用工具列表
     */
    fun getAvailableTools(): List<ToolAdapter> {
        return ToolRegistry.getAvailableTools()
    }

    /**
     * 获取工具信息
     */
    fun getToolInfo(toolName: String): Map<String, Any>? {
        val tool = ToolRegistry.getTool(toolName)
        if (tool == null) {
            return null
        }

        val stat = usageStats[toolName]
        
        return mapOf(
            "name" to tool.getName(),
            "description" to tool.getDescription(),
            "parameters" to tool.getParameters(),
            "available" to tool.isAvailable(),
            "usage_count" to (stat?.totalCount ?: 0),
            "success_rate" to (stat?.successRate ?: 0.0)
        )
    }

    /**
     * 获取所有工具信?    */
    fun getAllToolInfo(): List<Map<String, Any>> {
        return ToolRegistry.getAllTools().map { tool ->
            val stat = usageStats[tool.getName()]
            mapOf(
                "name" to tool.getName(),
                "description" to tool.getDescription(),
                "parameters" to tool.getParameters(),
                "available" to tool.isAvailable(),
                "usage_count" to (stat?.totalCount ?: 0),
                "success_rate" to (stat?.successRate ?: 0.0)
            )
        }
    }

    /**
     * 获取工具执行历史
     */
    fun getExecutionHistory(limit: Int = 50): List<ToolExecutionRecord> {
        return executionHistory.takeLast(limit).reversed()
    }

    /**
     * 获取工具使用统计
     */
    fun getUsageStats(): Map<String, ToolUsageStat> {
        return usageStats.toMap()
    }

    /**
     * 清除执行历史
     */
    fun clearHistory() {
        executionHistory.clear()
    }

    /**
     * 清除所有缓?    */
    fun clearAllCache() {
        // 目前缓存主要在各个工具适配器内?       // 这里可以添加清除各个工具缓存的逻辑
    }

    private fun recordExecution(
        toolName: String,
        parameters: Map<String, Any>,
        success: Boolean,
        startTime: Long,
        error: String? = null
    ) {
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        val record = ToolExecutionRecord(
            toolName = toolName,
            parameters = parameters.toMap(),
            success = success,
            error = error,
            durationMs = duration,
            timestamp = startTime
        )
        
        executionHistory.add(record)
        
        // 限制历史记录大小
        if (executionHistory.size > MAX_HISTORY_SIZE) {
            executionHistory.removeAt(0)
        }
    }

    private fun updateUsageStats(toolName: String, success: Boolean) {
        val stat = usageStats.getOrPut(toolName) {
            ToolUsageStat(
                toolName = toolName,
                totalCount = 0,
                successCount = 0,
                totalDurationMs = 0
            )
        }
        
        stat.totalCount++
        if (success) {
            stat.successCount++
        }
    }

    /**
     * 工具执行记录
     */
    data class ToolExecutionRecord(
        val toolName: String,
        val parameters: Map<String, Any>,
        val success: Boolean,
        val error: String?,
        val durationMs: Long,
        val timestamp: Long
    )

    /**
     * 工具使用统计
     */
    data class ToolUsageStat(
        val toolName: String,
        var totalCount: Int,
        var successCount: Int,
        var totalDurationMs: Long
    ) {
        val successRate: Double
            get() = if (totalCount == 0) 0.0 else successCount.toDouble() / totalCount.toDouble()
        
        val avgDurationMs: Double
            get() = if (totalCount == 0) 0.0 else totalDurationMs.toDouble() / totalCount.toDouble()
    }
}
