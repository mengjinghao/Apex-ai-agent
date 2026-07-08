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
 * Ui domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class SimplifiedUINode(
        val className: String?,
        val text: String?,
        val contentDesc: String?,
        val resourceId: String?,
        val bounds: String?,
        val isClickable: Boolean,
        val children: List<SimplifiedUINode>
) {
    fun toTreeString(indent: String = ""): String {
        if (!shouldKeepNode()) return ""

        val sb = StringBuilder()

        // Node identifier
        sb.append(indent)
        if (isClickable) sb.append("ï¼?) else sb.append("ï¼?)

        // Class name
        className?.let { sb.append("[${it}] ") }

        // Text content (maximum 30 characters)
        text?.takeIf { it.isNotBlank() }?.let {
            val displayText = if (it.length > 30) "${it.take(27)}..." else it
            sb.append("T: \"${displayText}\" ")
        }

        // Content description
        contentDesc?.takeIf { it.isNotBlank() }?.let { sb.append("D: \"${it}\" ") }

        // Resource ID
        resourceId?.takeIf { it.isNotBlank() }?.let { sb.append("ID: ${it} ") }

        // Bounds
        bounds?.let { sb.append("ï¼Œçš„${it}") }

        sb.append("\n")

        // Process children recursively
        children.forEach { sb.append(it.toTreeString("${indent}  ")) }

        return sb.toString()
    }

    private fun shouldKeepNode(): Boolean {
        // Keep conditions: key element types or has content or clickable or has children that
        // should be kept
        val isKeyElement =
                className in
                        setOf("Button", "TextView", "EditText", "ScrollView", "Switch", "ImageView")
        val hasContent = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()

        return isKeyElement || hasContent || isClickable || children.any { it.shouldKeepNode() }
    }
}

/** Represents UI page information result data */

@Serializable
data class UIPageResultData(
        val packageName: String,
        val activityName: String,
        val uiElements: SimplifiedUINode
) : ToolResultData() {
    override fun toString(): String {
        return """
            |Current Application: ${packageName}
            |Current Activity: ${activityName}
            |
            |UI Elements:
            |${uiElements.toTreeString()}
            """.trimMargin()
    }
}

/** Represents a UI action result data */

@Serializable
data class UIActionResultData(
        val actionType: String,
        val actionDescription: String,
        val coordinates: Pair<Int, Int>? = null,
        val elementId: String? = null
) : ToolResultData() {
    override fun toString(): String {
        return actionDescription
    }
}

/** Represents a combined operation result data */

@Serializable
data class CombinedOperationResultData(
        val operationSummary: String,
        val waitTime: Int,
        val pageInfo: UIPageResultData
) : ToolResultData() {
    override fun toString(): String {
        return "${operationSummary} (waited ${waitTime}ms)\n\n${pageInfo}"
    }
}

/** Device information result data */

@Serializable
data class ComputerPageInfoNode(
    val interactionId: Int?,
    val type: String, // e.g., "container", "button", "link", "text", "input"
    val description: String,
    val children: List<ComputerPageInfoNode>
) {
    fun toTreeString(level: Int = 0): String {
        val indent = "  ".repeat(level)
        val idPrefix = interactionId?.let { "(${it}) " } ?: ""
        val typePrefix = if (type != "container" && type != "text") "ï¼Œçš„${type}: " else ""
        val selfStr = "${indent}${idPrefix}${typePrefix}'${description.trim()}'"

        val childrenStr = if (children.isNotEmpty()) {
            "\n" + children.joinToString("\n") { it.toTreeString(level + 1) }
        } else {
            ""
        }
        return selfStr + childrenStr
    }
}

/** Represents the result of a computer desktop action */

@Serializable
data class ComputerDesktopActionResultData(
    val action: String,
    val target: String? = null,
    val resultSummary: String,
    val tabs: List<ComputerTabInfo>? = null,
    val pageContent: ComputerPageInfoNode? = null
) : ToolResultData() {
    @Serializable
    data class ComputerTabInfo(
        val id: String,
        val title: String,
        val url: String,
        val isActive: Boolean
    )

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Computer Desktop Action: '${action}'")
        target?.let { sb.appendLine("Target: ${it}") }
        sb.appendLine("Result: ${resultSummary}")
        tabs?.let {
            sb.appendLine("\nOpen Tabs (${it.size}):")
            it.forEach { tab ->
                sb.appendLine("- [${if (tab.isActive) "*" else " "}] ${tab.title} (${tab.url})")
            }
        }
        pageContent?.let {
            sb.appendLine("\n--- Page Content (Interactable Elements marked with ï¼?---")
            sb.append(it.toTreeString())
        }
        return sb.toString()
    }
}

/** Represents the result of a memory query */

