package com.apex.domain.chat

import com.apex.core.model.Conversation

/** 获取聊天历史（内存存储，后续可扩展 Room） */
class GetChatHistoryUseCase {
    private val conversations = mutableMapOf<String, Conversation>()

    fun get(id: String): Conversation? = conversations[id]
    fun save(conversation: Conversation) { conversations[conversation.id] = conversation }
    fun list(): List<Conversation> = conversations.values.sortedByDescending { it.updatedAt }
    fun delete(id: String) { conversations.remove(id) }

    fun create(systemPrompt: String? = null): Conversation {
        val conv = Conversation(id = System.currentTimeMillis().toString(), systemPrompt = systemPrompt)
        conversations[conv.id] = conv
        return conv
    }
}
