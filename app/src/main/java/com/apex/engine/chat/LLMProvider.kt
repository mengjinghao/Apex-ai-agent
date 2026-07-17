package com.apex.engine.chat

import com.apex.core.model.ChatMessage
import com.apex.core.model.ToolMetadata
import kotlinx.coroutines.flow.Flow

/**
 * LLM 提供商接口 — 所有 AI Provider 实现此接口。
 */
interface LLMProvider {
    /** 流式发送消息，返回事件流 */
    fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolMetadata> = emptyList(),
        config: LLMRequestConfig
    ): Flow<StreamEvent>

    /** 取消当前请求 */
    fun cancel()
}

/** 请求配置 */
data class LLMRequestConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null
)

/** 流式事件 */
sealed class StreamEvent {
    /** 文本 chunk */
    data class Chunk(val text: String) : StreamEvent()
    /** 工具调用 */
    data class ToolCallEvent(val id: String, val name: String, val arguments: String) : StreamEvent()
    /** 流结束 */
    object Done : StreamEvent()
    /** 错误 */
    data class Error(val message: String) : StreamEvent()
}
