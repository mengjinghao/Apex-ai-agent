package com.apex.ui.features.toolbox.screens.uidebugger

/**
 * UI 调试器 ViewModel — Stub。
 * 原 ViewModel 用于调试 UI 树，UI 移除后保留 getInstance API。
 */
class UIDebuggerViewModel private constructor() {

    companion object {
        @Volatile private var instance: UIDebuggerViewModel? = null

        @JvmStatic
        fun getInstance(): UIDebuggerViewModel {
            return instance ?: synchronized(this) {
                instance ?: UIDebuggerViewModel().also { instance = it }
            }
        }
    }

    fun start() {}
    fun stop() {}
    fun captureHierarchy(): String = ""
    fun clear() {}
}
