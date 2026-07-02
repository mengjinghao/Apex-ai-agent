package com.apex.ui.features.chat.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 状态委托 — Stub。
 * 原用于在 Service 和 UI 之间共享聊天 UI 状态（消息列表、加载状态等），
 * UI 移除后保留为简单状态容器，业务代码仍可读写状态。
 */
class UiStateDelegate {

    data class UiState(
        val messages: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val isWaitingForUser: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun updateState(update: (UiState) -> UiState) {
        _state.value = update(_state.value)
    }

    fun getCurrentState(): UiState = _state.value

    fun reset() {
        _state.value = UiState()
    }
}
