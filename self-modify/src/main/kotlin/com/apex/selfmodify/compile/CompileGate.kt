package com.apex.selfmodify.compile

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class CompileGate(
    private val projectDir: File,
    private val timeoutMs: Long = 120_000L
) {
    suspend fun compile(module: String): CompileResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            val process = ProcessBuilder("./gradlew", ":$module:compileDebugKotlin", "--no-daemon", "--offline")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            Pair(exitCode, output)
        }
        val duration = System.currentTimeMillis() - start
        if (result == null) {
            ApexLog.w(ApexSuite.ApkId.MAIN, "[CompileGate] timeout after ${timeoutMs}ms")
            return@withContext CompileResult.Timeout(duration)
        }
        val (exitCode, output) = result
        if (exitCode == 0) {
            ApexLog.i(ApexSuite.ApkId.MAIN, "[CompileGate] success in ${duration}ms")
            CompileResult.Success(duration)
        } else {
            val errors = parseErrors(output)
            ApexLog.w(ApexSuite.ApkId.MAIN, "[CompileGate] failed: ${errors.size} errors in ${duration}ms")
            CompileResult.Failure(errors, duration)
        }
    }

    private fun parseErrors(output: String): List<CompileError> {
        val pattern = Regex("""e: file:.*?/([^:]+):(\d+):(\d+):\s*(.+)""")
        return pattern.findAll(output).map { m ->
            CompileError(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[4])
        }.toList()
    }
}
