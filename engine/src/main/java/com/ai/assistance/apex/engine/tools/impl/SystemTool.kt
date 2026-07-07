package com.ai.assistance.apex.engine.tools.impl

import android.os.Build
import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.successResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class SystemTool : Tool {
    override val name = "system"
    override val description = "System information and operations tool"
    override val category = "system"
    override val parameters = arrayOf("command")
    override val requiresRoot = false

    override fun execute(args: String): ExecutionResult {
        val command = args.trim().lowercase()

        return when {
            command.startsWith("info") -> getSystemInfo()
            command.startsWith("uptime") -> getUptime()
            command.startsWith("mem") -> getMemoryInfo()
            command.startsWith("disk") -> getDiskInfo()
            command.startsWith("cpu") -> getCpuInfo()
            else -> {
                if (command.isNotEmpty()) {
                    executeShellCommand(command)
                } else {
                    errorResult("Unknown command: $command")
                }
            }
        }
    }

    private fun getSystemInfo(): ExecutionResult {
        val info = buildString {
            appendLine("=== System Information ===")
            appendLine("OS Version: ${Build.VERSION.RELEASE}")
            appendLine("API Level: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Bootloader: ${Build.BOOTLOADER}")
            appendLine("Build ID: ${Build.ID}")
            appendLine("Build Fingerprint: ${Build.FINGERPRINT}")
        }

        return ExecutionResult().apply {
            exitCode = 0
            output = info
            success = true
        }
    }

    private fun getUptime(): ExecutionResult {
        val uptimeMs = android.os.SystemClock.uptimeMillis()
        val uptimeSeconds = uptimeMs / 1000

        val days = uptimeSeconds / 86400
        val hours = (uptimeSeconds % 86400) / 3600
        val minutes = (uptimeSeconds % 3600) / 60
        val seconds = uptimeSeconds % 60

        val result = buildString {
            appendLine("=== System Uptime ===")
            appendLine("Uptime: $uptimeMs ms")
            appendLine("Formatted: $days days, $hours hours, $minutes minutes, $seconds seconds")
        }

        return ExecutionResult().apply {
            exitCode = 0
            output = result
            success = true
        }
    }

    private fun getMemoryInfo(): ExecutionResult {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        val result = buildString {
            appendLine("=== Memory Information ===")
            appendLine("Total Memory: ${formatBytes(totalMemory)}")
            appendLine("Used Memory: ${formatBytes(usedMemory)}")
            appendLine("Free Memory: ${formatBytes(freeMemory)}")
            appendLine("Max Memory: ${formatBytes(maxMemory)}")
            appendLine("Available Processors: ${runtime.availableProcessors()}")
        }

        return ExecutionResult().apply {
            exitCode = 0
            output = result
            success = true
        }
    }

    private fun getDiskInfo(): ExecutionResult {
        val result = buildString {
            appendLine("=== Disk Information ===")
            appendLine("Data Directory: ${android.os.Environment.getDataDirectory().absolutePath}")
            appendLine("External Storage: ${android.os.Environment.getExternalStorageDirectory().absolutePath}")
        }

        return ExecutionResult().apply {
            exitCode = 0
            output = result
            success = true
        }
    }

    private fun getCpuInfo(): ExecutionResult {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/cpuinfo")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(10, TimeUnit.SECONDS)

            ExecutionResult().apply {
                exitCode = 0
                this.output = output
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Failed to get CPU info")
        }
    }

    private fun executeShellCommand(command: String): ExecutionResult {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = if (process.waitFor(30, TimeUnit.SECONDS)) process.exitValue() else { process.destroyForcibly(); -1 }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }

            ExecutionResult().apply {
                this.exitCode = exitCode
                this.output = output
                this.error = error
                success = exitCode == 0
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Command execution failed")
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$bytes bytes"
        }
    }

}