package com.apex.agent.ui.common.displays

import android.content.Context

/**
 * UI 操作覆盖层 — Stub。
 * 提供点击/手势等操作的视觉反馈，原 UI 实现已移除。
 */
class UIOperationOverlay private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: UIOperationOverlay? = null

        @JvmStatic
        fun getInstance(context: Context): UIOperationOverlay {
            return instance ?: synchronized(this) {
                instance ?: UIOperationOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    fun showClick(x: Int, y: Int): UIOperationOverlay = this
    fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int): UIOperationOverlay = this
    fun showLongPress(x: Int, y: Int): UIOperationOverlay = this
    fun hide(): UIOperationOverlay = this
    fun dismiss(): UIOperationOverlay = this
}
