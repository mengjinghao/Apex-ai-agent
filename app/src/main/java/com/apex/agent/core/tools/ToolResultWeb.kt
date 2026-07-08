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
 * Web domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class HttpResponseData(
        val url: String,
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val contentType: String,
        val content: String,
        val contentBase64: String? = null,
        val size: Int,
        val cookies: Map<String, String> = emptyMap()
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("HTTP Response:")
        sb.appendLine("URL: ${url}")
        sb.appendLine("Status: ${statusCode} ${statusMessage}")
        sb.appendLine("Content-Type: ${contentType}")
        sb.appendLine("Size: ${size} bytes")

        // 添加Cookie信息
        if (cookies.isNotEmpty()) {
            sb.appendLine("Cookies: ${cookies.size}")
            cookies.entries.take(5).forEach { (name, value) ->
                sb.appendLine("  ${name}: ${value.take(30)}${if (value.length > 30) "..." else ""}")
            }
            if (cookies.size > 5) {
                sb.appendLine("  ... and ${cookies.size - 5} more cookies")
            }
        }

        sb.appendLine()
        sb.appendLine("Content Summary:")
        sb.append(content)
        return sb.toString()
    }
}

/** 系统设置数据 */

@Serializable
data class VisitWebResultData(
        val url: String,
        val title: String,
        val content: String,
        val metadata: Map<String, String> = emptyMap(),
        val links: List<LinkData> = emptyList(),
        val imageLinks: List<String> = emptyList(),
        val visitKey: String? = null,
        val contentSavedTo: String? = null,
        val contentTruncated: Boolean = false,
        val originalContentLength: Int? = null
) : ToolResultData() {
    companion object {
        private const val MAX_INLINE_LINKS = 120
        private const val MAX_INLINE_IMAGES = 120
    }

    @Serializable
    data class LinkData(val url: String, val text: String)

    override fun toString(): String {
        val sb = StringBuilder()
        visitKey?.let { sb.appendLine("Visit key: ${it}\n") }

        if (links.isNotEmpty()) {
            sb.appendLine("Results:")
            links.take(MAX_INLINE_LINKS).forEachIndexed { index, link ->
                sb.appendLine("[${index + 1}] ${link.text}")
            }
            val omittedCount = links.size - MAX_INLINE_LINKS
            if (omittedCount > 0) {
                sb.appendLine("... (${omittedCount} more links omitted from inline preview)")
            }
            sb.appendLine()
        }

        if (imageLinks.isNotEmpty()) {
            sb.appendLine("Images:")
            imageLinks.take(MAX_INLINE_IMAGES).forEachIndexed { index, link ->
                val name = link.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
                sb.appendLine("[${index + 1}] ${name}")
            }
            val omittedCount = imageLinks.size - MAX_INLINE_IMAGES
            if (omittedCount > 0) {
                sb.appendLine("... (${omittedCount} more images omitted from inline preview)")
            }
            sb.appendLine()
        }

        contentSavedTo?.let {
            sb.appendLine("Full content saved to file: ${it}")
            originalContentLength?.let { totalChars ->
                sb.appendLine("Original content length: ${totalChars} chars")
            }
            if (contentTruncated) {
                sb.appendLine("Use read_file_part or grep_code to inspect the saved file.")
            }
            sb.appendLine()
        }

        sb.appendLine(if (contentTruncated) "Content Preview:" else "Content:")
        sb.append(content)

        return sb.toString()
    }
}

/** Intent execution result data */

@Serializable
data class IntentResultData(
        val action: String,
        val uri: String,
        val package_name: String,
        val component: String,
        val flags: Int,
        val extras_count: Int,
        val result: String
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Intent Execution Result:")
        sb.appendLine("Action: ${action}")
        if (uri != "null") sb.appendLine("URI: ${uri}")
        if (package_name != "null") sb.appendLine("Package: ${package_name}")
        if (component != "null") sb.appendLine("Component: ${component}")
        sb.appendLine("Flags: ${flags}")
        sb.appendLine("Extras Count: ${extras_count}")
        sb.appendLine("\nExecution Result: ${result}")
        return sb.toString()
    }
}

/** 文件查找结果数据 */

@Serializable
data class FFmpegResultData(
        val command: String,
        val returnCode: Int,
        val output: String,
        val duration: Long,
        val outputFile: String? = null,
        val mediaInfo: MediaInfo? = null
) : ToolResultData() {
    @Serializable
    data class MediaInfo(
            val format: String,
            val duration: String,
            val bitrate: String,
            val videoStreams: List<StreamInfo>,
            val audioStreams: List<StreamInfo>
    )

    @Serializable
    data class StreamInfo(
            val index: Int,
            val codecType: String,
            val codecName: String,
            val resolution: String? = null,
            val frameRate: String? = null,
            val sampleRate: String? = null,
            val channels: Int? = null
    )

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("FFmpeg Execution Result:")
        sb.appendLine("Command: ${command}")
        sb.appendLine("Return Code: ${returnCode}")
        sb.appendLine("Execution Time: ${duration}ms")

        outputFile?.let { sb.appendLine("Output File: ${it}") }

        mediaInfo?.let { info ->
            sb.appendLine("\nMedia Information:")
            sb.appendLine("Format: ${info.format}")
            sb.appendLine("Duration: ${info.duration}")
            sb.appendLine("Bitrate: ${info.bitrate}")

            if (info.videoStreams.isNotEmpty()) {
                sb.appendLine("\nVideo Streams:")
                info.videoStreams.forEach { stream ->
                    sb.appendLine("  Index: ${stream.index}")
                    sb.appendLine("  Codec: ${stream.codecName}")
                    stream.resolution?.let { sb.appendLine("  Resolution: ${it}") }
                    stream.frameRate?.let { sb.appendLine("  Frame Rate: ${it}") }
                    sb.appendLine()
                }
            }

            if (info.audioStreams.isNotEmpty()) {
                sb.appendLine("\nAudio Streams:")
                info.audioStreams.forEach { stream ->
                    sb.appendLine("  Index: ${stream.index}")
                    sb.appendLine("  Codec: ${stream.codecName}")
                    stream.sampleRate?.let { sb.appendLine("  Sample Rate: ${it}") }
                    stream.channels?.let { sb.appendLine("  Channels: ${it}") }
                    sb.appendLine()
                }
            }
        }

        sb.appendLine("\nOutput Log:")
        sb.append(output)

        return sb.toString()
    }
}

/** 通知数据结构 */

