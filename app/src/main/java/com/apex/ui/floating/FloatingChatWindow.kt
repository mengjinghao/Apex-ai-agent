package com.apex.ui.floating

/**
 * 悬浮聊天窗口 — Stub。
 * 原 Compose 组件用于渲染悬浮窗 UI，已移除。
 * FloatingWindowManager 调用此构造函数为 no-op。
 */
class FloatingChatWindow private constructor() {
    companion object {
        operator fun invoke(
            messages: List<Any?> = emptyList(),
            width: Int = 0,
            height: Int = 0,
            content: () -> Unit = {}
        ): FloatingChatWindow = FloatingChatWindow()
    }
}
