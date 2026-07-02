package com.ai.assistance.aiterminal.terminal.model

/**
 * 终端全局状态（对标状态管理能力）
 */
data class TerminalState(
    val currentSessionId: String = "",
    val sessions: Map<String, Session> = emptyMap(),
    val isServiceRunning: Boolean = false
)
