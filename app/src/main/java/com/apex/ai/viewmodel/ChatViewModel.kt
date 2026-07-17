package com.apex.ai.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.ai.data.ApiConfigManager
import com.apex.ai.data.ChatMessage
import com.apex.ai.service.AIServiceFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var sendJob: Job? = null
    private var currentService: com.apex.ai.service.AIService? = null

    fun updateInput(text: String) { _inputText.value = text }

    fun send(context: Context) {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _isSending.value) return

        val config = ApiConfigManager.getApiConfig(context)
        if (config.apiKey.isEmpty()) {
            _error.value = "请先在设置中配置 API Key"
            return
        }

        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg
        _inputText.value = ""
        _error.value = null
        _isSending.value = true

        val aiMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)
        _messages.value = _messages.value + aiMsg
        val aiIndex = _messages.value.size - 1

        val aiService = AIServiceFactory.create(config)
        currentService = aiService
        sendJob = viewModelScope.launch {
            try {
                val history = _messages.value.filter { !it.isStreaming }
                aiService.sendMessage(history) { chunk ->
                    val current = _messages.value.toMutableList()
                    if (aiIndex < current.size) {
                        current[aiIndex] = current[aiIndex].copy(
                            content = current[aiIndex].content + chunk,
                            isStreaming = true
                        )
                        _messages.value = current
                    }
                }
                val final = _messages.value.toMutableList()
                if (aiIndex < final.size) {
                    final[aiIndex] = final[aiIndex].copy(isStreaming = false)
                    _messages.value = final
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "发送失败"
                val cleaned = _messages.value.toMutableList()
                if (aiIndex < cleaned.size && cleaned[aiIndex].content.isEmpty()) {
                    cleaned.removeAt(aiIndex)
                    _messages.value = cleaned
                }
            } finally {
                _isSending.value = false
                currentService = null
            }
        }
    }

    fun cancelSend() {
        sendJob?.cancel()
        currentService?.cancel()
        currentService = null
        _isSending.value = false
        val current = _messages.value.toMutableList()
        if (current.isNotEmpty() && current.last().isStreaming) {
            current[current.size - 1] = current.last().copy(isStreaming = false)
            _messages.value = current
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _error.value = null
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        runCatching { currentService?.cancel() }
    }
}
