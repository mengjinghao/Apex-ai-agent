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
 * Terminal domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class ADBResultData(val command: String, val output: String, val exitCode: Int) :
        ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("ADB Command Execution Result:")
        sb.appendLine("Command: ${command}")
        sb.appendLine("Exit Code: ${exitCode}")
        sb.appendLine("\nOutput:")
        sb.appendLine(output)
        return sb.toString()
    }
}

/** 终端命令执行结果数据 */

@Serializable
data class TerminalCommandResultData(
        val command: String,
        val output: String,
        val exitCode: Int,
        val sessionId: String,
        val timedOut: Boolean = false
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Terminal Command Execution Result:")
        sb.appendLine("Command: ${command}")
        sb.appendLine("Session: ${sessionId}")
        sb.appendLine("Exit Code: ${exitCode}")
        if (timedOut) {
            sb.appendLine("Timed Out: true")
        }
        sb.appendLine("\nOutput:")
        sb.appendLine(output)
        return sb.toString()
    }
}

/** 隐藏终端命令执行结果数据 */

@Serializable
data class HiddenTerminalCommandResultData(
        val command: String,
        val output: String,
        val exitCode: Int,
        val executorKey: String,
        val timedOut: Boolean = false
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Hidden Terminal Command Execution Result:")
        sb.appendLine("Command: ${command}")
        sb.appendLine("Executor Key: ${executorKey}")
        sb.appendLine("Exit Code: ${exitCode}")
        if (timedOut) {
            sb.appendLine("Timed Out: true")
        }
        sb.appendLine("\nOutput:")
        sb.appendLine(output)
        return sb.toString()
    }
}

/** 计算结果结构化数�*/

@Serializable
data class TerminalSessionCreationResultData(
    val sessionId: String,
    val sessionName: String,
    val isNewSession: Boolean
) : ToolResultData() {
    override fun toString(): String {
        return if (isNewSession) {
            "Successfully created new terminal session. Session Name: '${sessionName}', Session ID: ${sessionId}"
        } else {
            "Successfully retrieved existing terminal session. Session Name: '${sessionName}', Session ID: ${sessionId}"
        }
    }
}

/** 终端会话关闭结果数据 */

@Serializable
data class TerminalSessionCloseResultData(
    val sessionId: String,
    val success: Boolean,
    val message: String
) : ToolResultData() {
    override fun toString(): String = message
}

/** 终端会话当前屏幕内容结果数据（仅当前屏，不含历史滚动缓冲�*/

@Serializable
data class TerminalSessionScreenResultData(
    val sessionId: String,
    val rows: Int,
    val cols: Int,
    val content: String,
    val commandRunning: Boolean = false
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Terminal Session Screen Snapshot:")
        sb.appendLine("Session: ${sessionId}")
        sb.appendLine("Size: ${cols}x${rows}")
        sb.appendLine("Command Running: ${commandRunning}")
        sb.appendLine()
        sb.append(content)
        return sb.toString()
    }
}

/** Grep代码搜索结果数据 */

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class GrepResultData(
    val searchPath: String,
    val pattern: String,
    val matches: List<FileMatch>,
    val totalMatches: Int,
    val filesSearched: Int,
    @EncodeDefault
    val env: String = "android"
) : ToolResultData() {
    
    @Serializable
    data class FileMatch(
        val filePath: String,
        val lineMatches: List<LineMatch>
    )
    
    @Serializable
    data class LineMatch(
        val lineNumber: Int,
        val lineContent: String,
        val matchContext: String? = null
    )

    private fun parsePreNumberedLineNumber(line: String): Int? {
        val trimmed = line.trimStart()
        val separatorIndex = trimmed.indexOf('|')
        if (separatorIndex <= 0) return null
        return trimmed.substring(0, separatorIndex).trim().toIntOrNull()
    }

    private fun markPreNumberedContextLine(line: String): String {
        val separatorIndex = line.indexOf('|')
        if (separatorIndex < 0) return line
        if (separatorIndex + 1 < line.length && line[separatorIndex + 1] == '>') return line
        return buildString(line.length + 1) {
            append(line, 0, separatorIndex + 1)
            append('>')
            append(line.substring(separatorIndex + 1))
        }
    }
    
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("[${env}] Grep Search Result:")
        sb.appendLine("Search Path: ${searchPath}")
        sb.appendLine("Pattern: ${pattern}")
        sb.appendLine("Total Matches: ${totalMatches} (in ${matches.size} files)")
        sb.appendLine("Files Searched: ${filesSearched}")
        sb.appendLine()

        if (matches.isEmpty()) {
            sb.appendLine("No matches found")
        } else {
            // Set display limit - show up to 30 match groups
            val maxDisplayMatches = 30
            var displayedMatches = 0
            var collapsedMatches = 0

            for (fileMatch in matches) {
                val remainingSlots = maxDisplayMatches - displayedMatches
                if (remainingSlots <= 0) {
                    // Count remaining collapsed matches
                    collapsedMatches += fileMatch.lineMatches.size
                    continue
                }

                sb.appendLine("File: ${fileMatch.filePath}")

                val matchesToShow = fileMatch.lineMatches.take(remainingSlots)
                val matchesCollapsedInFile = fileMatch.lineMatches.size - matchesToShow.size

                matchesToShow.forEach { lineMatch ->
                    // If context is available, show full context
                    if (lineMatch.matchContext != null && lineMatch.matchContext.isNotBlank()) {
                        val contextLines = lineMatch.matchContext.lines()
                        val isPreNumberedContext =
                            contextLines.any { it.isNotBlank() } &&
                                contextLines.all { it.isBlank() || parsePreNumberedLineNumber(it) != null }

                        if (isPreNumberedContext) {
                            contextLines.forEach { contextLine ->
                                val renderedLine =
                                    if (parsePreNumberedLineNumber(contextLine) == lineMatch.lineNumber) {
                                        markPreNumberedContextLine(contextLine)
                                    } else {
                                        contextLine
                                    }
                                sb.appendLine(renderedLine)
                            }
                        } else {
                            val centerIndex = contextLines.size / 2

                            contextLines.forEachIndexed { idx, contextLine ->
                                val actualLineNum = lineMatch.lineNumber - centerIndex + idx
                                val lineNumStr = String.format("%6d", actualLineNum)

                                if (idx == centerIndex) {
                                    sb.appendLine("${lineNumStr}|>${contextLine}")
                                } else {
                                    sb.appendLine("${lineNumStr}| ${contextLine}")
                                }
                            }
                        }
                        sb.appendLine() // Add blank line after each match block
                    } else {
                        // No context, show only matching line
                        val lineNumStr = String.format("%6d", lineMatch.lineNumber)
                        sb.appendLine("${lineNumStr}| ${lineMatch.lineContent}")
                    }
                    displayedMatches++
                }

                if (matchesCollapsedInFile > 0) {
                    sb.appendLine("  ... (${matchesCollapsedInFile} more match groups collapsed in this file)")
                    collapsedMatches += matchesCollapsedInFile
                }

                sb.appendLine()
            }

            if (collapsedMatches > 0) {
                sb.appendLine("=" .repeat(60))
                sb.appendLine("To save space, ${collapsedMatches} match groups were collapsed")
                sb.appendLine("Displayed ${displayedMatches} match groups, total ${totalMatches} matches")
            }
        }

        return sb.toString()
    }
}

/** 工作流基本信息结果数�*/

