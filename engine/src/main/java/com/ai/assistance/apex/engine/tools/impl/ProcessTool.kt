package com.ai.assistance.apex.engine.tools.impl

import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ProcessTool : Tool {
    override val name = "process"
    override val description = "Process management tool for listing and killing processes"
    override val category = "system"
    override val parameters = arrayOf("operation", "pid", "name")
    override val requiresRoot = false

    override fun execute(args: String): ExecutionResult {
        val params = parseArgs(args)
        val operation = params["operation"]?.lowercase() ?: return errorResult("Operation not specified")

        return when (operation) {
            "list" -> listProcesses(params["name"])
            "kill" -> killProcess(params["pid"])
            "top" -> topProcesses()
            else -> errorResult("Unknown operation: $operation")
        }
    }

    private fun listProcesses(filterName: String?): ExecutionResult {
        return try {
            val process = Runtime.getRuntime().exec("ps -ef" + (if (!filterName.isNullOrEmpty()) " | grep $filterName" else ""))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = if (process.waitFor(10, TimeUnit.SECONDS)) 0 else { process.destroyForcibly(); -1 }

            ExecutionResult().apply {
                this.exitCode = exitCode
                this.output = output
                success = exitCode == 0
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Failed to list processes")
        }
    }

    private fun killProcess(pid: String?): ExecutionResult {
        if (pid.isNullOrEmpty()) return errorResult("PID is required")

        return try {
            val process = Runtime.getRuntime().exec("kill $pid")
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            val exitCode = if (exited) process.exitValue() else { process.destroyForcibly(); -1 }
            val output = if (exitCode == 0) "Process $pid killed" else "Failed to kill process $pid"

            ExecutionResult().apply {
                this.exitCode = exitCode
                this.output = output
                success = exitCode == 0
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Failed to kill process")
        }
    }

    private fun topProcesses(): ExecutionResult {
        return try {
            val process = Runtime.getRuntime().exec("top -b -n 1 | head -20")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = if (process.waitFor(10, TimeUnit.SECONDS)) 0 else { process.destroyForcibly(); -1 }

            ExecutionResult().apply {
                this.exitCode = exitCode
                this.output = output
                success = exitCode == 0
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Failed to get top processes")
        }
    }

}