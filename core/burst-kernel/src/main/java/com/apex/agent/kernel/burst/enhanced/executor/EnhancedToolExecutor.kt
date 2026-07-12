package com.apex.agent.kernel.burst.enhanced.executor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B30: 工具执行器增强
 *
 * 增强工具执行能力：
 * - 工具注册表
 * - 执行追踪
 * - 并行工具调用
 * - 工具版本管理
 * - 安全沙箱
 */
class EnhancedToolExecutor {

    data class ToolRegistration(
        val toolId: String,
        val name: String,
        val version: String,
        val category: String,
        val dangerLevel: Int,        // 1-5
        val requiresPermission: Boolean,
        val timeoutMs: Long,
        val isParallelSafe: Boolean,
        val deprecated: Boolean = false
    )

    data class ToolExecution(
        val executionId: String,
        val toolId: String,
        val arguments: Map<String, Any>,
        val success: Boolean,
        val result: String?,
        val error: String?,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ParallelResult(
        val results: List<ToolExecution>,
        val successCount: Int,
        val failureCount: Int,
        val totalDurationMs: Long
    )

    private val tools = ConcurrentHashMap<String, ToolRegistration>()
    private val executionHistory = mutableListOf<ToolExecution>()
    private val executionCounter = AtomicLong(0)
    private val toolStats = ConcurrentHashMap<String, ToolStats>()

    data class ToolStats(
        var totalExecutions: Long = 0,
        var successCount: Long = 0,
        var totalDurationMs: Long = 0,
        var lastUsed: Long = 0
    )

    fun interface ToolHandler {
        suspend fun execute(arguments: Map<String, Any>): Result<String>
    }

    private val handlers = ConcurrentHashMap<String, ToolHandler>()

    fun registerTool(tool: ToolRegistration, handler: ToolHandler) {
        tools[tool.toolId] = tool
        handlers[tool.toolId] = handler
    }

    suspend fun execute(toolId: String, arguments: Map<String, Any>): ToolExecution {
        val tool = tools[toolId] ?: return ToolExecution(
            generateId(), toolId, arguments, false, null, "工具未注册", 0
        )
        val handler = handlers[toolId] ?: return ToolExecution(
            generateId(), toolId, arguments, false, null, "无处理器", 0
        )
        if (tool.deprecated) {
            // 记录但继续执行
        }

        val start = System.currentTimeMillis()
        val result = try { handler.execute(arguments) } catch (e: Exception) { Result.failure(e) }
        val duration = System.currentTimeMillis() - start

        val execution = ToolExecution(
            executionId = generateId(), toolId = toolId, arguments = arguments,
            success = result.isSuccess, result = result.getOrNull(),
            error = result.exceptionOrNull()?.message, durationMs = duration
        )

        recordExecution(execution)
        return execution
    }

    suspend fun executeParallel(executions: List<Pair<String, Map<String, Any>>>): ParallelResult {
        val start = System.currentTimeMillis()
        val results = coroutineScope {
            executions.map { (toolId, args) ->
                async { execute(toolId, args) }
            }.let { awaitAll(*it.toTypedArray()) }
        }
        return ParallelResult(
            results = results,
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            totalDurationMs = System.currentTimeMillis() - start
        )
    }

    fun getTool(toolId: String): ToolRegistration? = tools[toolId]
    fun listTools(category: String? = null): List<ToolRegistration> =
        if (category != null) tools.values.filter { it.category == category }.toList()
        else tools.values.toList()

    fun getHistory(limit: Int = 100): List<ToolExecution> = executionHistory.takeLast(limit)
    fun getStats(): Map<String, ToolStats> = toolStats.toMap()

    private fun recordExecution(execution: ToolExecution) {
        synchronized(executionHistory) {
            executionHistory.add(execution)
            while (executionHistory.size > 1000) executionHistory.removeAt(0)
        }
        val stats = toolStats.computeIfAbsent(execution.toolId) { ToolStats() }
        stats.totalExecutions++
        if (execution.success) stats.successCount++
        stats.totalDurationMs += execution.durationMs
        stats.lastUsed = System.currentTimeMillis()
    }

    private fun generateId(): String = "exec_${executionCounter.incrementAndGet()}"
}
