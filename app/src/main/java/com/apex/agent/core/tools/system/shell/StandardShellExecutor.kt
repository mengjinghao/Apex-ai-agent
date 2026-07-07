package com.apex.agent.core.tools.system.shell

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.core.tools.system.AndroidPermissionLevel
import com.apex.agent.core.tools.system.ShellIdentity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** 基于标准Android权限的Shell命令执行。实现STANDARD权限级别的命令执/
class StandardShellExecutor(private val context: Context) : ShellExecutor {
    companion object {
        private const val TAG = "StandardShellExecutor"
        private const val COMMAND_TIMEOUT = 30L //    }

    override fun getPermissionLevel(): AndroidPermissionLevel = AndroidPermissionLevel.STANDARD

    override fun isAvailable(): Boolean = true // 标准执行器始终可。
    override fun hasPermission(): ShellExecutor.PermissionStatus =
            ShellExecutor.PermissionStatus.granted() // 标准执行器不需要额外权。
    override fun initialize() {
        // 标准执行器不需要初始化
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // 标准监听器不需要额外权�?       onResult(true)
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult =
            withContext(Dispatchers.IO) {
                AppLogger.d(TAG, "Executing standard command: ${command}")

                try {
                    // 判断是否包含shell特殊字符
                    if (containsShellOperators(command)) {
                        return@withContext executeWithShell(command)
                    }

                    // 使用Runtime执行简单命                   val process = Runtime.getRuntime().exec(command)

                    // 设置超时
                    val completed = process.waitFor(COMMAND_TIMEOUT, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroy()
                        return@withContext ShellExecutor.CommandResult(
                                false,
                                "",
                                "Command timed out after ${COMMAND_TIMEOUT} seconds",
                                -1
                        )
                    }

                    // 读取标准输出
                    val stdout =
                            BufferedReader(InputStreamReader(process.inputStream)).use {
                                it.readText()
                            }

                    // 读取错误输出
                    val stderr =
                            BufferedReader(InputStreamReader(process.errorStream)).use {
                                it.readText()
                            }

                    val exitCode = process.exitValue()

                    return@withContext ShellExecutor.CommandResult(
                            exitCode == 0,
                            stdout,
                            stderr,
                            exitCode
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error executing standard command", e)
                    return@withContext ShellExecutor.CommandResult(
                            false,
                            "",
                            "Error: ${e.message}",
                            -1
                    )
                }
            }

    override suspend fun startProcess(command: String): ShellProcess = withContext(Dispatchers.IO) {
        StandardShellProcess(command)
            }

    /** 通过shell解释器执行包含特殊操作符的命/
    private suspend fun executeWithShell(command: String): ShellExecutor.CommandResult =
            withContext(Dispatchers.IO) {
                try {
                    // 使用sh -c执行带有shell特性的命令
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

                    // 设置超时
                    val completed = process.waitFor(COMMAND_TIMEOUT, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroy()
                        return@withContext ShellExecutor.CommandResult(
                                false,
                                "",
                                "Command timed out after ${COMMAND_TIMEOUT} seconds",
                                -1
                        )
                    }

                    // 读取标准输出
                    val stdout =
                            BufferedReader(InputStreamReader(process.inputStream)).use {
                                it.readText()
                            }

                    // 读取错误输出
                    val stderr =
                            BufferedReader(InputStreamReader(process.errorStream)).use {
                                it.readText()
                            }

                    val exitCode = process.exitValue()

                    // 对于grep命令，即使没有匹配也认为成功
                    val success =
                            if (command.contains("grep")) {
                                exitCode == 0 || exitCode == 1
                            } else {
                                exitCode == 0
                            }

                    return@withContext ShellExecutor.CommandResult(
                            success,
                            stdout,
                            stderr,
                            exitCode
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error executing shell command", e)
                    return@withContext ShellExecutor.CommandResult(
                            false,
                            "",
                            "Error: ${e.message}",
                            -1
                    )
                }
            }

    /**
     * 检测命令是否包含需要shell解释的特殊操作符
     * @param command 要检查的命令
     * @return 是否包含shell操作�?    */
    private fun containsShellOperators(command: String): Boolean {
        // 预处理：标记引号内的内容，避免检测引号内的操作符
        var inSingleQuotes = false
        var inDoubleQuotes = false
        var escaped = false
        var i = 0

        while (i < command.length) {
            val c = command[i]

            // 处理转义字符
            if (c == '\\' && !escaped) {
                escaped = true
                i++
                continue
            }

            // 处理引号
            if (c == '\'' && !escaped && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
            } else if (c == '"' && !escaped && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
            }
            // 只在不在引号内时检测操作符
            else if (!inSingleQuotes && !inDoubleQuotes && !escaped) {
                // 检测管�?               if (c == '|') {
                    return true
                }

                // 检  操作               if (c == '&') {
                    return true
                }

                // 检测重定向
                if (c == '>' || c == '<') {
                    return true
                }

                // 检测分�?               if (c == ';') {
                    return true
                }
            }

            escaped = false
            i++
        }

        return false
    }
}

/**
 * 标准，ShellProcess 实现，使，Runtime.exec()
 */
private class StandardShellProcess(command: String) : ShellProcess {
    private val process: Process = if (containsShellOperators(command)) {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
    } else {
        Runtime.getRuntime().exec(command)
    }
    
    override val stdout: Flow<String> = callbackFlow {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    trySend(line ?: "")
                }
            }
        } catch (e: Exception) {
            // Process ended or error occurred
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override val stderr: Flow<String> = callbackFlow {
        try {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    trySend(line ?: "")
                }
            }
        } catch (e: Exception) {
            // Process ended or error occurred
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override val isAlive: Boolean
        get() = process.isAlive

    override fun destroy() {
        process.destroy()
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
    }
    
    companion object {
        /**
         * 检测命令是否包含需要shell解释的特殊操作符
         */
        private fun containsShellOperators(command: String): Boolean {
            // 预处理：标记引号内的内容，避免检测引号内的操作符
            var inSingleQuotes = false
            var inDoubleQuotes = false
            var escaped = false
            var i = 0

            while (i < command.length) {
                val c = command[i]

                // 处理转义字符
                if (c == '\\' && !escaped) {
                    escaped = true
                    i++
                    continue
                }

                // 处理引号
                if (c == '\'' && !escaped && !inDoubleQuotes) {
                    inSingleQuotes = !inSingleQuotes
                } else if (c == '"' && !escaped && !inSingleQuotes) {
                    inDoubleQuotes = !inDoubleQuotes
                }
                // 只在不在引号内时检测操作符
                else if (!inSingleQuotes && !inDoubleQuotes && !escaped) {
                    // 检测管                   if (c == '|') {
                        return true
                    }

                    // 检  操作                   if (c == '&') {
                        return true
                    }

                    // 检测重定向
                    if (c == '>' || c == '<') {
                        return true
                    }

                    // 检测分                   if (c == ';') {
                        return true
                    }
                }

                escaped = false
                i++
            }

            return false
        }
    }
}
