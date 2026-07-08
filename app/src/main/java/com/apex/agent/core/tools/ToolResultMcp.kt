package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * Mcp domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class McpRestartLogPluginResultItem(
        val id: String,
        val displayName: String,
        val shortName: String,
        val status: String,
        val message: String,
        val serviceName: String,
        val log: String
)


@Serializable
data class McpRestartWithLogsResultData(
        val timeoutMs: Long,
        val elapsedMs: Long,
        val timedOut: Boolean,
        val progress: Double,
        val message: String,
        val pluginsTotal: Int,
        val pluginsStarted: Int,
        val successCount: Int,
        val failedCount: Int,
        val plugins: List<McpRestartLogPluginResultItem>,
        val extraLogs: Map<String, String>
) : ToolResultData() {
    override fun toString(): String {
        return "MCP restart: success=${successCount}, failed=${failedCount}, timedOut=${timedOut}"
    }
}


