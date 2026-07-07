package com.ai.assistance.apex.engine.tools

import android.content.Context
import com.ai.assistance.apex.engine.model.ToolInfo
import com.ai.assistance.apex.engine.tools.impl.CodeExecutionTool
import com.ai.assistance.apex.engine.tools.impl.FileTool
import com.ai.assistance.apex.engine.tools.impl.NetworkTool
import com.ai.assistance.apex.engine.tools.impl.ProcessTool
import com.ai.assistance.apex.engine.tools.impl.SystemTool
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry(private val context: Context) {
    private val tools = ConcurrentHashMap<String, Tool>()

    init {
        registerBuiltInTools()
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): Tool? {
        return tools[name]
    }

    fun getAllTools(): MutableList<ToolInfo> {
        return tools.values.map { tool ->
            ToolInfo().apply {
                name = tool.name
                description = tool.description
                category = tool.category
                parameters = tool.parameters
                requiresRoot = tool.requiresRoot
            }
        }.toMutableList()
    }

    private fun registerBuiltInTools() {
        register(FileTool())
        register(NetworkTool())
        register(SystemTool())
        register(ProcessTool())
        register(CodeExecutionTool())
    }
}