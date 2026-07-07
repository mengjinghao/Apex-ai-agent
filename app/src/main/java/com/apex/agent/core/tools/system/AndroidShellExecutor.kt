package com.apex.agent.core.tools.system

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.core.tools.system.shell.ShellExecutor
import com.apex.agent.core.tools.system.shell.ShellExecutorFactory
import com.apex.agent.core.tools.system.shell.ShellProcess
import com.apex.agent.data.preferences.androidPermissionPreferences

/** 向后兼容的Shell命令执行工具，通过权限级别委托到相应的Shell执行�?/
class AndroidShellExecutor {
    companion object {
        private const val TAG = "AndroidShellExecutor"
        private var context: Context? = null

        /**
         * 设置全局上下文引�?        * @param appContext 应用上下�?        */
        fun setContext(appContext: Context) {
            context = appContext.applicationContext
        }

        private fun getPermissionLevelLabel(level: AndroidPermissionLevel): String {
            return when (level) {
                AndroidPermissionLevel.STANDARD -> "STANDARD"
                AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY"
                AndroidPermissionLevel.DEBUGGER -> "DEBUGGER"
                AndroidPermissionLevel.ADMIN -> "ADMIN"
                AndroidPermissionLevel.ROOT -> "ROOT"
            }
        }

        private fun buildStrictUnavailableReason(
            level: AndroidPermissionLevel,
            executorAvailable: Boolean,
            permStatus: ShellExecutor.PermissionStatus
        ): String {
            val reasons = mutableListOf<String>()

            if (!executorAvailable) {
                reasons += "executor unavailable"
            }
            if (!permStatus.granted) {
                reasons += permStatus.reason.trim().ifEmpty { "permission not granted" }
            }

            val reasonText = reasons.distinct().joinToString("; ").ifBlank { "unknown reason" }
            return "Current ${getPermissionLevelLabel(level)} unavailable: ${reasonText}"
        }

        /**
         * 封装执行命令的函�?        * @param command 要执行的命令
         * @return 命令执行结果
         */
        suspend fun executeShellCommand(command: String): CommandResult {
            return executeShellCommand(command, null)
        }

        suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity): CommandResult {
            val ctx = context ?: return CommandResult(false, "", "Context not initialized")

            // 如果调用方显式指定了身份，就直接向下传递；否则使用默认身份
            val identity = identityOverride ?: ShellIdentity.DEFAULT

            val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
            AppLogger.d(TAG, "Using preferred permission level: ${preferredLevel}, identity=${identity}")

            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD

            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()
            val executorAvailable = preferredExecutor.isAvailable()

            if (executorAvailable && permStatus.granted) {
                val result = preferredExecutor.executeCommand(command, identity)
                return CommandResult(result.success, result.stdout, result.stderr, result.exitCode)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            AppLogger.d(TAG, "Strict permission mode enabled, no fallback. ${reason}")
            return CommandResult(false, "", reason, -1)
        }

        suspend fun startShellProcess(command: String): ShellProcess {
            val ctx = context ?: throw IllegalStateException("Context not initialized")

            val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
            AppLogger.d(TAG, "Starting process with preferred permission level: ${preferredLevel}")

            val actualLevel = preferredLevel ?: AndroidPermissionLevel.STANDARD
            val preferredExecutor = ShellExecutorFactory.getExecutor(ctx, actualLevel)
            val permStatus = preferredExecutor.hasPermission()
            val executorAvailable = preferredExecutor.isAvailable()

            if (executorAvailable && permStatus.granted) {
                return preferredExecutor.startProcess(command)
            }

            val reason = buildStrictUnavailableReason(actualLevel, executorAvailable, permStatus)

            AppLogger.d(TAG, "Strict permission mode enabled, no fallback. ${reason}")
            throw SecurityException(reason)
        }
    }

    /** 命令执行结果数据�?/
    data class CommandResult(
            val success: Boolean,
            val stdout: String,
            val stderr: String = "",
            val exitCode: Int = -1
    )
}

enum class ShellIdentity {
    DEFAULT,
    APP,
    ROOT,
    SHELL
}
