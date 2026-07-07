package com.ai.assistance.aiterminal.terminal.model

/**
 * 会话状态（对标Native层）
 */
enum class SessionState {
    CREATED, RUNNING, SUSPENDED, CLOSED
}

/**
 * 终端会话实体（对标多会话管理）
 */
data class Session(
    val sessionId: String,
    var state: SessionState = SessionState.CREATED,
    var currentDir: String = "/",
    val commandHistory: MutableList<String> = mutableListOf(),
    val env: MutableMap<String, String> = mutableMapOf()
)
