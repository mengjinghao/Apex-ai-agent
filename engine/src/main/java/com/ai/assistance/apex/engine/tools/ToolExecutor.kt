package com.ai.assistance.apex.engine.tools

import android.content.Context
import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.model.ToolInfo

class ToolExecutor(private val context: Context) {
    private val toolRegistry = ToolRegistry(context)

    fun execute(toolName: String, args: String): ExecutionResult {
        val tool = toolRegistry.getTool(toolName)
        return tool?.execute(args) ?: ExecutionResult().apply {
            exitCode = -1
            error = "Tool not found: $toolName"
            success = false
        }
    }

    fun getAvailableTools(): MutableList<ToolInfo> {
        return toolRegistry.getAllTools()
    }

    fun registerTool(tool: Tool) {
        toolRegistry.register(tool)
    }
}