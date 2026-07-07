package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalSession
import com.ai.assistance.aiterminal.terminal.TerminalManager

interface DialogCommandHandler {
    suspend fun executeCommand(command: String, session: TerminalSession): CommandExecutionResult
    suspend fun executeCommandWithRoot(command: String, session: TerminalSession): CommandExecutionResult
}

sealed class CommandExecutionResult {
    abstract val command: String
    
    data class Success(
        override val command: String,
        val output: String
    ) : CommandExecutionResult()
    
    data class Error(
        override val command: String,
        val message: String,
        val output: String = ""
    ) : CommandExecutionResult()
}

class DefaultDialogCommandHandler(private val context: Context) : DialogCommandHandler {
    private val terminalManager by lazy { TerminalManager.getInstance(context) }
    
    override suspend fun executeCommand(
        command: String,
        session: TerminalSession
    ): CommandExecutionResult {
        return try {
            val success = terminalManager.executeCommand(session.sessionId, command)
            if (success) {
                CommandExecutionResult.Success(command, "ok")
            } else {
                CommandExecutionResult.Error(command, "Command execution failed")
            }
        } catch (e: Exception) {
            CommandExecutionResult.Error(command, e.message ?: "Unknown error")
        }
    }
    
    override suspend fun executeCommandWithRoot(
        command: String,
        session: TerminalSession
    ): CommandExecutionResult {
        val rootCommand = "su -c '${command.replace("'", "'\\''")}'"
        return executeCommand(rootCommand, session)
    }
}