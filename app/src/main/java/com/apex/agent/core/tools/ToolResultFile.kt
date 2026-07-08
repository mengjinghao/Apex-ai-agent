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
 * File domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FilePartContentData(
        val path: String,
        val content: String,
        val partIndex: Int,
        val totalParts: Int,
        val startLine: Int,
        val endLine: Int,
        val totalLines: Int,
        @EncodeDefault
        val env: String = "android"
) : ToolResultData() {
    override fun toString(): String {
        val partInfo =
                "Part ${partIndex + 1} of ${totalParts} (Lines ${startLine + 1}-${endLine} of ${totalLines})"
        return "[${env}] ${partInfo}\n\n${content}"
    }
}

/** ADB命令执行结果数据 */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class DirectoryListingData(
        val path: String,
        val entries: List<FileEntry>,
        @EncodeDefault
        val env: String = "android"
) : ToolResultData() {
    @Serializable
    data class FileEntry(
            val name: String,
            val isDirectory: Boolean,
            val size: Long,
            val permissions: String,
            val lastModified: String
    )

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("[${env}] Directory listing for ${path}:")
        entries.forEach { entry ->
            val typeIndicator = if (entry.isDirectory) "d" else "-"
            sb.appendLine(
                    "${typeIndicator}${entry.permissions} ${
                    entry.size.toString().padStart(8)
                } ${entry.lastModified} ${entry.name}"
            )
        }
        return sb.toString()
    }
}

/** Represents a file content result */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FileContentData(
        val path: String,
        val content: String,
        val size: Long,
        @EncodeDefault
        val env: String = "android"
) :
        ToolResultData() {
    override fun toString(): String {
        return "[${env}] Content of ${path}:\n${content}"
    }
}

/** Represents a binary file content result (Base64 encoded) */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class BinaryFileContentData(
        val path: String,
        val contentBase64: String,
        val size: Long,
        @EncodeDefault
        val env: String = "android"
) : ToolResultData() {
    override fun toString(): String {
        return "[${env}] Binary content of ${path} (${size} bytes, base64 length=${contentBase64.length})"
    }
}

/** Represents file existence check result */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FileExistsData(
        val path: String,
        val exists: Boolean,
        val isDirectory: Boolean = false,
        val size: Long = 0,
        @EncodeDefault
        val env: String = "android"
) : ToolResultData() {
    override fun toString(): String {
        val fileType = if (isDirectory) "Directory" else "File"
        return if (exists) {
            "[${env}] ${fileType} exists at path: ${path} (size: ${size} bytes)"
        } else {
            "[${env}] No file or directory exists at path: ${path}"
        }
    }
}

/** Represents detailed file information */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FileInfoData(
        val path: String,
        val exists: Boolean,
        val fileType: String, // "file", "directory", or "other"
        val size: Long,
        val permissions: String,
        val owner: String,
        val group: String,
        val lastModified: String,
        val rawStatOutput: String,
        @EncodeDefault
        val env: String = "android"
) : ToolResultData() {
    override fun toString(): String {
        if (!exists) {
            return "[${env}] File or directory does not exist at path: ${path}"
        }

        val sb = StringBuilder()
        sb.appendLine("[${env}] File information for ${path}:")
        sb.appendLine("Type: ${fileType}")
        sb.appendLine("Size: ${size} bytes")
        sb.appendLine("Permissions: ${permissions}")
        sb.appendLine("Owner: ${owner}")
        sb.appendLine("Group: ${group}")
        sb.appendLine("Last modified: ${lastModified}")
        return sb.toString()
    }
}

/** Represents a file operation result */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FileOperationData(
        val operation: String,
        @EncodeDefault
        val env: String = "android",
        val path: String,
        val successful: Boolean,
        val details: String
) : ToolResultData() {
    override fun toString(): String {
        return "[${env}] ${details}"
    }
}

/** Represents the result of an 'apply_file' operation, including the AI-generated diff */

@Serializable
data class FileApplyResultData(
    val operation: FileOperationData,
    val aiDiffInstructions: String,
    val syntaxCheckResult: String? = null,
    val diffContent: String? = null
) : ToolResultData() {
    private fun buildRequestContent(): String {
        val sections = mutableListOf<String>()
        sections.add(operation.toString())

        extractDiffSummaryLine()?.let { sections.add(it) }

        if (!syntaxCheckResult.isNullOrBlank()) {
            sections.add("--- Syntax Check ---")
            sections.add(syntaxCheckResult)
        }

        return sections.joinToString("\n")
    }

    private fun extractDiffSummaryLine(): String? {
        val candidates = listOfNotNull(diffContent, aiDiffInstructions)

        return candidates
            .asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("Changes: +") ||
                    it.equals("No changes detected (files are identical)", ignoreCase = true)
            }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(operation.toString())

        // If diffContent is available, embed it in a custom XML-like tag for the renderer.
        if (diffContent != null) {
            val encodedDiff = diffContent.replace("&", "&").replace("<", "<").replace(">", ">")
            sb.append("<file-diff path=\"${operation.path}\" details=\"${operation.details}\">")
            sb.append("<![CDATA[${encodedDiff}]]>")
            sb.append("</file-diff>")
        }

        val requestContent = buildRequestContent()
        if (requestContent.isNotBlank()) {
            sb.append("<file-request-content><![CDATA[${requestContent}]]></file-request-content>")
        }

        if (aiDiffInstructions.isNotEmpty() && !aiDiffInstructions.startsWith("Error")) {
            sb.appendLine("\n--- AI-Generated Diff ---")
            sb.appendLine(aiDiffInstructions)
        }
        if (!syntaxCheckResult.isNullOrEmpty()) {
            sb.appendLine("\n--- Syntax Check ---")
            sb.appendLine(syntaxCheckResult)
        }
        return sb.toString()
    }
}

/** HTTP响应结果结构化数�*/

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FindFilesResultData(
        val path: String,
        val pattern: String,
        val files: List<String>,
        @EncodeDefault
        val env: String = "android"
) :
        ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("[${env}] File Search Result:")
        sb.appendLine("Search Path: ${path}")
        sb.appendLine("Pattern: ${pattern}")

        sb.appendLine("Found ${files.size} files:")
        files.forEachIndexed { index, file ->
            if (index < 10 || files.size <= 20) {
                sb.appendLine("- ${file}")
            } else if (index == 10 && files.size > 20) {
                sb.appendLine("... and ${files.size - 10} other files")
            }
        }

        return sb.toString()
    }
}

/** FFmpeg处理结果数据 */

