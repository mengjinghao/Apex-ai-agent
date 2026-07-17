package com.apex.engine.tools

import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCall
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolResult
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具注册中心 — 管理所有可用工具。
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, ApexTool>()

    fun register(tool: ApexTool) {
        tools[tool.metadata.id] = tool
    }

    fun unregister(id: String) {
        tools.remove(id)
    }

    fun list(): List<ApexTool> = tools.values.toList()

    fun listMetadata(): List<ToolMetadata> = tools.values.map { it.metadata }

    fun get(id: String): ApexTool? = tools[id]

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.toolId] ?: return ToolResult.Error("工具 ${call.toolId} 未注册")
        val args = parseArguments(call.arguments)
        return tool.execute(args)
    }

    private fun parseArguments(json: String): Map<String, Any> {
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, Any>()
            for (key in obj.keys()) {
                map[key] = obj.get(key)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
