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
 * Base sealed class for all tool result data types.
 *
 * Domain-specific result data classes have been split into sibling files
 * (ToolResultBasic.kt, ToolResultFile.kt, ToolResultTerminal.kt, etc.)
 * in the same package [com.apex.core.tools] - sealed subtypes may live in
 * different files of the same package.
 */

@Serializable
sealed class ToolResultData {
    /** Converts the structured data to a string representation */
    abstract override fun toString(): String
    fun toJson(): String {
        val jsonConfig = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "__type"
        }
        val json = jsonConfig.encodeToString(this)
        return json
    }
}
