package com.apex.ui.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.core.kernel.ApexKernel
import com.apex.core.model.ApiConfig
import com.apex.core.model.ChatMessage
import com.apex.core.model.Conversation
import com.apex.domain.chat.GetChatHistoryUseCase
import com.apex.domain.chat.SendMessageUseCase
import com.apex.domain.config.GetApiConfigUseCase
import com.apex.engine.chat.ChatEngine
import com.apex.engine.chat.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class ChatViewModel : ViewModel() {
    private val engine = ChatEngine()
    private val sendUseCase = SendMessageUseCase(engine)
    private val historyUseCase = GetChatHistoryUseCase()
    private val getConfigUseCase = GetApiConfigUseCase()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sendJob: Job? = null

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

        val userMsg = ChatMessage(role = "user", content = text)
        val aiMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        val newMessages = _state.value.messages + userMsg + aiMsg
        val aiIndex = newMessages.size - 1

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
                val historyForApi = _state.value.conversation.copy(
                    messages = _state.value.messages.filter { !it.isStreaming }.toMutableList().apply { 
                        removeAll { it.role == "assistant" && it.content.isEmpty() }
                    }
                )
                sendUseCase.execute(historyForApi, config).collect { event ->
                    when (event) {
                        is StreamEvent.Chunk -> {
                            val current = _state.value.messages.toMutableList()
                            if (aiIndex < current.size) {
                                current[aiIndex] = current[aiIndex].copy(
                                    content = current[aiIndex].content + event.text,
                                    isStreaming = true
                                )
                                _state.update { it.copy(messages = current) }
                            }
                        }
                        is StreamEvent.Done -> {
                            val current = _state.value.messages.toMutableList()
                            if (aiIndex < current.size) {
                                current[aiIndex] = current[aiIndex].copy(isStreaming = false)
                                _state.update { it.copy(messages = current) }
                            }
                        }
                        is StreamEvent.Error -> {
                            val current = _state.value.messages.toMutableList()
                            if (aiIndex < current.size) {
                                if (current[aiIndex].content.isEmpty()) {
                                    current[aiIndex] = current[aiIndex].copy(
                                        isStreaming = false,
                                        error = event.message
                                    )
                                } else {
                                    current[aiIndex] = current[aiIndex].copy(isStreaming = false)
                                }
                                _state.update { it.copy(messages = current, error = event.message) }
                            }
                        }
                        is StreamEvent.ToolCallEvent -> { /* TODO */ }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "发送失败") }
            } finally {
                _state.update { it.copy(isSending = false) }
            }
        }
    }

    private fun cancelSend() {
        sendJob?.cancel()
        engine.cancel()
        val current = _state.value.messages.toMutableList()
        if (current.isNotEmpty() && current.last().isStreaming) {
            current[current.size - 1] = current.last().copy(isStreaming = false)
            _state.update { it.copy(messages = current, isSending = false) }
        }
    }

    private fun clearMessages() {
        val config = getConfigUseCase.execute()
        val conv = historyUseCase.create(config.systemPrompt)
        _state.update { it.copy(conversation = conv, messages = emptyList(), error = null) }
    }
}
