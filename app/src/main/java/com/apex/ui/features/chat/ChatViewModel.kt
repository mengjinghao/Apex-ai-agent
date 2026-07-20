package com.apex.ui.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.core.model.ApiConfig
import com.apex.core.model.ChatMessage
import com.apex.core.model.Conversation
import com.apex.core.model.ToolCall
import com.apex.core.model.ToolResult
import com.apex.domain.chat.GetChatHistoryUseCase
import com.apex.domain.config.GetApiConfigUseCase
import com.apex.engine.chat.ChatEngine
import com.apex.engine.chat.StreamEvent
import com.apex.engine.tools.ToolExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ChatUiState(
    val conversation: Conversation = Conversation(id = "0", title = "新对话"),
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = false
)

sealed class ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent()
    object SendMessage : ChatIntent()
    object CancelSend : ChatIntent()
    object ClearMessages : ChatIntent()
    object ClearError : ChatIntent()
}

/**
 * 聊天 ViewModel — Hilt 注入 [ChatEngine] + [ToolExecutor]。
 *
 * 工具调用循环：
 *  1. 用户发送消息 → 启动一轮 LLM 流式请求（[ChatEngine.sendMessage]）
 *  2. 收到 [StreamEvent.ToolCallEvent] 时记录工具调用，等流结束后执行工具
 *  3. 把工具结果作为 role="tool" 的 [ChatMessage] 追加进对话
 *  4. 把助手那一条消息打上 [ChatMessage.toolCallsJson]（OpenAI 协议 tool_calls 字段）
 *  5. 创建一个新的空 AI 气泡，回到步骤 1（让 LLM 看到工具结果后继续作答）
 *  6. 最多循环 [MAX_TOOL_ROUNDS] 次，超出后报错退出，避免无限循环
 *
 * ChatEngine 通过 [ToolExecutor.listMetadata] 把注册表中的工具元信息透传给 LLM
 * Provider（[com.apex.engine.chat.OpenAICompatProvider]），由 Provider 转成 OpenAI
 * function-calling 协议格式后随请求体发出。SafeShellTool 等 4 个内置工具
 * （[com.apex.engine.tools.builtin.BuiltInTools]）会被自动暴露给 LLM。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatEngine: ChatEngine,
    private val toolExecutor: ToolExecutor
) : ViewModel() {
    private val historyUseCase = GetChatHistoryUseCase()
    private val getConfigUseCase = GetApiConfigUseCase()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sendJob: Job? = null

    // PERF-21: 流式响应限流。逐 chunk emit StateFlow 会触发 1000+ 次 Compose 重组
    // (一次 LLM 响应可能 1000+ chunks)。用 StringBuilder 累积当前 AI 气泡内容,
    // 每 60ms 最多 emit 一次,Done/Error 时做最终 flush。视觉无感知(人眼 <16fps),
    // 重组次数从 1000 降到 ~17。
    private val streamingBuffer = StringBuilder()
    private var lastEmitTime = 0L

    init {
        val config = getConfigUseCase.execute()
        val conv = historyUseCase.create(config.systemPrompt)
        _state.update { it.copy(conversation = conv, isConfigured = config.isConfigured) }
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> _state.update { it.copy(inputText = intent.text) }
            ChatIntent.SendMessage -> sendMessage()
            ChatIntent.CancelSend -> cancelSend()
            ChatIntent.ClearMessages -> clearMessages()
            ChatIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun sendMessage() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty() || _state.value.isSending) return

        val config = getConfigUseCase.execute()
        if (!config.isConfigured) {
            _state.update { it.copy(error = "请先在设置中配置 API Key") }
            return
        }

        // PERF-21: 每次发送重置流式缓冲区与限流计时器
        streamingBuffer.setLength(0)
        lastEmitTime = 0L

        val userMsg = ChatMessage(role = "user", content = text)
        val aiMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        val newMessages = _state.value.messages + userMsg + aiMsg

        var conversation = _state.value.conversation.copy(messages = _state.value.messages + userMsg)
        conversation = historyUseCase.save(conversation).let { conversation }

        _state.update {
            it.copy(
                messages = newMessages,
                inputText = "",
                isSending = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            try {
                var rounds = 0
                while (true) {
                    // 当前要追加 chunks 的 AI 气泡索引（每轮新建一个 AI 气泡）
                    val currentAiIndex = _state.value.messages.size - 1

                    // PERF-21: 每轮重置流式缓冲区(每轮写入一个新的 AI 气泡)
                    streamingBuffer.setLength(0)
                    lastEmitTime = 0L

                    // 构造发送给 LLM 的对话历史：过滤掉 streaming / error / 空内容 assistant 消息
                    val convForApi = _state.value.conversation.copy(
                        messages = _state.value.messages.filter {
                            !it.isStreaming && !it.isError &&
                                !(it.isAssistant && it.content.isEmpty() && it.toolCallsJson == null)
                        }
                    )

                    var toolCallEvent: StreamEvent.ToolCallEvent? = null
                    var errorEvent: StreamEvent.Error? = null

                    chatEngine.sendMessage(convForApi, config).collect { event ->
                        when (event) {
                            is StreamEvent.Chunk -> {
                                // PERF-21: 累积到 StringBuilder,每 60ms 最多 emit 一次 StateFlow
                                streamingBuffer.append(event.text)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 60) {
                                    lastEmitTime = now
                                    _state.update { state ->
                                        val messages = state.messages.toMutableList()
                                        if (currentAiIndex in messages.indices) {
                                            messages[currentAiIndex] = messages[currentAiIndex].copy(
                                                content = streamingBuffer.toString(),
                                                isStreaming = true
                                            )
                                        }
                                        state.copy(messages = messages)
                                    }
                                }
                            }
                            is StreamEvent.Done -> { /* 自然结束，由循环外逻辑处理 */ }
                            is StreamEvent.Error -> { errorEvent = event }
                            is StreamEvent.ToolCallEvent -> {
                                // 只取第一个工具调用；同一回合多工具的边界场景留待后续优化
                                if (toolCallEvent == null) toolCallEvent = event
                            }
                        }
                    }

                    // PERF-21: 最终 flush — 把完整 streamingBuffer 写回 UI(最后一个 60ms
                    // 窗口可能未触发),同时标记非流式
                    val cur = _state.value.messages.toMutableList()
                    if (currentAiIndex in cur.indices) {
                        cur[currentAiIndex] = cur[currentAiIndex].copy(
                            content = streamingBuffer.toString(),
                            isStreaming = false
                        )
                        _state.update { it.copy(messages = cur) }
                    }

                    // 错误：终止循环
                    if (errorEvent != null) {
                        val errMsg = errorEvent!!.message
                        val cur2 = _state.value.messages.toMutableList()
                        if (currentAiIndex in cur2.indices) {
                            if (cur2[currentAiIndex].content.isEmpty()) {
                                cur2[currentAiIndex] = cur2[currentAiIndex].copy(error = errMsg)
                            }
                            _state.update { it.copy(messages = cur2, error = errMsg) }
                        } else {
                            _state.update { it.copy(error = errMsg) }
                        }
                        break
                    }

                    val tc = toolCallEvent
                    // 没有工具调用 → 正常完成，退出循环
                    if (tc == null) break

                    // 工具调用循环上限保护
                    rounds++
                    if (rounds > MAX_TOOL_ROUNDS) {
                        _state.update {
                            it.copy(error = "工具调用次数超过上限 ($MAX_TOOL_ROUNDS)，已停止")
                        }
                        break
                    }

                    // 给当前 AI 气泡打上 toolCallsJson（OpenAI 协议要求 assistant 消息
                    // 在 tool_calls 数组里描述本次工具调用，否则后续 tool 消息会被拒绝）
                    val toolCallsJson = JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                // arguments 已经是 JSON 字符串（OpenAI 协议要求 string）
                                put("arguments", tc.arguments)
                            })
                        })
                    }.toString()
                    val cur3 = _state.value.messages.toMutableList()
                    if (currentAiIndex in cur3.indices) {
                        cur3[currentAiIndex] = cur3[currentAiIndex].copy(toolCallsJson = toolCallsJson)
                        _state.update { it.copy(messages = cur3) }
                    }

                    // 执行工具（ToolExecutor 自带熔断）
                    val toolCall = ToolCall(
                        id = tc.id,
                        toolId = tc.name,
                        name = tc.name,
                        arguments = tc.arguments
                    )
                    val result = toolExecutor.execute(toolCall)
                    val resultContent = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                    }

                    // 追加 tool 结果消息 + 一个新的空 AI 气泡，下一轮 LLM 流会写入新气泡
                    val toolMsg = ChatMessage(
                        role = "tool",
                        content = resultContent,
                        toolCallId = tc.id,
                        toolName = tc.name
                    )
                    val nextAiMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
                    _state.update { it.copy(messages = it.messages + toolMsg + nextAiMsg) }
                    // 回到 while 顶部，新一轮 LLM 流式请求
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "发送失败") }
            } finally {
                // PERF-21: 兜底 flush — 取消/异常时 collect 中断,前面 60ms 窗口的
                // final flush 可能没执行。把 streamingBuffer 完整内容写回最后一个
                // streaming 气泡(若已 flush 过则 isStreaming=false,此处 if 跳过)。
                val cur = _state.value.messages.toMutableList()
                if (cur.isNotEmpty() && cur.last().isStreaming) {
                    val lastIdx = cur.size - 1
                    cur[lastIdx] = cur[lastIdx].copy(
                        content = streamingBuffer.toString().ifEmpty { cur[lastIdx].content },
                        isStreaming = false
                    )
                    _state.update { it.copy(messages = cur) }
                }
                _state.update { it.copy(isSending = false) }
            }
        }
    }

    private fun cancelSend() {
        sendJob?.cancel()
        chatEngine.cancel()
        // PERF-21: 取消时也 flush streamingBuffer,保留已收到的部分内容
        val current = _state.value.messages.toMutableList()
        if (current.isNotEmpty() && current.last().isStreaming) {
            val lastIdx = current.size - 1
            current[lastIdx] = current[lastIdx].copy(
                content = streamingBuffer.toString().ifEmpty { current[lastIdx].content },
                isStreaming = false
            )
            _state.update { it.copy(messages = current, isSending = false) }
        }
    }

    private fun clearMessages() {
        val config = getConfigUseCase.execute()
        val conv = historyUseCase.create(config.systemPrompt)
        _state.update { it.copy(conversation = conv, messages = emptyList(), error = null) }
    }

    companion object {
        /** 单次用户消息触发的最大工具调用轮次（防 LLM 陷入无限工具调用循环）。 */
        private const val MAX_TOOL_ROUNDS = 5
    }
}
