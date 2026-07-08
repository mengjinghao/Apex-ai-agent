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
 * Basic domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class BooleanResultData(val value: Boolean) : ToolResultData() {
    override fun toString(): String = value.toString()
}


@Serializable
data class StringResultData(val value: String) : ToolResultData() {
    override fun toString(): String = value
}


@Serializable
data class IntResultData(val value: Int) : ToolResultData() {
    override fun toString(): String = value.toString()
}


@Serializable
data class BinaryResultData(val value: ByteArray) : ToolResultData() {
    override fun toString(): String = "Binary data (${value.size} bytes)"

    override fun equals(other: Any): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BinaryResultData
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }
}

/** 文件分段读取结果数据 */

@Serializable
data class CalculationResultData(
        val expression: String,
        val result: Double,
        val formattedResult: String,
        val variables: Map<String, Double> = emptyMap()
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Expression: ${expression}")
        sb.appendLine("Result: ${formattedResult}")

        if (variables.isNotEmpty()) {
            sb.appendLine("Variables:")
            variables.forEach { (name, value) -> sb.appendLine("  ${name} = ${value}") }
        }

        return sb.toString()
    }
}

/** 日期结果结构化数�*/

@Serializable
data class DateResultData(val date: String, val format: String, val formattedDate: String) :
        ToolResultData() {
    override fun toString(): String {
        return formattedDate
    }
}

/** Connection result data */

@Serializable
data class ConnectionResultData(
        val connectionId: String,
        val isActive: Boolean,
        val timestamp: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return "Simulated connection established. Demo connection ID: ${connectionId}"
    }
}

/** Represents a directory listing result */

