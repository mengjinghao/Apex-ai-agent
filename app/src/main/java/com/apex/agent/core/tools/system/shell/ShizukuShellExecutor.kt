package com.apex.agent.core.tools.system.shell

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.core.tools.system.PermissionMode
import com.apex.agent.core.tools.system.ShellIdentity
import com.apex.agent.core.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuShellExecutor(private val context: Context) : ShellExecutor {

    companion object {
        private const val TAG = "ShizukuShellExecutor"
    }

    private var isInitialized = false
    private var cachedPermissionStatus: ShellExecutor.PermissionStatus? = null
    private var lastPermissionCheckTime = 0L
    private const val PERMISSION_CACHE_TTL = 10000L

    override fun getPermissionLevel(): com.apex.agent.core.tools.system.AndroidPermissionLevel =
        com.apex.agent.core.tools.system.AndroidPermissionLevel.ROOT

    override fun isAvailable(): Boolean = try {
        ShizukuAuthorizer.isShizukuServiceRunning()
    } catch (e: Exception) {
        AppLogger.e(TAG, "检�?Shizuku 可用性失�?, e)
        false
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        val now = System.currentTimeMillis()

        if (now - lastPermissionCheckTime < PERMISSION_CACHE_TTL && cachedPermissionStatus != null) {
            return cachedPermissionStatus!!
        }

        val status = try {
            when {
                !isAvailable() -> ShellExecutor.PermissionStatus.denied("Shizuku 服务未运�?)
                !ShizukuAuthorizer.hasShizukuPermission() -> ShellExecutor.PermissionStatus.denied("未获�?Shizuku 权限")
                else -> ShellExecutor.PermissionStatus.granted()
            }
        } catch (e: Exception) {
            ShellExecutor.PermissionStatus.denied("检查权限失�? ${e.message}")
        }

        cachedPermissionStatus = status
        lastPermissionCheckTime = now
        return status
    }

    override fun initialize() {
        if (isInitialized) return

        AppLogger.d(TAG, "初始�?Shizuku Shell 执行�?..")
        isInitialized = true
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        AppLogger.d(TAG, "请求 Shizuku 权限...")
        ShizukuAuthorizer.requestShizukuPermission(onResult)
    }

    override suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): ShellExecutor.CommandResult = withContext(Dispatchers.IO) {
            AppLogger.d(TAG, "执行 Shizuku 命令: ${command}")

            val permStatus = hasPermission()
            if (!permStatus.granted) {
                return@withContext ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = permStatus.reason,
                    exitCode = -1
                )
            }

            try {
                val result = executeShizukuCommand(command)
                AppLogger.d(TAG, "Shizuku 命令执行完成: exitCode=${result.exitCode}")
                result
            } catch (e: Exception) {
                AppLogger.e(TAG, "Shizuku 命令执行失败", e)
                ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "执行失败: ${e.message}",
                    exitCode = -1
                )
            }
        }

    private fun executeShizukuCommand(command: String): ShellExecutor.CommandResult {
        return try {
            val builder = Shizuku.newProcessBuilder(arrayOf("sh", "-c", command))
            val process = builder.start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            ShellExecutor.CommandResult(
                success = exitCode == 0,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "执行 Shizuku 命令失败", e)
            ShellExecutor.CommandResult(
                success = false,
                stdout = "",
                stderr = e.message ?: "Unknown error",
                exitCode = -1
            )
        }
    }

    override suspend fun startProcess(command: String): ShellProcess {
        return ShizukuShellProcess(command)
    }
}

private class ShizukuShellProcess(
    private val command: String
) : ShellProcess {

    companion object {
        private const val TAG = "ShizukuShellProcess"
    }

    private var process: Process? = null
    private var destroyed = false

    init {
        startProcess()
    }

    private fun startProcess() {
        try {
            val builder = Shizuku.newProcessBuilder(arrayOf("sh", "-c", command))
            process = builder.start()
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动 Shizuku 进程失败", e)
        }
    }

    override val stdout: Flow<String> = flow {
        try {
            process?.inputStream?.let { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && !destroyed) {
                        emit(line!!)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取 stdout 失败", e)
        }
    }.flowOn(Dispatchers.IO)

    override val stderr: Flow<String> = flow {
        try {
            process?.errorStream?.let { errorStream ->
                BufferedReader(InputStreamReader(errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && !destroyed) {
                        emit(line!!)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取 stderr 失败", e)
        }
    }.flowOn(Dispatchers.IO)

    override val isAlive: Boolean
        get() = process?.isAlive == true && !destroyed

    override fun destroy() {
        destroyed = true
        process?.destroy()
        process = null
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        try {
            process?.waitFor() ?: -1
        } catch (e: Exception) {
            AppLogger.e(TAG, "等待进程结束失败", e)
            -1
        }
    }
}