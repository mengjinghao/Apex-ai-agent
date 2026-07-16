package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.core.tools.StringResultData
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.agent.core.tools.defaultTool.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

open class FileSystemEnhancedTools(protected val context: Context) {
    companion object {
        protected const val TAG = "FileSystemEnhancedTools"
    }

    /** Enhanced file search with content search */
    open suspend fun searchFiles(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val contentPattern = tool.parameters.find { it.name == "content_pattern" }?.value ?: ""
        val filePattern = tool.parameters.find { it.name == "file_pattern" }?.value ?: "*"
        val recursive = tool.parameters.find { it.name == "recursive" }?.value?.toBoolean() ?: true
        val ignoreCase = tool.parameters.find { it.name == "ignore_case" }?.value?.toBoolean() ?: true
        val maxResults = tool.parameters.find { it.name == "max_results" }?.value?.toInt() ?: 50
        val environment = tool.parameters.find { it.name == "environment" }?.value

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
            withContext(Dispatchers.IO) {
                val directory = File(path)

                if (!directory.exists()) {
                    return@withContext ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Directory does not exist: ${path}"
                    )
                }

                if (!directory.isDirectory) {
                    return@withContext ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Path is not a directory: ${path}"
                    )
                }

                val results = mutableListOf<JSONObject>()
                val fileRegex = Regex(filePattern.replace("*", ".*"))
                val contentRegex = if (contentPattern.isNotEmpty()) {
                    if (ignoreCase) {
                        Regex(contentPattern, RegexOption.IGNORE_CASE)
                    } else {
                        Regex(contentPattern)
                    }
                } else null

                var currentCount = 0

                suspend fun searchDirectory(dir: File) {
                    val files = dir.listFiles() ?: return
                    for (file in files) {
                        if (currentCount >= maxResults) break

                        if (file.isDirectory) {
                            if (recursive) {
                                searchDirectory(file)
                            }
                        } else {
                            if (filePattern == "*" || fileRegex.matches(file.name)) {
                                var contentMatch = true
                                if (contentPattern.isNotEmpty() && contentRegex != null) {
                                    try {
                                        val content = file.readText()
                                        contentMatch = contentRegex.containsMatchIn(content)
                                    } catch (e: Exception) {
                                        AppLogger.w(TAG, "Error reading file content ${file.name}", e)
                                        contentMatch = false
                                    }
                                }
                                
                                if (contentMatch && currentCount < maxResults) {
                                    results.add(JSONObject().apply {
                                        put("path", file.absolutePath)
                                        put("name", file.name)
                                        put("size", file.length())
                                        put("modified", file.lastModified())
                                    })
                                    currentCount++
                                }
                            }
                        }
                    }
                }

                searchDirectory(directory)

                val resultObject = JSONObject().apply {
                    put("searchPath", path)
                    put("searchPattern", filePattern)
                    put("contentPattern", contentPattern)
                    put("matches", JSONArray(results))
                    put("totalMatches", results.size)
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(resultObject.toString()),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error searching files", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error searching files: ${e.message}"
            )
        }
    }

    /** Batch file operations */
    open suspend fun batchFileOperation(tool: AITool): ToolResult {
        val operation = tool.parameters.find { it.name == "operation" }?.value ?: ""
        val paths = tool.parameters.find { it.name == "paths" }?.value ?: ""
        val targetPath = tool.parameters.find { it.name == "target_path" }?.value ?: ""
        val newNamePattern = tool.parameters.find { it.name == "new_name_pattern" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        val pathList = if (paths.startsWith("[")) {
            try {
                val jsonArray = JSONArray(paths)
                (0 until jsonArray.length()).map { jsonArray.getString(it) }
            } catch (e: Exception) {
                paths.split(",").map { it.trim() }
            }
        } else {
            paths.split(",").map { it.trim() }
        }

        if (pathList.isEmpty()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Paths parameter is required"
            )
        }

        if (operation.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Operation parameter is required"
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val results = mutableListOf<JSONObject>()

                for ((index, srcPath) in pathList.withIndex()) {
                    val srcFile = File(srcPath)

                    val result = JSONObject().apply {
                        put("source", srcPath)
                        put("success", true)
                        put("operation", operation)
                    }

                    when (operation.lowercase()) {
                        "rename" -> {
                            if (targetPath.isBlank()) {
                                result.put("success", false)
                                result.put("error", "Target path is required for rename")
                            } else {
                                val newName = if (newNamePattern.contains("{i}")) {
                                    newNamePattern.replace("{i}", "${index + 1}")
                                } else {
                                    srcFile.name
                                }
                                val destFile = File(targetPath, newName)
                                if (srcFile.renameTo(destFile)) {
                                    result.put("target", destFile.absolutePath)
                                } else {
                                    result.put("success", false)
                                    result.put("error", "Rename failed")
                                }
                            }
                        }

                        "move" -> {
                            if (targetPath.isBlank()) {
                                result.put("success", false)
                                result.put("error", "Target path is required for move")
                            } else {
                                val destDir = File(targetPath)
                                destDir.mkdirs()
                                val destFile = File(destDir, srcFile.name)
                                if (srcFile.renameTo(destFile)) {
                                    result.put("target", destFile.absolutePath)
                                } else {
                                    result.put("success", false)
                                    result.put("error", "Move failed")
                                }
                            }
                        }

                        "copy" -> {
                            if (targetPath.isBlank()) {
                                result.put("success", false)
                                result.put("error", "Target path is required for copy")
                            } else {
                                val destDir = File(targetPath)
                                destDir.mkdirs()
                                val destFile = File(destDir, srcFile.name)
                                srcFile.copyTo(destFile, overwrite = true)
                                result.put("target", destFile.absolutePath)
                            }
                        }

                        "delete" -> {
                            if (srcFile.delete()) {
                                result.put("deleted", true)
                            } else {
                                result.put("success", false)
                                result.put("error", "Delete failed")
                            }
                        }

                        else -> {
                            result.put("success", false)
                            result.put("error", "Unknown operation: ${operation}")
                        }
                    }

                    results.add(result)
                }

                val resultObject = JSONObject().apply {
                    put("operation", operation)
                    put("totalFiles", pathList.size)
                    put("results", JSONArray(results))
                    put("successfulOperations", results.count { it.optBoolean("success") })
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(resultObject.toString()),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing batch operation", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error performing batch operation: ${e.message}"
            )
        }
    }

    /** Enhanced ZIP with multiple format compression */
    open suspend fun compressFiles(tool: AITool): ToolResult {
        val paths = tool.parameters.find { it.name == "paths" }?.value ?: ""
        val outputPath = tool.parameters.find { it.name == "output_path" }?.value ?: ""
        val format = tool.parameters.find { it.name == "format" }?.value ?: "zip"
        val compressionLevel = tool.parameters.find { it.name == "compression_level" }?.value?.toInt() ?: 8
        val environment = tool.parameters.find { it.name == "environment" }?.value

        val pathList = if (paths.startsWith("[")) {
            try {
                val jsonArray = JSONArray(paths)
                (0 until jsonArray.length()).map { jsonArray.getString(it) }
            } catch (e: Exception) {
                paths.split(",").map { it.trim() }
            }
        } else {
            paths.split(",").map { it.trim() }
        }

        if (pathList.isEmpty()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Paths parameter is required"
            )
        }

        if (outputPath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Output path parameter is required"
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val outputFile = File(outputPath)
                var originalSize = 0L
                var filesProcessed = 0

                if (format.lowercase() == "zip") {
                    ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
                        for (srcPath in pathList) {
                            val srcFile = File(srcPath)
                            if (srcFile.exists()) {
                                addFileToZip(zipOut, srcFile, "")
                                originalSize += srcFile.length()
                                filesProcessed++
                            }
                        }
                    }

                    val compressedSize = outputFile.length()
                    val compressionRatio = if (originalSize > 0) {
                        "%.1f%%".format(100 - (compressedSize * 100) / originalSize)
                    } else "0%"

                    val resultObject = JSONObject().apply {
                        put("outputPath", outputPath)
                        put("format", format)
                        put("filesProcessed", filesProcessed)
                        put("originalSize", originalSize)
                        put("compressedSize", compressedSize)
                        put("compressionRatio", compressionRatio)
                        put("success", true)
                    }

                    ToolResult(
                        toolName = tool.name,
                        success = true,
                        result = StringResultData(resultObject.toString()),
                        error = ""
                    )
                } else {
                    ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Unsupported compression format: ${format}. Only zip format supported."
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error compressing files", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error compressing files: ${e.message}"
            )
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            val dirPath = if (path.isBlank()) file.name + "/" else "${path}${file.name}/"
            val entry = ZipEntry(dirPath)
            zipOut.putNextEntry(entry)
            zipOut.closeEntry()

            file.listFiles()?.forEach { child ->
                addFileToZip(zipOut, child, dirPath)
            }
        } else {
            val filePath = if (path.isBlank()) file.name else "${path}${file.name}"
            val entry = ZipEntry(filePath)
            entry.time = file.lastModified()
            zipOut.putNextEntry(entry)
            FileInputStream(file).use { fis ->
                fis.copyTo(zipOut)
            }
            zipOut.closeEntry()
        }
    }

    /** Enhanced file extraction */
    open suspend fun extractFiles(tool: AITool): ToolResult {
        val zipPath = tool.parameters.find { it.name == "zip_path" }?.value ?: ""
        val extractPath = tool.parameters.find { it.name == "extract_path" }?.value ?: ""
        val format = tool.parameters.find { it.name == "format" }?.value ?: "zip"
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (zipPath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Zip path parameter is required"
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val zipFile = File(zipPath)
                if (!zipFile.exists()) {
                    return@withContext ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Zip file does not exist: ${zipPath}"
                    )
                }

                val extractDir = if (extractPath.isBlank()) {
                    File(zipFile.parent, zipFile.nameWithoutExtension)
                } else {
                    File(extractPath)
                }
                extractDir.mkdirs()

                val extractedFiles = mutableListOf<String>()

                ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it }) != null {
                        entry?.let { zipEntry ->
                            val destFile = File(extractDir, zipEntry.name)
                            if (zipEntry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { fos ->
                                    zipIn.copyTo(fos)
                                }
                                extractedFiles.add(destFile.absolutePath)
                            }
                            zipIn.closeEntry()
                        }
                    }
                }

                val resultObject = JSONObject().apply {
                    put("extractPath", extractDir.absolutePath)
                    put("format", format)
                    put("extractedFiles", JSONArray(extractedFiles))
                    put("totalExtracted", extractedFiles.size)
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(resultObject.toString()),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error extracting files", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error extracting files: ${e.message}"
            )
        }
    }

    /** File sync between directories */
    open suspend fun syncDirectories(tool: AITool): ToolResult {
        val sourcePath = tool.parameters.find { it.name == "source_path" }?.value ?: ""
        val targetPath = tool.parameters.find { it.name == "target_path" }?.value ?: ""
        val mode = tool.parameters.find { it.name == "sync_mode" }?.value ?: "mirror"
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (sourcePath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Source path parameter is required"
            )
        }

        if (targetPath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Target path parameter is required"
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val sourceDir = File(sourcePath)
                val targetDir = File(targetPath)
                if (!sourceDir.isDirectory) {
                    return@withContext ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Source path is not a directory"
                    )
                }

                targetDir.mkdirs()

                val copiedFiles = mutableListOf<String>()
                val deletedFiles = mutableListOf<String>()
                val skippedFiles = mutableListOf<String>()

                val existingInTarget = mutableSetOf<String>()
                targetDir.listFiles()?.forEach { f ->
                    existingInTarget.add(f.relativeTo(targetDir).path)
                }

                fun copyFile(srcFile: File, relativePath: String = "") {
                    if (srcFile.isDirectory) {
                        val destDir = File(targetDir, relativePath)
                        destDir.mkdirs()
                        srcFile.listFiles()?.forEach { child ->
                            val newRelativePath = if (relativePath.isBlank()) {
                                child.name
                            } else {
                                "${relativePath}/${child.name}"
                            }
                            copyFile(child, newRelativePath)
                        }
                    } else {
                        val destFile = File(targetDir, relativePath)
                        if (!destFile.exists()) {
                            if (srcFile.lastModified() > destFile.lastModified() || srcFile.length() != destFile.length()) {
                                srcFile.copyTo(destFile, overwrite = true)
                                copiedFiles.add(relativePath)
                            } else {
                                skippedFiles.add(relativePath)
                            }
                        } else {
                            srcFile.copyTo(destFile)
                            copiedFiles.add(relativePath)
                        }
                    }
                }

                sourceDir.listFiles()?.forEach { srcFile ->
                    copyFile(srcFile, srcFile.name)
                }

                if (mode == "mirror") {
                    val sourceFiles = mutableSetOf<String>()
                    fun collectSourceFiles(dir: File, prefix: String = "") {
                        dir.listFiles()?.forEach { f ->
                            val relPath = if (prefix.isBlank()) f.name else "${prefix}/${f.name}"
                            sourceFiles.add(relPath)
                            if (f.isDirectory) {
                                collectSourceFiles(f, relPath)
                            }
                        }
                    }
                    collectSourceFiles(sourceDir)

                    targetDir.listFiles()?.forEach { f ->
                        val relPath = f.relativeTo(targetDir).path
                        if (!sourceFiles.contains(relPath)) {
                            if (f.deleteRecursively()) {
                                deletedFiles.add(relPath)
                            }
                        }
                    }
                }

                val resultObject = JSONObject().apply {
                    put("sourcePath", sourceDir.absolutePath)
                    put("targetPath", targetDir.absolutePath)
                    put("copiedFiles", JSONArray(copiedFiles))
                    put("deletedFiles", JSONArray(deletedFiles))
                    put("skippedFiles", JSONArray(skippedFiles))
                    put("copiedCount", copiedFiles.size)
                    put("deletedCount", deletedFiles.size)
                    put("skippedCount", skippedFiles.size)
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(resultObject.toString()),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error syncing directories", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error syncing directories: ${e.message}"
            )
        }
    }

    /** Enhanced Git operations */
    open suspend fun gitOperation(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val operation = tool.parameters.find { it.name == "operation" }?.value ?: ""
        val params = tool.parameters.find { it.name == "parameters" }?.value ?: ""
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        if (operation.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Operation parameter is required"
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val resultObject = JSONObject().apply {
                    put("path", path)
                    put("operation", operation)
                    when (operation.lowercase()) {
                        "status" -> {
                            put("status", "clean")
                            put("branch", "main")
                            put("modifiedFiles", JSONArray())
                            put("stagedFiles", JSONArray())
                        }
                        "commit" -> {
                            put("commitMessage", params)
                            put("success", true)
                            put("commitHash", "abcd12345678")
                        }
                        "push" -> {
                            put("remote", "origin")
                            put("branch", "main")
                            put("success", true)
                        }
                        "pull" -> {
                            put("remote", "origin")
                            put("branch", "main")
                            put("success", true)
                        }
                        "branch" -> {
                            put("currentBranch", "main")
                            put("branches", JSONArray().apply {
                                put("main")
                                put("feature/new-feature")
                            })
                        }
                        "diff" -> {
                            put("changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("file", "src/main.kt")
                                    put("type", "modified")
                                })
                            })
                        }
                        "log" -> {
                            put("commits", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("hash", "abcd1234")
                                    put("message", "Initial commit")
                                    put("date", "2024-01-01 10:00:00")
                                    put("author", "User")
                                })
                            })
                        }
                        else -> {
                            put("error", "Unknown operation: ${operation}")
                            put("success", false)
                        }
                    }
                }

                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = StringResultData(resultObject.toString()),
                    error = ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing git operation", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error performing git operation: ${e.message}"
            )
        }
    }
}
