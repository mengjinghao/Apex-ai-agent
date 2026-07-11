package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.model.ToolPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TerminalToolCallHandler(private val context: Context) {
    private val toolExecutor by lazy {
        TerminalToolExecutor(context)
    }

    suspend fun handleToolCalls(toolCallsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val toolCalls = JSONArray(toolCallsJson)
            val results = mutableListOf<ToolCallResult>()

            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(i)
                val function = toolCall?.optJSONObject("function")
                val toolName = function?.optString("name")
                val arguments = function?.optString("arguments")
                val callId = toolCall?.optString("id")

                if (toolName != null && arguments != null && callId != null) {
                    val result = executeToolCall(toolName, arguments, callId)
                    results.add(result)
                }
            }

            buildToolResultsResponse(results)
        } catch (e: Exception) {
            "Error handling tool calls: ${e.message}"
        }
    }

    private suspend fun executeToolCall(
        toolName: String,
        arguments: String,
        callId: String
    ): ToolCallResult {
        val result = toolExecutor.executeToolFromJson(toolName, arguments)
        return ToolCallResult(
            callId = callId,
            toolName = toolName,
            success = result.success,
            result = result.result,
            error = result.error
        )
    }

    private fun buildToolResultsResponse(results: List<ToolCallResult>): String {
        val response = StringBuilder()
        
        results.forEach { result ->
            val resultTag = generateResultTag(result.toolName)
            response.append("<${resultTag}>")
            response.append("<name>${result.toolName}</name>")
            response.append("<content>")
            response.append(if (result.success) {
                result.result
            } else {
                "Error: ${result.error}"
            })
            response.append("</content>")
            response.append("</${resultTag}>")
            response.append("\n")
        }
        
        return response.toString()
    }

    private fun generateResultTag(toolName: String): String {
        val sanitizedName = toolName.replace("_", "").lowercase()
        return "toolresult_${sanitizedName}"
    }

    suspend fun buildToolDefinitions(): String? {
        return buildToolsJson(TerminalToolDefinition.terminalTools)
    }

    suspend fun processToolCallResponse(response: String): String {
        return convertToolCallPayloadToXml(response)
    }

    private fun buildToolsJson(toolPrompts: List<ToolPrompt>): String? {
        if (toolPrompts.isEmpty()) return null
        val tools = JSONArray()
        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
        }
        return if (tools.length() > 0) tools.toString() else null
    }

    private fun convertToolCallPayloadToXml(content: String): String {
        if (content.isBlank()) return content
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return content

        return try {
            val json = JSONObject(trimmed)
            val toolCalls = json.optJSONArray("tool_calls")
                ?: run {
                    val arr = JSONArray()
                    if (json.has("function")) arr.put(json)
                    arr
                }

            val xml = StringBuilder()
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(i) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name", "")
                if (name.isBlank()) continue

                xml.append("<tool_call name=\"$name\">")
                val args = function.optString("arguments", "{}")
                try {
                    val argsObj = JSONObject(args)
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        xml.append("\n<param name=\"$key\">${argsObj.optString(key)}</param>")
                    }
                } catch (e: Exception) {
                    xml.append("\n<param name=\"_raw\">$args</param>")
                }
                xml.append("\n</tool_call>\n")
            }
            xml.toString().trim().ifEmpty { content }
        } catch (e: Exception) {
            content
        }
    }
}

data class ToolCallResult(
    val callId: String,
    val toolName: String,
    val success: Boolean,
    val result: String,
    val error: String? = null
)