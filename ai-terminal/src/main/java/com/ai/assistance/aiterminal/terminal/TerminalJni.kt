package com.ai.assistance.aiterminal.terminal

import android.util.Log
import com.ai.assistance.aiterminal.terminal.model.SessionState
import com.ai.assistance.aiterminal.terminal.model.TerminalEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * JNI 封装类（对标核心能力 + 事件回调）
 */
class TerminalJni {
    companion object {
        private const val TAG = "TerminalJni"
        
        // 加载JNI库
        init {
            try {
                System.loadLibrary("ai_terminal_jni")
                Log.d(TAG, "JNI library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load JNI library", e)
                throw e
            }
        }
    }

    // 事件通道（响应式，对标Flows）
    private val eventChannel = Channel<TerminalEvent>(Channel.UNLIMITED)
    val eventFlow: Flow<TerminalEvent> = eventChannel.receiveAsFlow()

    // 存储当前会话ID（避免循环依赖）
    @Volatile
    private var currentSessionId: String? = null

    // JNI 回调方法（Native层调用）
    fun postEvent(type: String, data: String, code: Int) {
        // 解析Native事件 -> Kotlin事件
        val sessionId = currentSessionId ?: ""
        val event = when (type) {
            "COMMAND_OUTPUT" -> TerminalEvent.CommandOutput(sessionId, data)
            "DIRECTORY_CHANGED" -> TerminalEvent.DirectoryChanged(sessionId, data)
            "SESSION_STATE_CHANGED" -> TerminalEvent.SessionStateChanged(
                sessionId,
                try { SessionState.valueOf(data) } catch (e: Exception) { SessionState.CREATED }
            )
            "COMMAND_FINISHED" -> TerminalEvent.CommandFinished(sessionId, data, code)
            "ERROR_OCCURRED" -> TerminalEvent.ErrorOccurred(sessionId, data, code)
            else -> return
        }
        // 发送到Flow
        eventChannel.trySend(event)
    }

    // ========== JNI 原生方法 ==========
    external fun initJniCallback(callbackObj: Any)
    external fun createSession(sessionId: String): Boolean
    external fun startSession(sessionId: String, shellType: String = "sh"): Boolean
    external fun executeCommand(sessionId: String, command: String): Boolean
    external fun switchSession(sessionId: String): Boolean
    external fun changeDirectory(sessionId: String, path: String): Boolean
    external fun getCurrentDirectory(sessionId: String): String?
    external fun suspendSession(sessionId: String)
    external fun resumeSession(sessionId: String)
    external fun closeSession(sessionId: String): Boolean
    external fun closeAllSessions()
    external fun getCurrentSessionId(): String?
    external fun cleanup()

    // ========== 封装方法 ==========
    /**
     * 更新当前会话ID（从TerminalManager调用）
     */
    fun updateCurrentSessionId(sessionId: String?) {
        currentSessionId = sessionId
    }

    /**
     * 初始化JNI（绑定回调）
     */
    fun init() {
        initJniCallback(this)
    }
}
