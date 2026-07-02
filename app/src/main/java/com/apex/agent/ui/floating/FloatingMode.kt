package com.apex.agent.ui.floating

/**
 * 悬浮窗模式枚举（com.apex.agent.ui.floating 包）。
 * 注意：com.apex.ui.floating.FloatingMode 是另一个同名枚举，两者并存。
 *
 * 业务代码使用的枚举值：
 * - FULLSCREEN      全屏模式
 * - WINDOW          窗口模式
 * - BALL            悬浮球模式
 * - RESULT_DISPLAY  结果展示模式
 * - SCREEN_OCR      屏幕OCR模式
 * - HALF_SCREEN     半屏模式
 * - MINIMIZED       最小化模式
 * - PIP             画中画模式
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
