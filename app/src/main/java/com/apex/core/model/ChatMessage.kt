package com.apex.core.model

/**
 * 聊天消息 — 核心数据模型。
 */
data class ChatMessage(
    val role: String,           // "user" / "assistant" / "system" / "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val error: String? = null
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
    val isSystem: Boolean get() = role == "system"
    val isTool: Boolean get() = role == "tool"
    val isError: Boolean get() = error != null
}
