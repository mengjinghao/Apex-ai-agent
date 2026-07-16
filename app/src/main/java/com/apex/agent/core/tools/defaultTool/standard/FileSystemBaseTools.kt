package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.util.AppLogger
import com.apex.core.tools.DirectoryListingData
import com.apex.core.tools.FileContentData
import com.apex.core.tools.FileApplyResultData
import com.apex.core.tools.BinaryFileContentData
import com.apex.core.tools.FileExistsData
import com.apex.core.tools.FileInfoData
import com.apex.core.tools.FileOperationData
import com.apex.core.tools.FilePartContentData
import com.apex.data.model.AITool
import com.apex.core.tools.ToolResult
import java.io.File
import com.apex.agent.core.util.IOException
import java.text.SimpleDateFormat
import com.apex.agent.core.tools.skill.Date
import java.util.Locale
import com.apex.util.FileUtils
import com.apex.util.SyntaxCheckUtil
import com.apex.util.PathMapper
import com.apex.agent.terminal.provider.filesystem.FileSystemProvider
import com.apex.agent.core.tools.defaultTool.PathValidator
import com.apex.agent.core.tools.defaultTool.debugger.name
import com.apex.core.tools.StringResultData

open class FileSystemBaseTools(protected val context: Context) {
    companion object {
        protected const val TAG = "FileSystemBaseTools"
    }

    // Linux文件系统工具实例
    protected val linuxTools: LinuxFileSystemTools by lazy {
        LinuxFileSystemTools(context)
    }

    private val safTools: SafFileSystemTools by lazy {
        SafFileSystemTools(context, apiPreferences)
    }

    protected fun isSafEnvironment(environment: String): Boolean {
        return environment?.startsWith("repo:", ignoreCase = true) == true
    }

    /** 检查是否是Linux环境 */
    protected fun isLinuxEnvironment(environment: String): Boolean {
        return environment?.lowercase() == "linux"
    }

    /** List files in a directory */
    open suspend fun listFiles(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        // 如果是Linux环境，委托给LinuxFileSystemTools
        if (isLinuxEnvironment(environment)) {
            return linuxTools.listFiles(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.listFiles(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val directory = File(path)

            if (!directory.exists()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Directory does not exist: ${path}"
                )
            }

            if (!directory.isDirectory) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Path is not a directory: ${path}"
                )
            }

            val entries = mutableListOf<DirectoryListingData.FileEntry>()
            val files = directory.listFiles() ?: emptyArray()

            val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)

            for (file in files) {
                if (file.name != "." && file.name != "..") {
                    entries.add(
                        DirectoryListingData.FileEntry(
                            name = file.name,
                            isDirectory = file.isDirectory,
                            size = file.length(),
                            permissions = getFilePermissions(file),
                            lastModified = dateFormat.format(Date(file.lastModified()))
                        )
                    )
                }
            }

            AppLogger.d(TAG, "Listed ${entries.size} entries in directory ${path}")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = DirectoryListingData(path, entries),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error listing directory", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error listing directory: ${e.message}"
            )
        }
    }

    /** Get file permissions as a string like "rwxr-xr-x" */
    protected fun getFilePermissions(file: File): String {
        // Java has limited capabilities for getting Unix-style file permissions
        // This is a simplified version that checks basic permissions
        val canRead = if (file.canRead()) 'r' else '-'
        val canWrite = if (file.canWrite()) 'w' else '-'
        val canExecute = if (file.canExecute()) 'x' else '-'

        // For simplicity, we'll use the same permissions for user, group, and others
        return "${canRead}${canWrite}${canExecute}${canRead}-${canExecute}${canRead}-${canExecute}"
    }

    /** Read file content */
    open suspend fun readFile(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.readFile(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.readFile(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)

            if (!file.exists()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "File does not exist: ${path}"
                )
            }

            if (file.isDirectory) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Path is a directory, not a file: ${path}"
                )
            }

            val content = file.readText()
            AppLogger.d(TAG, "Read file ${path}, size: ${content.length} bytes")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FileContentData(path, content),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading file", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error reading file: ${e.message}"
            )
        }
    }

    /** Write content to a file */
    open suspend fun writeFile(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val content = tool.parameters.find { it.name == "content" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.writeFile(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.writeFile(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)

            // Create parent directory if it doesn't exist
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            file.writeText(content)
            AppLogger.d(TAG, "Wrote file ${path}, size: ${content.length} bytes")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FileOperationData("File written successfully: ${path}"),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error writing file", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error writing file: ${e.message}"
            )
        }
    }

    /** Append content to a file */
    open suspend fun appendFile(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val content = tool.parameters.find { it.name == "content" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.appendFile(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.appendFile(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)

            // Create parent directory if it doesn't exist
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            file.appendText(content)
            AppLogger.d(TAG, "Appended to file ${path}, size: ${content.length} bytes")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FileOperationData("Content appended successfully: ${path}"),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error appending to file", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error appending to file: ${e.message}"
            )
        }
    }

    /** Check if a file exists */
    open suspend fun fileExists(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.fileExists(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.fileExists(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)
            val exists = file.exists()
            AppLogger.d(TAG, "Checked file existence: ${path} -> ${exists}")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FileExistsData(path, exists),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking file existence", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error checking file existence: ${e.message}"
            )
        }
    }

    /** Get file information */
    open suspend fun fileInfo(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.fileInfo(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.fileInfo(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)

            if (!file.exists()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "File does not exist: ${path}"
                )
            }

            val info = FileInfoData(
                path = path,
                exists = true,
                isDirectory = file.isDirectory,
                size = file.length(),
                permissions = getFilePermissions(file),
                lastModified = file.lastModified()
            )

            AppLogger.d(TAG, "Got file info for: ${path}")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = info,
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting file info", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error getting file info: ${e.message}"
            )
        }
    }

    /** Read a part of a file */
    open suspend fun readFilePart(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val startLineStr = tool.parameters.find { it.name == "start_line" }?.value ?: "1"
        val endLineStr = tool.parameters.find { it.name == "end_line" }?.value ?: "100"
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.readFilePart(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.readFilePart(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val file = File(path)

            if (!file.exists()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "File does not exist: ${path}"
                )
            }

            if (file.isDirectory) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Path is a directory, not a file: ${path}"
                )
            }

            val startLine = startLineStr.toIntOrNull() ?: 1
            val endLine = endLineStr.toIntOrNull() ?: 100

            val content = readLinesFromFile(file, startLine - 1, endLine)
            val totalLines = countFileLines(file)

            AppLogger.d(TAG, "Read file part ${path}, lines ${startLine}-${endLine} of ${totalLines}")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FilePartContentData(path, content, startLine, endLine, totalLines),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading file part", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error reading file part: ${e.message}"
            )
        }
    }

    /** 从文件中读取指定范围的行 */
    private fun readLinesFromFile(file: File, startLine: Int, endLine: Int): String {
        val partContent = StringBuilder()
        var currentLine = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach {
                if (currentLine >= endLine) return@useLines
                if (currentLine >= startLine) {
                    partContent.append(it).append('\n')
                }
                currentLine++
            }
        }
        // Remove last newline if content is not empty
        if (partContent.isNotEmpty()) {
            partContent.setLength(partContent.length - 1)
        }
        return partContent.toString()
    }

    /** 统计文件总行?/
    protected fun countFileLines(file: File): Int {
        var totalLines = 0
        file.bufferedReader().use {
            while (it.readLine() != null) {
                totalLines++
            }
        }
        return totalLines
    }
}
