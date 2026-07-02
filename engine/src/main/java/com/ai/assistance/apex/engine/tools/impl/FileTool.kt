package com.ai.assistance.apex.engine.tools.impl

import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class FileTool : Tool {
    override val name = "file"
    override val description = "File operations tool for reading, writing, deleting and listing files"
    override val category = "file"
    override val parameters = arrayOf("operation", "path", "content", "source", "destination")
    override val requiresRoot = false

    override fun execute(args: String): ExecutionResult {
        val params = parseArgs(args)
        val operation = params["operation"]?.lowercase() ?: return errorResult("Operation not specified")

        return when (operation) {
            "read" -> readFile(params["path"])
            "write" -> writeFile(params["path"], params["content"])
            "delete" -> deleteFile(params["path"])
            "list" -> listFiles(params["path"])
            "exists" -> checkExists(params["path"])
            "copy" -> copyFile(params["source"], params["destination"])
            "mkdir" -> makeDir(params["path"])
            else -> errorResult("Unknown operation: $operation")
        }
    }

    private fun readFile(path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) return errorResult("Path is required")

        return try {
            val file = File(path)
            if (!file.exists()) return errorResult("File does not exist: $path")

            val content = file.readText()
            ExecutionResult().apply {
                exitCode = 0
                output = content
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Read failed")
        }
    }

    private fun writeFile(path: String?, content: String?): ExecutionResult {
        if (path.isNullOrEmpty()) return errorResult("Path is required")

        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content ?: "")

            ExecutionResult().apply {
                exitCode = 0
                output = "File written successfully: $path"
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Write failed")
        }
    }

    private fun deleteFile(path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) return errorResult("Path is required")

        return try {
            val file = File(path)
            val deleteSuccess = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }

            ExecutionResult().apply {
                exitCode = if (deleteSuccess) 0 else -1
                output = if (deleteSuccess) "Deleted successfully" else "Delete failed"
                this.success = deleteSuccess
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Delete failed")
        }
    }

    private fun listFiles(path: String?): ExecutionResult {
        val dirPath = path ?: "."
        return try {
            val dir = File(dirPath)
            if (!dir.exists()) return errorResult("Directory does not exist: $dirPath")
            if (!dir.isDirectory) return errorResult("Not a directory: $dirPath")

            val files = dir.listFiles()
            val result = files?.joinToString("\n") {
                val type = if (it.isDirectory) "dir" else "file"
                val size = if (it.isFile) " (${it.length()} bytes)" else ""
                "${it.name} [$type]$size"
            } ?: "Empty directory"

            ExecutionResult().apply {
                exitCode = 0
                output = result
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "List failed")
        }
    }

    private fun checkExists(path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) return errorResult("Path is required")

        val exists = File(path).exists()
        return ExecutionResult().apply {
            exitCode = 0
            output = exists.toString()
            success = true
        }
    }

    private fun copyFile(source: String?, destination: String?): ExecutionResult {
        if (source.isNullOrEmpty()) return errorResult("Source is required")
        if (destination.isNullOrEmpty()) return errorResult("Destination is required")

        return try {
            val srcFile = File(source)
            val destFile = File(destination)

            if (!srcFile.exists()) return errorResult("Source file does not exist")

            destFile.parentFile?.mkdirs()
            srcFile.copyTo(destFile, overwrite = true)

            ExecutionResult().apply {
                exitCode = 0
                output = "Copied $source to $destination"
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Copy failed")
        }
    }

    private fun makeDir(path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) return errorResult("Path is required")

        return try {
            val dir = File(path)
            val mkdirSuccess = dir.mkdirs()

            ExecutionResult().apply {
                exitCode = if (mkdirSuccess) 0 else -1
                output = if (mkdirSuccess) "Directory created: $path" else "Failed to create directory"
                this.success = mkdirSuccess
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "mkdir failed")
        }
    }

}