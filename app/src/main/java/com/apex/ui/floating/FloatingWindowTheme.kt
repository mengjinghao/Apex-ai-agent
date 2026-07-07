package com.apex.ui.floating

/**
 * 悬浮窗主题 — Stub。
 * 原 Compose 主题包装器已移除。FloatingWindowManager 调用此构造函数为 no-op。
 */
class FloatingWindowTheme private constructor() {
    companion object {
        operator fun invoke(
            colorScheme: Any? = null,
            typography: Any? = null,
            content: () -> Unit = {}
        ): FloatingWindowTheme = FloatingWindowTheme()
    }
}
