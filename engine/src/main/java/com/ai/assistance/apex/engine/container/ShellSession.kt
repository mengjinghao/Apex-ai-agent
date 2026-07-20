package com.ai.assistance.apex.engine.container

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ai.assistance.apex.engine.model.ExecutionResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

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
        // J-8: 优先用 Process.pid() 公开方法 (Android API 35+ / Java 9+),
        // 避免依赖反射访问私有 `pid` 字段 (不同 ROM/版本字段名/类型可能不同,极脆弱)。
        return try {
            val pidMethod = Process::class.java.getMethod("pid")
            (pidMethod.invoke(process) as Long).toInt()
        } catch (e: NoSuchMethodException) {
            // Fallback (API 26-34): 反射读取私有 `pid` 字段
            try {
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(process)
            } catch (e2: Exception) {
                -1
            }
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
        // J-10 / B-15: sentinel 协议 — 命令后追加 `echo <sentinel>:$?`,
        // 轮询 outputBuffer 直到 sentinel 出现,然后从 `$?` 解析真实 exit code。
        // 替代旧的 sleep(200)+poll:不再丢失 sentinel 之后的输出,exit code 也准确。
        val sentinel = "__APEX_CMD_DONE_${startTime}__"
        val sentinelPrefix = "$sentinel:"

        return try {
            synchronized(outputBuffer) {
                outputBuffer.setLength(0)
            }

            val writer = inputWriter
            if (writer == null) {
                return ExecutionResult().apply {
                    exitCode = -1
                    error = "Input writer not available"
                    executionTime = System.currentTimeMillis() - startTime
                    success = false
                }
            }
            writer.apply {
                write("$command\n")
                write("echo $sentinelPrefix\$?\n")
                flush()
            }

            val maxWaitTime = startTime + timeoutMs
            var sentinelIndex = -1
            // PERF-14: 原实现在 synchronized 块里 outputBuffer.toString() 每 50ms 复制整个
            // buffer (O(n) + 分配)。改为 synchronized 块内只做 indexOf (O(n) 但零分配),
            // sentinel 找到后再做一次 toString 解析。长输出场景下 GC 压力大幅下降。
            var timeoutSnapshot: String? = null
            while (System.currentTimeMillis() < maxWaitTime) {
                val idx = synchronized(outputBuffer) { outputBuffer.indexOf(sentinelPrefix) }
                if (idx >= 0) {
                    sentinelIndex = idx
                    break
                }
                if (process?.isAlive != true) {
                    // Shell 在产出 sentinel 前就退出了 — 抓一次快照用于错误返回,然后停止等待
                    timeoutSnapshot = synchronized(outputBuffer) { outputBuffer.toString() }
                    break
                }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            val executionTime = System.currentTimeMillis() - startTime

            if (sentinelIndex < 0) {
                // 超时或 shell 退出,未拿到 sentinel — 返回已收集到的输出
                val snapshot = timeoutSnapshot
                    ?: synchronized(outputBuffer) { outputBuffer.toString() }
                return ExecutionResult().apply {
                    exitCode = -1
                    this.output = snapshot
                    this.error = if (process?.isAlive != true)
                        "Shell process exited before command completed"
                    else "Command timed out after ${timeoutMs}ms"
                    this.executionTime = executionTime
                    success = false
                }
            }

            // PERF-14: sentinel 已找到 — 在单个 synchronized 块内完成解析 + buffer 清理,
            // 避免重复 toString。这是唯一一次完整复制,且只在命令结束时发生(非每 50ms)。
            val (actualOutput, parsedExitCode) = synchronized(outputBuffer) {
                val full = outputBuffer.toString()
                // 解析 exit code: sentinel 后紧跟 ":<digits>" 直到行尾
                val afterSentinel = full.substring(sentinelIndex + sentinelPrefix.length)
                val newlineIdx = afterSentinel.indexOf('\n')
                val exitCodeStr = if (newlineIdx >= 0)
                    afterSentinel.substring(0, newlineIdx).trim()
                else
                    afterSentinel.trim()
                val code = exitCodeStr.toIntOrNull() ?: 0

                // 真实命令输出 = sentinel 之前的全部内容 (去掉末尾换行)
                var actualOut = full.substring(0, sentinelIndex)
                while (actualOut.endsWith('\n')) {
                    actualOut = actualOut.removeSuffix("\n")
                }

                // 从共享 buffer 中移除 sentinel 行,保持 getOutput() 干净
                val lineEnd = full.indexOf('\n', sentinelIndex)
                outputBuffer.setLength(0)
                outputBuffer.append(full.substring(0, sentinelIndex))
                if (lineEnd >= 0 && lineEnd + 1 < full.length) {
                    outputBuffer.append(full.substring(lineEnd + 1))
                }
                Pair(actualOut, code)
            }

            ExecutionResult().apply {
                exitCode = parsedExitCode
                this.output = actualOutput
                this.error = ""
                this.executionTime = executionTime
                success = (parsedExitCode == 0)
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