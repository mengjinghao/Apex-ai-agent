package com.ai.assistance.aiterminal.terminal.model

/**
 * 终端事件（对标事件通知能力）
 */
sealed class TerminalEvent {
    data class CommandOutput(val sessionId: String, val output: String) : TerminalEvent()
    data class DirectoryChanged(val sessionId: String, val newDir: String) : TerminalEvent()
    data class SessionStateChanged(val sessionId: String, val state: SessionState) : TerminalEvent()
    data class CommandFinished(val sessionId: String, val command: String, val exitCode: Int) : TerminalEvent()
    data class ErrorOccurred(val sessionId: String, val message: String, val code: Int) : TerminalEvent()
}
