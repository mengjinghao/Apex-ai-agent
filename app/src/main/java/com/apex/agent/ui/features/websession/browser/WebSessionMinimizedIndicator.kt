package com.apex.agent.ui.features.websession.browser

/**
 * Web 浏览器最小化指示器 — Stub。
 */
class WebSessionMinimizedIndicator private constructor() {
    companion object {
        operator fun invoke(
            title: String = "",
            onClick: () -> Unit = {}
        ): WebSessionMinimizedIndicator = WebSessionMinimizedIndicator()
    }
}
