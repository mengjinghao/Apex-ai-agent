package com.apex.core.kernel

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * 类型安全的事件总线 — 基于 SharedFlow，支持背压和多订阅者。
 */
class EventBus {
    @PublishedApi internal val _events = MutableSharedFlow<ApexEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ApexEvent> = _events.asSharedFlow()

    /** 发布事件 */
    suspend fun publish(event: ApexEvent) {
        _events.emit(event)
    }

    /** 订阅特定类型的事件 */
    inline fun <reified T : ApexEvent> filter() = _events.filterIsInstance<T>()
}

/** 全局事件基类 */
sealed class ApexEvent {
    /** 聊天消息已发送 */
    data class MessageSent(val conversationId: String, val role: String, val content: String) : ApexEvent()
    /** 收到 AI 流式 chunk */
    data class StreamChunk(val conversationId: String, val chunk: String) : ApexEvent()
    /** AI 回复完成 */
    data class MessageCompleted(val conversationId: String, val fullContent: String) : ApexEvent()
    /** 工具调用开始 */
    data class ToolCallStarted(val toolId: String, val toolName: String) : ApexEvent()
    /** 工具调用完成 */
    data class ToolCallCompleted(val toolId: String, val success: Boolean, val result: String) : ApexEvent()
    /** 配置变更 */
    data class ConfigChanged(val key: String) : ApexEvent()
    /** 错误 */
    data class ErrorOccurred(val message: String) : ApexEvent()
}
