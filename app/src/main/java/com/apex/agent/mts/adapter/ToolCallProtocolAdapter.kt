package com.apex.agent.mts.adapter

import com.apex.agent.mts.schema.*
import com.apex.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Protocol { NATIVE_OPENAI, NATIVE_ANTHROPIC, XML, TEXT, AUTO }

private const val TAG = "ToolCallProtocolAdapter"

data class ToolDefinitions(
    val protocol: Protocol,
    val definitions: Any,
    val rawPrompt: String? = null
)

data class ProtocolConfig(
    val protocol: Protocol = Protocol.AUTO,
    val maxToolDescriptionLen: Int = 512,
    val includeDeprecated: Boolean = false,
    val includeExperimental: Boolean = false
)

class ToolCallProtocolAdapter {
    fun buildDefinitions(
        tools: List<ToolSpec>,
        config: ProtocolConfig = ProtocolConfig()
    ): ToolDefinitions {
        val filtered = tools.filter { t ->
            (config.includeDeprecated || t.metadata.deprecated.not()) &&
                (config.includeExperimental || t.metadata.experimental.not())
        }
        val protocol = resolveProtocol(config.protocol)
        return when (protocol) {
            Protocol.NATIVE_OPENAI -> buildOpenAIDefinitions(filtered, config)
            Protocol.NATIVE_ANTHROPIC -> buildAnthropicDefinitions(filtered, config)
            Protocol.XML -> buildXmlPrompt(filtered, config)
            Protocol.TEXT -> buildTextPrompt(filtered, config)
            Protocol.AUTO -> buildOpenAIDefinitions(filtered, config)
        }
    }

    fun parseToolCalls(
        response: String,
        registry: (String) -> ToolSpec?,
        protocol: Protocol = Protocol.AUTO
    ): List<ParsedToolCall> {
        return when (val resolved = resolveProtocol(protocol)) {
            Protocol.NATIVE_OPENAI -> parseOpenAIToolCalls(response, registry)
            Protocol.NATIVE_ANTHROPIC -> parseAnthropicToolCalls(response, registry)
            Protocol.XML -> parseXmlToolCalls(response, registry)
            Protocol.TEXT -> parseTextToolCalls(response, registry)
            Protocol.AUTO -> autoDetectAndParse(response, registry)
        }
    }

    fun convertToolCallsToNative(
        calls: List<ParsedToolCall>,
        targetProtocol: Protocol
    ): Any {
        return when (targetProtocol) {
            Protocol.NATIVE_OPENAI -> convertToOpenAIFormat(calls)
            Protocol.NATIVE_ANTHROPIC -> convertToAnthropicFormat(calls)
            Protocol.XML -> convertToXmlFormat(calls)
            Protocol.TEXT -> convertToTextFormat(calls)
            else -> convertToOpenAIFormat(calls)
        }
    }

    fun convertResultToXml(
        results: List<ExecutionResult>,
        toolCalls: List<ParsedToolCall>
    ): String {
        val sb = StringBuilder()
        for ((i, result) in results.withIndex()) {
            val call = toolCalls.find { it.toolCallId == result.toolCallId }
            val toolName = call?.rawName ?: result.toolName
            sb.appendLine("<result tool=\"$toolName\" id=\"${result.toolCallId}\">")
            when (val outcome = result.outcome) {
                is ToolOutcome.Success -> {
                    sb.appendLine("  <status>success</status>")
                    sb.appendLine("  <data>${escapeXml(outcome.data)}</data>")
                    if (outcome.metadata.isNotEmpty()) {
                        sb.appendLine("  <metadata>${escapeXml(JSONObject(outcome.metadata).toString())}</metadata>")
                    }
                }
                is ToolOutcome.Failure -> {
                    sb.appendLine("  <status>failure</status>")
                    sb.appendLine("  <error code=\"${outcome.code}\">${escapeXml(outcome.error)}</error>")
                }
                is ToolOutcome.Cancelled -> {
                    sb.appendLine("  <status>cancelled</status>")
                }
            }
            sb.appendLine("  <duration_ms>${result.durationMs}</duration_ms>")
            sb.appendLine("</result>")
        }
        return sb.toString()
    }

    private fun resolveProtocol(preferred: Protocol): Protocol {
        return if (preferred == Protocol.AUTO) Protocol.NATIVE_OPENAI else preferred
    }

    private fun buildOpenAIDefinitions(
        tools: List<ToolSpec>,
        config: ProtocolConfig
    ): ToolDefinitions {
        val arr = JSONArray()
        for (tool in tools) {
            val fn = JSONObject()
            fn.put("name", tool.name)
            fn.put("description", truncate(tool.description, config.maxToolDescriptionLen))
            fn.put("parameters", buildJsonSchema(tool.parameters))
            val definition = JSONObject().apply {
                put("type", "function")
                put("function", fn)
            }
            arr.put(definition)
        }
        return ToolDefinitions(Protocol.NATIVE_OPENAI, arr)
    }

    private fun buildAnthropicDefinitions(
        tools: List<ToolSpec>,
        config: ProtocolConfig
    ): ToolDefinitions {
        val arr = JSONArray()
        for (tool in tools) {
            val obj = JSONObject()
            obj.put("name", tool.name)
            obj.put("description", truncate(tool.description, config.maxToolDescriptionLen))
            obj.put("input_schema", buildJsonSchema(tool.parameters))
            arr.put(obj)
        }
        return ToolDefinitions(Protocol.NATIVE_ANTHROPIC, arr)
    }

    private fun buildXmlPrompt(
        tools: List<ToolSpec>,
        config: ProtocolConfig
    ): ToolDefinitions {
        val sb = StringBuilder()
        sb.appendLine("Available tools:")
        sb.appendLine()
        for (tool in tools) {
            sb.appendLine("<tool name=\"${tool.name}\">")
            sb.appendLine("  <description>${escapeXml(tool.description)}</description>")
            if (tool.parameters.isNotEmpty()) {
                sb.appendLine("  <parameters>")
                for (param in tool.parameters) {
                    val req = if (param.required) " required=\"true\"" else ""
                    sb.appendLine("    <param name=\"${param.name}\" type=\"${param.type.name.lowercase()}\"$req>")
                    sb.appendLine("      <description>${escapeXml(param.description)}</description>")
                    param.defaultValue?.let {
                        sb.appendLine("      <default>${escapeXml(it.toString())}</default>")
                    }
                    param.examples?.forEach { example ->
                        sb.appendLine("      <example>${escapeXml(example)}</example>")
                    }
                    sb.appendLine("    </param>")
                }
                sb.appendLine("  </parameters>")
            }
            sb.appendLine("</tool>")
            sb.appendLine()
        }
        sb.appendLine("Use format: <tool name=\"tool_name\"><param name=\"param_name\">value</param></tool>")
        return ToolDefinitions(Protocol.XML, sb.toString(), sb.toString())
    }

    private fun buildTextPrompt(
        tools: List<ToolSpec>,
        config: ProtocolConfig
    ): ToolDefinitions {
        val sb = StringBuilder()
        sb.appendLine("Available tools:")
        sb.appendLine()
        for (tool in tools) {
            val params = if (tool.parameters.isNotEmpty()) {
                tool.parameters.joinToString(", ") { p ->
                    val req = if (p.required) " (required)" else ""
                    "${p.name}: ${p.type.name.lowercase()}$req"
                }
            } else {
                "No parameters"
            }
            sb.appendLine("- ${tool.name}: ${tool.description}")
            sb.appendLine("  Parameters: $params")
        }
        return ToolDefinitions(Protocol.TEXT, sb.toString(), sb.toString())
    }

    private fun buildJsonSchema(params: List<ParameterSpec>): JSONObject {
        val schema = JSONObject()
        schema.put("type", "object")
        val properties = JSONObject()
        val required = JSONArray()
        for (param in params) {
            val prop = JSONObject()
            prop.put("type", mapTypeToJsonSchema(param.type))
            prop.put("description", param.description)
            if (param.defaultValue != null) {
                prop.put("default", param.defaultValue)
            }
            if (param.enumValues != null) {
                prop.put("enum", JSONArray(param.enumValues))
            }
            if (param.examples != null) {
                prop.put("examples", JSONArray(param.examples))
            }
            properties.put(param.name, prop)
            if (param.required) {
                required.put(param.name)
            }
        }
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        return schema
    }

    private fun mapTypeToJsonSchema(type: ParameterType): String {
        return when (type) {
            ParameterType.STRING -> "string"
            ParameterType.INTEGER -> "integer"
            ParameterType.FLOAT -> "number"
            ParameterType.BOOLEAN -> "boolean"
            ParameterType.FILE -> "string"
            ParameterType.JSON -> "object"
            ParameterType.ENUM -> "string"
            ParameterType.ARRAY -> "array"
            ParameterType.OBJECT -> "object"
        }
    }

    private fun parseOpenAIToolCalls(
        response: String,
        registry: (String) -> ToolSpec?
    ): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices") ?: return results
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue
                val toolCalls = delta.optJSONArray("tool_calls") ?: continue
                for (j in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(j)
                    val id = tc.optString("id", UUID.randomUUID().toString())
                    val fn = tc.optJSONObject("function") ?: continue
                    val name = fn.optString("name", "")
                    val argsStr = fn.optString("arguments", "{}")
                    val args = try {
                        JSONObject(argsStr).toMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    val spec = registry(name)
                    if (spec != null) {
                        results.add(ParsedToolCall(id, spec, args, name))
                    }
                }
            }
        } catch (e: Exception) { AppLogger.e(TAG, "parseOpenAIToolCalls failed", e) }
        return results
    }

    private fun parseAnthropicToolCalls(
        response: String,
        registry: (String) -> ToolSpec?
    ): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        try {
            val json = JSONObject(response)
            val content = json.optJSONArray("content") ?: return results
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") != "tool_use") continue
                val id = block.optString("id", UUID.randomUUID().toString())
                val name = block.optString("name", "")
                val input = block.optJSONObject("input")?.toMap() ?: emptyMap()
                val spec = registry(name)
                if (spec != null) {
                    results.add(ParsedToolCall(id, spec, input, name))
                }
            }
        } catch (e: Exception) { AppLogger.e(TAG, "parseAnthropicToolCalls failed", e) }
        return results
    }

    private fun parseXmlToolCalls(
        response: String,
        registry: (String) -> ToolSpec?
    ): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        val toolPattern = Regex("<tool\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>([\\s\\S]*?)</tool>")
        val paramPattern = Regex("<param\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>([\\s\\S]*?)</param>")

        for (match in toolPattern.findAll(response)) {
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            val args = mutableMapOf<String, Any?>()
            for (pm in paramPattern.findAll(body)) {
                args[pm.groupValues[1]] = pm.groupValues[2].trim()
            }
            val spec = registry(name)
            val id = UUID.randomUUID().toString()
            if (spec != null) {
                results.add(ParsedToolCall(id, spec, args, name))
            }
        }
        return results
    }

    private fun parseTextToolCalls(
        response: String,
        registry: (String) -> ToolSpec?
    ): List<ParsedToolCall> {
        return parseXmlToolCalls(response, registry)
    }

    private fun autoDetectAndParse(
        response: String,
        registry: (String) -> ToolSpec?
    ): List<ParsedToolCall> {
        val trimmed = response.trim()
        return when {
            trimmed.startsWith("{") && trimmed.contains("\"tool_calls\"") ->
                parseOpenAIToolCalls(trimmed, registry)
            trimmed.startsWith("{") && trimmed.contains("\"tool_use\"") ->
                parseAnthropicToolCalls(trimmed, registry)
            trimmed.contains("<tool ") ->
                parseXmlToolCalls(trimmed, registry)
            else -> parseXmlToolCalls(trimmed, registry)
        }
    }

    private fun convertToOpenAIFormat(calls: List<ParsedToolCall>): JSONArray {
        val arr = JSONArray()
        for (call in calls) {
            val tc = JSONObject().apply {
                put("id", call.toolCallId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", call.rawName)
                    put("arguments", JSONObject(call.arguments).toString())
                })
            }
            arr.put(tc)
        }
        return arr
    }

    private fun convertToAnthropicFormat(calls: List<ParsedToolCall>): JSONArray {
        val arr = JSONArray()
        for (call in calls) {
            val block = JSONObject().apply {
                put("type", "tool_use")
                put("id", call.toolCallId)
                put("name", call.rawName)
                put("input", JSONObject(call.arguments))
            }
            arr.put(block)
        }
        return arr
    }

    private fun convertToXmlFormat(calls: List<ParsedToolCall>): String {
        val sb = StringBuilder()
        for (call in calls) {
            sb.appendLine("<tool name=\"${call.rawName}\">")
            for ((key, value) in call.arguments) {
                sb.appendLine("  <param name=\"$key\">${escapeXml(value?.toString() ?: "")}</param>")
            }
            sb.appendLine("</tool>")
        }
        return sb.toString()
    }

    private fun convertToTextFormat(calls: List<ParsedToolCall>): String {
        val sb = StringBuilder()
        for (call in calls) {
            val argsStr = call.arguments.entries.joinToString(", ") { "${it.key}=${it.value}" }
            sb.appendLine("Call tool: ${call.rawName} with params: $argsStr")
        }
        return sb.toString()
    }

    private fun truncate(text: String, maxLen: Int): String {
        return if (text.length <= maxLen) text else text.take(maxLen - 3) + "..."
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in keys()) {
            map[key] = opt(key)
        }
        return map
    }
}
