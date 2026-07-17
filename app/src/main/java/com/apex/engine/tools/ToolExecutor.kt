package com.apex.engine.tools

import com.apex.core.model.ToolCall
import com.apex.core.model.ToolResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工具执行器 — 带熔断和权限检查。
 */
class ToolExecutor(private val registry: ToolRegistry) {

    private val failureCount = ConcurrentHashMap<String, AtomicInteger>()
    private val maxFailures = 5

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        if (isCircuitOpen(call.toolId)) {
            return@withContext ToolResult.Error("工具 ${call.name} 已被熔断（连续失败 ${maxFailures} 次）")
        }
        try {
            val result = registry.execute(call)
            if (result is ToolResult.Error) {
                recordFailure(call.toolId)
            } else {
                resetFailures(call.toolId)
            }
            result
        } catch (e: Exception) {
            recordFailure(call.toolId)
            ToolResult.Error(e.message ?: "工具执行异常")
        }
    }

    private fun isCircuitOpen(toolId: String): Boolean {
        return (failureCount[toolId]?.get() ?: 0) >= maxFailures
    }

    private fun recordFailure(toolId: String) {
        failureCount.computeIfAbsent(toolId) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun resetFailures(toolId: String) {
        failureCount[toolId]?.set(0)
    }
}
