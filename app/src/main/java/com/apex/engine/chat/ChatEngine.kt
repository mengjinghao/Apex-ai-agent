package com.apex.engine.chat

import com.apex.core.kernel.ApexKernel
import com.apex.core.kernel.EventBus
import com.apex.core.model.ApiConfig
import com.apex.core.model.ChatMessage
import com.apex.core.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * 聊天引擎 — 协调 LLM Provider 和工具系统，管理对话流程。
 */
class ChatEngine(
    private val provider: LLMProvider = OpenAICompatProvider(),
    private val bus: EventBus = ApexKernel.eventBus
) {
    /** 发送消息，返回流式事件 */
    fun sendMessage(
        conversation: Conversation,
        config: ApiConfig
    ): Flow<StreamEvent> {
        val allMessages = buildMessages(conversation, config)
        val requestConfig = LLMRequestConfig(
            endpoint = config.endpoint,
            apiKey = config.apiKey,
            model = config.model,
            temperature = config.temperature
        )
        return provider.stream(allMessages, emptyList(), requestConfig)
            .onEach { event ->
                when (event) {
                    is StreamEvent.Chunk -> bus.publish(
                        com.apex.core.kernel.ApexEvent.StreamChunk(conversation.id, event.text)
                    )
                    is StreamEvent.Done -> bus.publish(
                        com.apex.core.kernel.ApexEvent.MessageCompleted(conversation.id, "")
                    )
                    is StreamEvent.Error -> bus.publish(
                        com.apex.core.kernel.ApexEvent.ErrorOccurred(event.message)
                    )
                    else -> {}
                }
            }
    }

    /** 取消当前请求 */
    fun cancel() {
        provider.cancel()
    }

    private fun buildMessages(conversation: Conversation, config: ApiConfig): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        if (config.systemPrompt.isNotEmpty()) {
            list.add(ChatMessage(role = "system", content = config.systemPrompt))
        }
        list.addAll(conversation.messages.filter { !it.isStreaming && !it.isError })
        return list
    }
}
