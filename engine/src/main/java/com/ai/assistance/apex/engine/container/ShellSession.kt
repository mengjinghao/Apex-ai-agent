package com.ai.assistance.apex.engine.container

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ai.assistance.apex.engine.model.ExecutionResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ShellSession(private val context: Context, private val rootfsPath: String) {

    companion object {
        private const val TAG = "ShellSession"
    }

    private var process: Process? = null
    private var outputBuffer = StringBuilder()
    private var inputWriter: OutputStreamWriter? = null
    private var outputListener: ((String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var pidValue: Int = -1

    val pid: Int get() {
        val p = process ?: return pidValue
        if (pidValue == -1) {
            pidValue = getProcessPid(p)
        }
        return pidValue
    }
    val startTime: Long = System.currentTimeMillis()

    private fun getProcessPid(process: Process): Int {
        return try {
            val field: Field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (e: Exception) {
            -1
        }
    }

    fun setOutputListener(listener: (String) -> Unit) {
        outputListener = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    fun start(): Boolean {
        return try {
            val command = buildProotCommand()
            process = Runtime.getRuntime().exec(command)
            val p = process ?: return false

            inputWriter = OutputStreamWriter(p.outputStream).apply {
                flush()
            }

            Thread {
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            synchronized(outputBuffer) {
                                outputBuffer.append(it).append("\n")
                            }
                            outputListener?.invoke(it)
                        }
                    }
                }
            }.start()

            Thread {
                BufferedReader(InputStreamReader(p.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            synchronized(outputBuffer) {
                                outputBuffer.append("[ERROR] $it\n")
                            }
                            errorListener?.invoke(it)
                        }
                    }
                }
            }.start()

            Thread {
                try {
                    process?.waitFor(30, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed", e)
            errorListener?.invoke("Shell start failed: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            inputWriter?.close()
            process?.destroyForcibly()
            process = null
            inputWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
        }
    }

    fun execute(command: String, timeoutMs: Long = 30000): ExecutionResult {
        if (process == null || process?.isAlive != true) {
            return ExecutionResult().apply {
                exitCode = -1
                error = "Shell process not alive"
                success = false
            }
        }

        val startTime = System.currentTimeMillis()

        return try {
            synchronized(outputBuffer) {
                outputBuffer.setLength(0)
            }

            inputWriter?.apply {
                write("$command\n")
                flush()
            }

            Thread.sleep(200)

            var output = ""
            val maxWaitTime = startTime + timeoutMs
            while (System.currentTimeMillis() < maxWaitTime) {
                synchronized(outputBuffer) {
                    output = outputBuffer.toString()
                }
                if (output.isNotEmpty() && output.contains("\n")) {
                    break
                }
                Thread.sleep(100)
            }

            val executionTime = System.currentTimeMillis() - startTime

            ExecutionResult().apply {
                exitCode = if (process?.isAlive == true) 0 else -1
                this.output = output
                this.error = ""
                this.executionTime = executionTime
                success = exitCode == 0
            }
        } catch (e: TimeoutException) {
            ExecutionResult().apply {
                exitCode = -1
                error = "Command execution timed out"
                executionTime = timeoutMs
                success = false
            }
        } catch (e: Exception) {
            ExecutionResult().apply {
                exitCode = -1
                error = e.message ?: "Execution error"
                executionTime = System.currentTimeMillis() - startTime
                success = false
            }
        }
    }

    fun getOutput(): String {
        synchronized(outputBuffer) {
            return outputBuffer.toString()
        }
    }

    private fun buildProotCommand(): Array<String> {
        val prootPath = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val externalDir = Environment.getExternalStorageDirectory().absolutePath

        return arrayOf(
            prootPath,
            "--rootfs=$rootfsPath",
            "--bind=$externalDir:/sdcard",
            "--bind=${context.filesDir}:${context.filesDir}",
            "--bind=/dev:/dev",
            "--bind=/proc:/proc",
            "--bind=/sys:/sys",
            "-w", homeDir.absolutePath,
            "/bin/bash",
            "--norc",
            "--noprofile"
        )
    }
}