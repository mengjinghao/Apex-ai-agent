package com.apex.agent.ui.features.chat.webview

import android.content.Context
import com.apex.util.AppLogger

/**
 * 本地 Web 服务器 — Stub。
 * 原 UI 层的本地资源服务器实现已移除。此 stub 提供 getInstance/start/stop API。
 * 业务代码调用不会启动真实服务器，但能正常编译。
 */
class LocalWebServer private constructor(private val context: Context) {

    enum class ServerType { WORKSPACE, MARKDOWN, DOCUMENTATION }

    companion object {
        @Volatile private var instance: LocalWebServer? = null

        @JvmStatic
        fun getInstance(context: Context, type: ServerType = ServerType.WORKSPACE): LocalWebServer {
            return instance ?: synchronized(this) {
                instance ?: LocalWebServer(context.applicationContext).also { instance = it }
            }
        }
    }

    fun start(): Boolean {
        AppLogger.w("LocalWebServer", "Stub start() called - no real server running")
        return false
    }

    fun stop() {
        AppLogger.d("LocalWebServer", "Stub stop() called")
    }

    fun isRunning(): Boolean = false

    fun getPort(): Int = -1
}
