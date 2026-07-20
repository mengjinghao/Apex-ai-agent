package com.apex.engine.chat

import com.apex.core.kernel.ApexKernel
import com.apex.core.kernel.EventBus
import com.apex.core.model.ApiConfig
import com.apex.core.model.ChatMessage
import com.apex.core.model.Conversation
import com.apex.core.model.ToolMetadata
import com.apex.engine.tools.ToolExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * 聊天引擎 — 协调 LLM Provider 和工具系统，管理对话流程。
 *
 * 工具循环不在本类内完成——本类只负责把 [ToolExecutor] 注册表中的工具元信息
 * 透传给 LLM Provider，由调用方（如 [com.apex.ui.features.chat.ChatViewModel]）
 * 消费 [StreamEvent.ToolCallEvent] 并通过 [ToolExecutor.execute] 执行，
 * 再以 role="tool" 的 [ChatMessage] 回灌对话后再次调用 [sendMessage]。
 *
 * @param provider LLM 提供商（默认 [OpenAICompatProvider]）
 * @param bus      事件总线（用于广播流式 chunk / 完成事件，便于全局监听）
 * @param toolExecutor 可选工具执行器。非空时会把其注册表中所有工具元信息作为
 *                     OpenAI function-calling 工具列表传给 LLM；为空时不暴露
 *                     任何工具（向后兼容，等价于之前的 `emptyList()` 行为）。
 */
class ChatEngine(
    private val provider: LLMProvider = OpenAICompatProvider(),
    private val bus: EventBus = ApexKernel.eventBus,
    private val toolExecutor: ToolExecutor? = null
) {
    /**
     * PERF-40: 工具元信息缓存。
     *
     * 工具在 [com.apex.application.ApexApplication.onCreate] + BuiltInTools.createAll 中
     * 一次性注册，之后注册表在运行期稳定不变。原实现每次 [sendMessage] 都调
     * [ToolExecutor.listMetadata] 重新映射 ToolRegistry.tools.values —— 长对话中每条消息
     * 都付一次 hashmap 遍历 + list 分配开销，不必要。
     *
     * 这里按“首次使用懒加载 + 永不自动失效”策略缓存。如果未来支持运行期动态注册/卸载
     * 工具，需要在注册/卸载点调用 [invalidateToolMetadataCache]。
     */
    @Volatile
    private var cachedToolMetadata: List<ToolMetadata>? = null

    /** 取工具元信息（首次调用懒加载，后续命中缓存）。 */
    private fun getToolMetadata(): List<ToolMetadata> {
        cachedToolMetadata?.let { return it }
        val list = toolExecutor?.listMetadata() ?: emptyList()
        cachedToolMetadata = list
        return list
    }

    /** 主动失效缓存——未来在动态注册/卸载工具时调用。 */
    fun invalidateToolMetadataCache() {
        cachedToolMetadata = null
    }

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
        // 把 ToolExecutor 注册表中所有工具元信息透传给 LLM Provider，
        // 由 Provider 转换为 OpenAI function-calling 协议格式后随请求发出。
        // PERF-40: 用缓存避免每条消息重复调 listMetadata()。
        // toolExecutor 为 null 时退化为不暴露任何工具（向后兼容）。
        val tools = getToolMetadata()
        return provider.stream(allMessages, tools, requestConfig)
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
