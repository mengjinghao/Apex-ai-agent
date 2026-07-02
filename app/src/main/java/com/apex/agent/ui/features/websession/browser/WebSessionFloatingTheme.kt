package com.apex.agent.ui.features.websession.browser

/**
 * Web 浏览器悬浮主题 — Stub。
 * 原 Compose 主题包装器已移除。WebSessionBrowserHost 调用此构造函数为 no-op。
 */
class WebSessionFloatingTheme private constructor() {
    companion object {
        operator fun invoke(
            colorScheme: Any? = null,
            typography: Any? = null,
            content: () -> Unit = {}
        ): WebSessionFloatingTheme = WebSessionFloatingTheme()
    }
}
