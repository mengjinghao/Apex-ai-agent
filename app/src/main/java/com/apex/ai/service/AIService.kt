package com.apex.ai.service

import com.apex.ai.data.ChatMessage

interface AIService {
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit
    ): String
    fun cancel()
}
