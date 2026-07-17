package com.apex.domain.chat

import com.apex.core.model.ApiConfig
import com.apex.core.model.Conversation
import com.apex.engine.chat.ChatEngine
import com.apex.engine.chat.StreamEvent
import kotlinx.coroutines.flow.Flow

/** 发送聊天消息 */
class SendMessageUseCase(private val engine: ChatEngine) {
    fun execute(conversation: Conversation, config: ApiConfig): Flow<StreamEvent> {
        return engine.sendMessage(conversation, config)
    }
    fun cancel() { engine.cancel() }
}
