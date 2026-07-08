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
 * Sandbox domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class SandboxScriptExecutionResultData(
        val success: Boolean,
        val scriptPath: String,
        val functionName: String,
        val params: JsonElement? = null,
        val envFilePath: String? = null,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val durationMs: Long,
        val result: JsonElement? = null,
        val error: String? = null,
        val events: List<String> = emptyList(),
        val executionMode: String? = null,
        val scriptLabel: String? = null,
        val requestedWaitMs: Long? = null
) : ToolResultData() {
    override fun toString(): String {
        return if (success) {
            "Sandbox script executed successfully (${durationMs}ms)"
        } else {
            error ?: "Sandbox script execution failed"
        }
    }
}


@Serializable
data class EnvironmentVariableReadResultData(
        val key: String,
        val value: String? = null,
        val exists: Boolean
) : ToolResultData() {
    override fun toString(): String {
        return if (exists) {
            "${key}=${value.orEmpty()}"
        } else {
            "${key} is not set"
        }
    }
}


@Serializable
data class EnvironmentVariableWriteResultData(
        val key: String,
        val requestedValue: String,
        val value: String? = null,
        val exists: Boolean,
        val cleared: Boolean
) : ToolResultData() {
    override fun toString(): String {
        return if (cleared) {
            "${key} cleared"
        } else {
            "${key}=${value.orEmpty()}"
        }
    }
}


@Serializable
data class SandboxPackageResultItem(
        val packageName: String,
        val displayName: String,
        val description: String,
        val isBuiltIn: Boolean,
        val enabledByDefault: Boolean,
        val enabled: Boolean,
        val imported: Boolean,
        val isDisabledByUser: Boolean,
        val toolCount: Int,
        val manageMode: String
)


@Serializable
data class SandboxPackagesResultData(
        val externalPackagesPath: String,
        val scriptDevGuide: String,
        val totalCount: Int,
        val builtInCount: Int,
        val externalCount: Int,
        val enabledCount: Int,
        val disabledCount: Int,
        val packages: List<SandboxPackageResultItem>
) : ToolResultData() {
    override fun toString(): String {
        return "Sandbox packages: total=${totalCount}, enabled=${enabledCount}, builtIn=${builtInCount}, external=${externalCount}"
    }
}


@Serializable
data class SandboxPackageUpdateResultData(
        val packageName: String,
        val requestedEnabled: Boolean,
        val previousEnabled: Boolean,
        val currentEnabled: Boolean,
        val message: String
) : ToolResultData() {
    override fun toString(): String {
        return message
    }
}


@Serializable
data class ScriptExecutionTraceData(
        val kind: String,
        val level: String? = null,
        val message: String,
        val callId: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        val prefix =
                when (kind.lowercase(Locale.ROOT)) {
                    "log" ->
                            level
                                    ?.takeIf { it.isNotBlank() }
                                    ?.uppercase(Locale.ROOT)
                                    ?.let { "[${it}] " }
                                    ?: "[LOG] "
                    "intermediate" -> "[INTERMEDIATE] "
                    else -> "[TRACE] "
                }
        return prefix + message
    }
}


