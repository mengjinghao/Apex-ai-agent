package com.apex.agent.core.multiagent

import android.content.Context
import com.apex.util.AppLogger
import java.io.File

/**
 * Agent 工具和文件系- 参AutoGPT
 * ?Agent 能够访问和管理文。
 */
class FileToolSystem(private val context: Context) {

    companion object {
        private const val TAG = "FileToolSystem"
    }

    data class FileAction(
        val action: FileActionType,
        val filename: String,
        val content: String? = null,
        val path: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class FileActionType {
        READ, WRITE, APPEND, DELETE, LIST, MKDIR
    }

    private val actionsHistory = mutableListOf<FileAction>()

    fun writeFile(filename: String, content: String, path: String? = null): Boolean {
        return try {
            val file = if (path != null) {
                File(path, filename)
            } else {
                File(context.filesDir, filename)
            }

            file.parentFile?.mkdirs()
            file.writeText(content)

            recordAction(FileActionType.WRITE, filename, content, path)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeFile failed", e)
            false
        }
    }

    fun readFile(filename: String, path: String? = null): String? {
        return try {
            val file = if (path != null) {
                File(path, filename)
            } else {
                File(context.filesDir, filename)
            }

            if (file.exists()) {
                val content = file.readText()
                recordAction(FileActionType.READ, filename, path = path)
                content
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "readFile failed", e)
            null
        }
    }

    fun listFiles(path: String? = null): List<String> {
        val dir = if (path != null) {
            File(path)
        } else {
            context.filesDir
        }

        return try {
            dir.listFiles()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "listFiles failed", e)
            emptyList()
        }.also {
            recordAction(FileActionType.LIST, path ?: "root")
        }
    }

    fun deleteFile(filename: String, path: String? = null): Boolean {
        return try {
            val file = if (path != null) {
                File(path, filename)
            } else {
                File(context.filesDir, filename)
            }

            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    recordAction(FileActionType.DELETE, filename, path = path)
                }
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "deleteFile failed", e)
            false
        }
    }

    fun getActionHistory(limit: Int = 20): List<FileAction> {
        return actionsHistory.takeLast(limit)
    }

    private fun recordAction(
        action: FileActionType,
        filename: String,
        content: String? = null,
        path: String? = null
    ) {
        actionsHistory.add(
            FileAction(
                action = action,
                filename = filename,
                content = if (content != null && content.length > 100) content.take(100) else content,
                path = path
            )
        )
    }

    fun searchFiles(keyword: String): List<String> {
        return listFiles().filter { it.contains(keyword, ignoreCase = true) }
    }
}
