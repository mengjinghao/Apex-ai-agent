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
        // Security (G-1/G-2/G-5, H-1/H-2/H-4): FileTool and NetworkTool now require
        // a Context so they can resolve allowlist base paths (filesDir/cacheDir/sdcard)
        // and perform canonical path validation.
        register(FileTool(context))
        register(NetworkTool(context))
        register(SystemTool(context))
        register(ProcessTool())
        register(CodeExecutionTool())
    }
}
