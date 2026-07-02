package com.apex.agent.ui.common.displays

import android.content.Context

/**
 * UI 自动化进度覆盖层 — Stub。
 * 原 UI 实现已随 UI 层移除。此 stub 保留业务代码（PhoneAgent / StandardUITools）
 * 编译所需的 API 表面，所有方法为 no-op。恢复 UI 时替换为真实实现即可。
 */
class UIAutomationProgressOverlay private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: UIAutomationProgressOverlay? = null

        @JvmStatic
        fun getInstance(context: Context): UIAutomationProgressOverlay {
            return instance ?: synchronized(this) {
                instance ?: UIAutomationProgressOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    fun show(message: String = ""): UIAutomationProgressOverlay = this
    fun updateProgress(progress: Float, message: String? = null): UIAutomationProgressOverlay = this
    fun setBorderEnabled(enabled: Boolean): UIAutomationProgressOverlay = this
    fun hide(): UIAutomationProgressOverlay = this
    fun dismiss(): UIAutomationProgressOverlay = this
}
