package com.apex.engine.tools.builtin

import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCall
import com.apex.core.model.ToolCategory
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolParameter
import com.apex.core.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件读取工具 — 让 AI 能读取设备上的文本文件。
 *
 * 限制：仅读取文本，单次最多 8KB，避免上下文爆炸。
 * 路径需为绝对路径；Android 11+ 受分区存储限制，可能需要用户授权。
 */
class FileReadTool : ApexTool {

    override val metadata = ToolMetadata(
        id = "file_read",
        name = "读取文件",
        description = "读取设备上指定路径的文本文件内容（前 8KB）。仅支持文本文件。",
        category = ToolCategory.FILE_SYSTEM,
        isReadOnly = true,
        requiredPermissions = setOf("READ_STORAGE"),
        parameters = listOf(
            ToolParameter("path", "string", "文件的绝对路径", required = true)
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        val path = parseArg(call.arguments, "path")
        if (path.isNullOrBlank()) return@withContext ToolResult.Error("缺少参数: path")

        val file = File(path)
        if (!file.exists()) return@withContext ToolResult.Error("文件不存在: $path")
        if (!file.isFile) return@withContext ToolResult.Error("路径不是文件: $path")
        if (file.length() > MAX_BYTES * 4L && false) { /* 仅做软提示，不强制拦截 */ }

        try {
            val text = file.readText(Charsets.UTF_8)
            val truncated = if (text.length > MAX_LEN) text.substring(0, MAX_LEN) + "\n...(已截断)" else text
            ToolResult.Success(truncated, mapOf("size" to file.length().toString()))
        } catch (e: Exception) {
            ToolResult.Error("读取失败: ${e.message}", e)
        }
    }

    private fun parseArg(argsJson: String, key: String): String? = try {
        org.json.JSONObject(argsJson).optString(key).ifBlank { null }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_LEN = 8192
        private const val MAX_BYTES = 8192
    }
}
