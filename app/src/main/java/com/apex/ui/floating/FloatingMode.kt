package com.apex.ui.floating

/**
 * 悬浮窗模式枚举（com.apex.ui.floating 包）。
 * 与 com.apex.agent.ui.floating.FloatingMode 同名不同包，业务代码同时引用。
 *
 * 完整枚举值与 com.apex.agent.ui.floating.FloatingMode 保持一致。
 */
enum class FloatingMode {
    FULLSCREEN,
    WINDOW,
    BALL,
    RESULT_DISPLAY,
    SCREEN_OCR,
    HALF_SCREEN,
    MINIMIZED,
    PIP
}
