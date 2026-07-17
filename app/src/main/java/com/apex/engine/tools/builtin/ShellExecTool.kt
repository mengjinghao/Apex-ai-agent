package com.apex.engine.tools.builtin

import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCall
import com.apex.core.model.ToolCategory
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolParameter
import com.apex.core.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shell 命令执行工具 — 让 AI 能执行系统命令。
 *
 * 通过 Runtime.exec 在应用进程内执行（无 root），适合 `ls` / `cat` / `getprop` 等只读命令。
 * 输出限制 8KB；超时 10s 自动销毁进程。
 *
 * 安全：该工具标记为非只读 + 需要 SHELL 权限，由 ToolExecutor 在权限层把关。
 */
class ShellExecTool : ApexTool {

    override val metadata = ToolMetadata(
        id = "shell_exec",
        name = "执行命令",
        description = "在设备上执行一条 Shell 命令并返回输出（stdout + stderr，前 8KB）。超时 10 秒。",
        category = ToolCategory.SHELL,
        isReadOnly = false,
        requiredPermissions = setOf("SHELL"),
        parameters = listOf(
            ToolParameter("command", "string", "要执行的命令（不支持交互式输入）", required = true)
        )
    )

    override suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        val command = parseArg(call.arguments, "command")
        if (command.isNullOrBlank()) return@withContext ToolResult.Error("缺少参数: command")

        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val done = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                process.destroyForcibly()
                return@withContext ToolResult.Error("命令执行超时（>10s）")
            }
            val exit = process.exitValue()
            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) append("\n[stderr]\n").append(stderr)
            }
            val truncated = if (combined.length > MAX_LEN) combined.substring(0, MAX_LEN) + "\n...(已截断)" else combined
            ToolResult.Success(
                "exit=$exit\n$truncated",
                mapOf("exit" to exit.toString())
            )
        } catch (e: Exception) {
            ToolResult.Error("执行失败: ${e.message}", e)
        } finally {
            process?.destroy()
        }
    }

    private fun parseArg(argsJson: String, key: String): String? = try {
        org.json.JSONObject(argsJson).optString(key).ifBlank { null }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_LEN = 8192
    }
}
