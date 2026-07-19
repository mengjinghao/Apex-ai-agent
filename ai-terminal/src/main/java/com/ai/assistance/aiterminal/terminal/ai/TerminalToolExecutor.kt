package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class TerminalToolExecutor(private val context: Context) {
    private val terminalManager by lazy {
        TerminalManager.getInstance(context)
    }

    /**
     * Security (G-5): path allowlist for the read_file / write_file tools.
     * Resolves the path canonically and verifies it lives under one of the
     * allowed base directories (app filesDir, app cacheDir, or external storage).
     * Returns true if the path is allowed, false otherwise.
     *
     * This prevents the AI from reading/writing arbitrary files (e.g. /etc/passwd,
     * /data/system/..., sibling app private dirs).
     */
    private fun isPathAllowed(path: String): Boolean {
        return try {
            val canonical = File(path).canonicalPath
            val allowedBases = listOfNotNull(
                runCatching { context.filesDir.canonicalPath }.getOrNull(),
                runCatching { context.cacheDir.canonicalPath }.getOrNull(),
                runCatching {
                    android.os.Environment.getExternalStorageDirectory().canonicalPath
                }.getOrNull()
            )
            allowedBases.any { base ->
                val baseWithSep = if (base.endsWith(File.separator)) base else base + File.separator
                canonical == base || canonical.startsWith(baseWithSep)
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            when (toolName) {
                "terminal_execute_command" -> executeCommand(parameters)
                "terminal_list_files" -> listFiles(parameters)
                "terminal_search_files" -> searchFiles(parameters)
                "terminal_read_file" -> readFile(parameters)
                "terminal_write_file" -> writeFile(parameters)
                "terminal_get_system_info" -> getSystemInfo(parameters)
                "terminal_list_apps" -> listApps(parameters)
                "terminal_get_app_info" -> getAppInfo(parameters)
                "terminal_kill_process" -> killProcess(parameters)
                "terminal_list_processes" -> listProcesses(parameters)
                "terminal_check_root" -> checkRoot()
                "terminal_get_network_info" -> getNetworkInfo(parameters)
                else -> ToolExecutionResult(
                    success = false,
                    result = "",
                    error = "Unknown tool: $toolName"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                result = "",
                error = "Error executing tool $toolName: ${e.message}"
            )
        }
    }

    private suspend fun executeCommand(parameters: Map<String, Any>): ToolExecutionResult {
        val command = parameters["command"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: command"
        )

        // Security (E-2): assess command risk before execution. Block CRITICAL/HIGH
        // commands using the thorough ai.DangerousCommandPatterns library (25+ patterns).
        val matchedPattern = DangerousCommandPatterns.matchPattern(command)
        if (matchedPattern != null && (matchedPattern.riskLevel == RiskLevel.CRITICAL || matchedPattern.riskLevel == RiskLevel.HIGH)) {
            return ToolExecutionResult(
                success = false,
                result = "",
                error = "Command blocked by risk assessor (level=" + matchedPattern.riskLevel + "): " + matchedPattern.description
            )
        }

        val requireRoot = parameters["require_root"] as? Boolean ?: false

        val sessionId = java.util.UUID.randomUUID().toString()
        terminalManager.createSession(sessionId)

        return try {
            val finalCommand = if (requireRoot) {
                "su -c '${escapeShellCommand(command)}'"
            } else {
                command
            }

            val success = terminalManager.executeCommand(sessionId, finalCommand)
            ToolExecutionResult(
                success = success,
                result = if (success) "ok" else ""
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                result = "",
                error = "Error executing command: ${e.message}"
            )
        } finally {
            terminalManager.closeSession(sessionId)
        }
    }

    private fun escapeShellCommand(command: String): String {
        return command.replace("'", "'\\''")
    }

    private suspend fun listFiles(parameters: Map<String, Any>): ToolExecutionResult {
        val path = parameters["path"] as? String ?: "."
        val longFormat = parameters["long_format"] as? Boolean ?: true
        val includeHidden = parameters["include_hidden"] as? Boolean ?: true

        val flags = mutableListOf<String>()
        if (longFormat) flags.add("-l")
        if (includeHidden) flags.add("-a")

        val command = "ls ${flags.joinToString(" ")} \"$path\""
        return executeCommand(mapOf("command" to command))
    }

    private suspend fun searchFiles(parameters: Map<String, Any>): ToolExecutionResult {
        val pattern = parameters["pattern"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: pattern"
        )

        val path = parameters["path"] as? String ?: "."
        val type = parameters["type"] as? String ?: ""

        val typeOption = if (type.isNotEmpty()) "-type $type" else ""
        val command = "find \"$path\" $typeOption -name \"$pattern\""
        return executeCommand(mapOf("command" to command))
    }

    private suspend fun readFile(parameters: Map<String, Any>): ToolExecutionResult {
        val filePath = parameters["file_path"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: file_path"
        )

        // Security (G-5): path allowlist. Reject reads outside app/storage dirs.
        if (!isPathAllowed(filePath)) {
            return ToolExecutionResult(
                success = false,
                result = "",
                error = "Blocked: file path '$filePath' is outside allowed directories (app filesDir/cacheDir or external storage)"
            )
        }

        val maxLines = (parameters["max_lines"] as? Number ?: 1000).toInt()
        val command = "head -n $maxLines \"$filePath\""
        return executeCommand(mapOf("command" to command))
    }

    private suspend fun writeFile(parameters: Map<String, Any>): ToolExecutionResult {
        val filePath = parameters["file_path"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: file_path"
        )

        // Security (G-5): path allowlist. Reject writes outside app/storage dirs.
        if (!isPathAllowed(filePath)) {
            return ToolExecutionResult(
                success = false,
                result = "",
                error = "Blocked: file path '$filePath' is outside allowed directories (app filesDir/cacheDir or external storage)"
            )
        }

        val content = parameters["content"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: content"
        )

        val append = parameters["append"] as? Boolean ?: false
        val operator = if (append) ">>" else ">"
        
        val escapedContent = escapeShellContent(content)
        val escapedFilePath = escapeShellContent(filePath)
        val command = "printf '%s' '$escapedContent' $operator '$escapedFilePath'"
        return executeCommand(mapOf("command" to command))
    }

    private fun escapeShellContent(content: String): String {
        return content.replace("'", "'\\''")
    }

    private suspend fun getSystemInfo(parameters: Map<String, Any>): ToolExecutionResult {
        val infoType = parameters["info_type"] as? String ?: "all"
        
        val commands = when (infoType) {
            "version" -> listOf(
                "getprop ro.build.version.release",
                "getprop ro.build.version.sdk",
                "getprop ro.build.id"
            )
            "hardware" -> listOf(
                "cat /proc/cpuinfo",
                "getprop ro.product.model",
                "getprop ro.product.device"
            )
            "memory" -> listOf(
                "cat /proc/meminfo",
                "free -h"
            )
            "storage" -> listOf(
                "df -h"
            )
            else -> listOf(
                "getprop ro.build.version.release",
                "getprop ro.product.model",
                "cat /proc/cpuinfo | grep Processor",
                "cat /proc/meminfo | head -5",
                "df -h"
            )
        }

        val results = mutableListOf<String>()
        for (command in commands) {
            val result = executeCommand(mapOf("command" to command))
            if (result.success) {
                results.add("$command:\n${result.result}")
            } else {
                results.add("$command: Error - ${result.error}")
            }
        }

        return ToolExecutionResult(
            success = true,
            result = results.joinToString("\n\n")
        )
    }

    private suspend fun listApps(parameters: Map<String, Any>): ToolExecutionResult {
        val type = parameters["type"] as? String ?: "all"
        val filter = parameters["filter"] as? String ?: ""

        val command = when (type) {
            "system" -> "pm list packages -s"
            "user" -> "pm list packages -3"
            else -> "pm list packages"
        }

        val result = executeCommand(mapOf("command" to command))
        if (!result.success) return result

        val packages = result.result.lines().filter { it.isNotBlank() }
        val filtered = if (filter.isNotEmpty()) {
            packages.filter { it.contains(filter) }
        } else {
            packages
        }

        return ToolExecutionResult(
            success = true,
            result = filtered.joinToString("\n")
        )
    }

    private suspend fun getAppInfo(parameters: Map<String, Any>): ToolExecutionResult {
        val packageName = parameters["package_name"] as? String ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: package_name"
        )

        val command = "dumpsys package $packageName"
        return executeCommand(mapOf("command" to command))
    }

    private suspend fun killProcess(parameters: Map<String, Any>): ToolExecutionResult {
        val pid = (parameters["pid"] as? Number ?: return ToolExecutionResult(
            success = false,
            result = "",
            error = "Missing required parameter: pid"
        )).toInt()

        val signal = (parameters["signal"] as? Number ?: 9).toInt()
        val command = "kill -$signal $pid"
        return executeCommand(mapOf("command" to command))
    }

    private suspend fun listProcesses(parameters: Map<String, Any>): ToolExecutionResult {
        val sortBy = parameters["sort_by"] as? String ?: "none"
        val limit = (parameters["limit"] as? Number ?: 20).toInt()

        val command = when (sortBy) {
            "cpu" -> "ps -A -o pid,user,pcpu,pmem,cmd | sort -k 3 -r | head -$limit"
            "mem" -> "ps -A -o pid,user,pcpu,pmem,cmd | sort -k 4 -r | head -$limit"
            else -> "ps -A -o pid,user,pcpu,pmem,cmd | head -$limit"
        }

        return executeCommand(mapOf("command" to command))
    }

    private suspend fun checkRoot(): ToolExecutionResult {
        val command = "su -c \"id\""
        val result = executeCommand(mapOf("command" to command))
        
        if (result.success && result.result.contains("uid=0")) {
            return ToolExecutionResult(
                success = true,
                result = "Root access is available"
            )
        } else {
            return ToolExecutionResult(
                success = true,
                result = "Root access is not available"
            )
        }
    }

    private suspend fun getNetworkInfo(parameters: Map<String, Any>): ToolExecutionResult {
        val detailLevel = parameters["detail_level"] as? String ?: "basic"
        
        val commands = when (detailLevel) {
            "detailed" -> listOf(
                "ifconfig",
                "ip addr",
                "ip route",
                "ping -c 3 8.8.8.8"
            )
            else -> listOf(
                "ifconfig | grep -E 'inet|wlan|eth'",
                "ping -c 2 8.8.8.8"
            )
        }

        val results = mutableListOf<String>()
        for (command in commands) {
            val result = executeCommand(mapOf("command" to command))
            if (result.success) {
                results.add("$command:\n${result.result}")
            } else {
                results.add("$command: Error - ${result.error}")
            }
        }

        return ToolExecutionResult(
            success = true,
            result = results.joinToString("\n\n")
        )
    }

    suspend fun executeToolFromJson(toolName: String, argumentsJson: String): ToolExecutionResult {
        try {
            val json = JSONObject(argumentsJson)
            val params = mutableMapOf<String, Any>()
            val keys = json.keys()
            
            while (keys.hasNext()) {
                val key = keys.next()
                params[key] = json.get(key)
            }
            
            return executeTool(toolName, params)
        } catch (e: Exception) {
            return ToolExecutionResult(
                success = false,
                result = "",
                error = "Invalid JSON arguments: ${e.message}"
            )
        }
    }
}

data class ToolExecutionResult(
    val success: Boolean,
    val result: String,
    val error: String? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("success", success)
        json.put("result", result)
        if (error != null) {
            json.put("error", error)
        }
        return json.toString()
    }
}
