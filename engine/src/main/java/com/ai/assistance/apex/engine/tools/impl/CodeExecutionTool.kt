package com.ai.assistance.apex.engine.tools.impl

import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

class CodeExecutionTool : Tool {
    override val name = "code"
    override val description = "Code execution tool for Python, JavaScript and Bash"
    override val category = "code"
    override val parameters = arrayOf("language", "code", "script")
    override val requiresRoot = false

    override fun execute(args: String): ExecutionResult {
        val params = parseArgs(args)
        val language = params["language"]?.lowercase() ?: return errorResult("Language not specified")
        val code = params["code"] ?: params["script"] ?: return errorResult("Code not specified")

        return when (language) {
            "python", "python3", "py" -> executePython(code)
            "javascript", "js", "node" -> executeJavaScript(code)
            "bash", "shell", "sh" -> executeBash(code)
            else -> errorResult("Unsupported language: $language")
        }
    }

    private fun executePython(code: String) = executeWithTempFile(code, ".py", "python3")
    private fun executeJavaScript(code: String) = executeWithTempFile(code, ".js", "node")
    private fun executeBash(code: String) = executeWithTempFile(code, ".sh", "/bin/bash", setExecutable = true)

    private fun executeWithTempFile(code: String, extension: String, command: String, setExecutable: Boolean = false): ExecutionResult {
        return try {
            val tempFile = File.createTempFile("script_${UUID.randomUUID()}", extension)
            tempFile.writeText(code)
            if (setExecutable) tempFile.setExecutable(true)
            tempFile.deleteOnExit()

            val startTime = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec(arrayOf(command, tempFile.absolutePath))
            val exitCode = if (process.waitFor(30, TimeUnit.SECONDS)) 0 else { process.destroyForcibly(); -1 }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val executionTime = System.currentTimeMillis() - startTime

            tempFile.delete()

            ExecutionResult().apply {
                this.exitCode = exitCode
                this.output = output
                this.error = error
                this.executionTime = executionTime
                success = exitCode == 0
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Execution failed")
        }
    }
}