package com.apex.ui.common.displays

import android.content.Context

/**
 * UI 自动化进度覆盖层（com.apex.ui 包）— Stub。
 * 与 com.apex.agent.ui.common.displays.UIAutomationProgressOverlay 同名，
 * 但属于不同的包路径。业务代码同时引用两者。
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
