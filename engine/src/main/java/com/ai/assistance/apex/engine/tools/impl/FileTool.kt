package com.ai.assistance.apex.engine.tools.impl

import android.content.Context
import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class FileTool(private val context: Context? = null) : Tool {
    override val name = "file"
    override val description = "File operations tool for reading, writing, deleting and listing files"
    override val category = "file"
    override val parameters = arrayOf("operation", "path", "content", "source", "destination")
    override val requiresRoot = false

    /**
     * Validate that the given path is inside one of the allowed base directories
     * (app filesDir, cacheDir, or external storage). Returns the canonical path.
     * Throws SecurityException if the path escapes the allowlist.
     *
     * Security (G-1/G-2/G-5): prevents path-traversal attacks like
     * `/sdcard/../../../etc/passwd` or `../../data/system/...`.
     */
    private fun validatePath(path: String): String {
        val file = File(path).canonicalFile
        val allowedBases = allowedBasePaths()
        val allowed = allowedBases.any { base ->
            // Compare with trailing separator so "/sdcardFoo" is not allowed under "/sdcard".
            val baseWithSep = if (base.endsWith(File.separator)) base else base + File.separator
            file.path == base || file.path.startsWith(baseWithSep)
        }
        if (!allowed) {
            throw SecurityException(
                "Path '$path' (canonical='${file.path}') is outside allowed directories: $allowedBases"
            )
        }
        return file.path
    }

    /** Returns the list of allowed canonical base paths (also used by the delete-root guard). */
    private fun allowedBasePaths(): List<String> {
        val external = runCatching {
            android.os.Environment.getExternalStorageDirectory().canonicalPath
        }.getOrNull()
        return if (context != null) {
            listOfNotNull(
                runCatching { context.filesDir.canonicalPath }.getOrNull(),
                runCatching { context.cacheDir.canonicalPath }.getOrNull(),
                external
            )
        } else {
            listOfNotNull(external)
        }
    }

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

        val canonicalPath = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return errorResult(e.message ?: "Path not allowed")
        }

        return try {
            val file = File(canonicalPath)
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

        val canonicalPath = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return errorResult(e.message ?: "Path not allowed")
        }

        return try {
            val file = File(canonicalPath)
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

        val canonicalPath = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return errorResult(e.message ?: "Path not allowed")
        }

        return try {
            val file = File(canonicalPath)

            // G-2: never allow deleting an allowed base directory itself
            // (e.g. /sdcard/, app filesDir). Prevents `rm -rf /sdcard/` style accidents.
            val bases = allowedBasePaths()
            if (file.isDirectory && bases.any { it == file.path }) {
                return errorResult("Refusing to delete protected base directory: ${file.path}")
            }

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
        val canonicalPath = try {
            validatePath(dirPath)
        } catch (e: SecurityException) {
            return errorResult(e.message ?: "Path not allowed")
        }

        return try {
            val dir = File(canonicalPath)
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

        // G-1: do not leak existence of paths outside the allowlist.
        val exists = try {
            val canonicalPath = validatePath(path)
            File(canonicalPath).exists()
        } catch (e: SecurityException) {
            false
        }
        return ExecutionResult().apply {
            exitCode = 0
            output = exists.toString()
            success = true
        }
    }

    private fun copyFile(source: String?, destination: String?): ExecutionResult {
        if (source.isNullOrEmpty()) return errorResult("Source is required")
        if (destination.isNullOrEmpty()) return errorResult("Destination is required")

        val canonicalSrc = try {
            validatePath(source)
        } catch (e: SecurityException) {
            return errorResult("Source: ${e.message ?: "not allowed"}")
        }
        val canonicalDest = try {
            validatePath(destination)
        } catch (e: SecurityException) {
            return errorResult("Destination: ${e.message ?: "not allowed"}")
        }

        return try {
            val srcFile = File(canonicalSrc)
            val destFile = File(canonicalDest)

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

        val canonicalPath = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return errorResult(e.message ?: "Path not allowed")
        }

        return try {
            val dir = File(canonicalPath)
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
