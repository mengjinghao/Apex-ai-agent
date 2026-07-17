package com.apex.core.model

/**
 * 对话会话 — 包含一组消息和元信息。
 */
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val systemPrompt: String? = null
) {
    fun addMessage(msg: ChatMessage): Conversation {
        val newTitle = if (messages.isEmpty() && msg.isUser) {
            msg.content.take(30)
        } else {
            title
        }
        return copy(
            messages = messages + msg,
            updatedAt = System.currentTimeMillis(),
            title = newTitle
        )
    }

    fun updateLastMessage(msg: ChatMessage): Conversation {
        if (messages.isEmpty()) return this
        val updated = messages.dropLast(1) + msg
        return copy(messages = updated, updatedAt = System.currentTimeMillis())
    }
}
