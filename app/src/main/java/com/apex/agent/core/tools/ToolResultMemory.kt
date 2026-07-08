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
 * Memory domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class MemoryQueryResultData(
    val memories: List<MemoryInfo>,
    val snapshotId: String? = null,
    val snapshotCreated: Boolean = false,
    val excludedBySnapshotCount: Int = 0
) : ToolResultData() {

    @Serializable
    data class MemoryInfo(
        val title: String,
        val content: String,
        val source: String,
        val tags: List<String>,
        val createdAt: String,
        val chunkInfo: String? = null,
        val chunkIndices: List<Int>? = null
    )

    override fun toString(): String {
        val snapshotSummary = buildList {
            snapshotId?.takeIf { it.isNotBlank() }?.let {
                add("Snapshot ID: ${it}")
            }
            if (snapshotCreated) {
                add("Snapshot created: true")
            }
            if (excludedBySnapshotCount > 0) {
                add("Excluded by snapshot: ${excludedBySnapshotCount}")
            }
        }.joinToString("\n")

        if (memories.isEmpty()) {
            return if (snapshotSummary.isBlank()) {
                "No relevant memories found."
            } else {
                "${snapshotSummary}\nNo relevant memories found."
            }
        }
        val memoryText = memories.joinToString("\n---\n") { memory ->
            """
            Title: ${memory.title}
            Content: ${memory.content}
            Source: ${memory.source}
            Tags: ${memory.tags.joinToString(", ")}
            Created: ${memory.createdAt}
            """.trimIndent()
        }
        return if (snapshotSummary.isBlank()) {
            memoryText
        } else {
            "${snapshotSummary}\n---\n${memoryText}"
        }
    }
}

/** 自动化配置搜索结果数�*/

@Serializable
data class MemoryLinkResultData(
    val sourceTitle: String,
    val targetTitle: String,
    val linkType: String,
    val weight: Float,
    val description: String
) : ToolResultData() {
    override fun toString(): String {
        return "Successfully linked memory: '${sourceTitle}' -> '${targetTitle}' (Type: ${linkType}, Strength: ${weight})"
    }
}

/** 记忆链接查询结果数据 */

@Serializable
data class MemoryLinkQueryResultData(
    val totalCount: Int,
    val links: List<LinkInfo>
) : ToolResultData() {
    @Serializable
    data class LinkInfo(
        val linkId: Long,
        val sourceTitle: String,
        val targetTitle: String,
        val linkType: String,
        val weight: Float,
        val description: String
    )

    override fun toString(): String {
        if (links.isEmpty()) {
            return "No memory links found."
        }
        val sb = StringBuilder()
        sb.appendLine("Memory Links (${totalCount}):")
        links.forEach { link ->
            sb.appendLine("- #${link.linkId}: '${link.sourceTitle}' -> '${link.targetTitle}' (Type: ${link.linkType}, Weight: ${link.weight})")
            if (link.description.isNotBlank()) {
                sb.appendLine("  Description: ${link.description}")
            }
        }
        return sb.toString().trim()
    }
}

/** 语音服务 TTS HTTP 配置条目 */

