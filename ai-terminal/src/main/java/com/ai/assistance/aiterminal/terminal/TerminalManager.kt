package com.ai.assistance.aiterminal.terminal

import com.ai.assistance.aiterminal.terminal.model.Session
import com.ai.assistance.aiterminal.terminal.model.SessionState
import com.ai.assistance.aiterminal.terminal.model.TerminalEvent
import com.ai.assistance.aiterminal.terminal.model.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 终端管理器（原ApexTerminalCore的TerminalManager）
 * 单例入口，统一管理会话、状态、事件
 */
class TerminalManager private constructor() {
    companion object {
        private var appContext: android.content.Context? = null

        // 单例实现
        val instance: TerminalManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            TerminalManager()
        }

        fun getInstance(context: android.content.Context): TerminalManager {
            appContext = context.applicationContext
            return instance
        }
    }

    // JNI实例
    private val jni = TerminalJni()

    // 终端全局状态（响应式，对标状态管理）
    private val _terminalState = MutableStateFlow(TerminalState())
    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()

    // 事件流（对标事件通知）
    val eventFlow = jni.eventFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 当前会话ID
    var currentSessionId: String? = null
        private set

    init {
        // 初始化JNI
        jni.init()
        // 监听JNI事件，更新状态
        scope.launch {
            eventFlow.collect { event ->
                updateStateFromEvent(event)
            }
        }
    }

    // ========== 会话管理（对标核心能力） ==========
    /**
     * 创建会话
     */
    fun createSession(sessionId: String): Boolean {
        val success = jni.createSession(sessionId)
        if (success) {
            _terminalState.update { state ->
                val newSessions = state.sessions.toMutableMap()
                newSessions[sessionId] = Session(sessionId)
                val newCurrentId = currentSessionId ?: sessionId
                state.copy(
                    sessions = newSessions,
                    currentSessionId = newCurrentId
                )
            }
            currentSessionId = currentSessionId ?: sessionId
            if (currentSessionId != null) {
                jni.updateCurrentSessionId(currentSessionId)
            }
        }
        return success
    }

    /**
     * 启动会话
     */
    fun startSession(sessionId: String, shellType: String = "sh"): Boolean {
        val success = jni.startSession(sessionId, shellType)
        if (success) {
            updateSessionState(sessionId, SessionState.RUNNING)
        }
        return success
    }

    /**
     * 创建并启动会话（快捷方法）
     */
    fun createAndStartSession(sessionId: String, shellType: String = "sh"): Boolean {
        return createSession(sessionId) && startSession(sessionId, shellType)
    }

    /**
     * 执行命令
     */
    fun executeCommand(sessionId: String, command: String): Boolean {
        val success = jni.executeCommand(sessionId, command)
        if (success) {
            _terminalState.update { state ->
                val newSessions = state.sessions.toMutableMap()
                newSessions[sessionId]?.commandHistory?.add(command)
                state.copy(sessions = newSessions)
            }
            // 记录命令到历史管理器
            CommandHistoryManager.instance.recordCommand(sessionId, command)
        }
        return success
    }

    /**
     * 切换会话
     */
    fun switchSession(sessionId: String): Boolean {
        if (!_terminalState.value.sessions.containsKey(sessionId)) {
            return false
        }
        
        val success = jni.switchSession(sessionId)
        if (success) {
            currentSessionId = sessionId
            _terminalState.update { it.copy(currentSessionId = sessionId) }
            jni.updateCurrentSessionId(sessionId)
        }
        return success
    }

    /**
     * 切换工作目录
     */
    fun changeDirectory(sessionId: String, path: String): Boolean {
        val success = jni.changeDirectory(sessionId, path)
        if (success) {
            _terminalState.update { state ->
                val newSessions = state.sessions.toMutableMap()
                newSessions[sessionId]?.currentDir = path
                state.copy(sessions = newSessions)
            }
        }
        return success
    }

    /**
     * 获取当前工作目录
     */
    fun getCurrentDirectory(sessionId: String): String? {
        return jni.getCurrentDirectory(sessionId)
    }

    /**
     * 挂起会话
     */
    fun suspendSession(sessionId: String) {
        jni.suspendSession(sessionId)
        updateSessionState(sessionId, SessionState.SUSPENDED)
    }

    /**
     * 恢复会话
     */
    fun resumeSession(sessionId: String) {
        jni.resumeSession(sessionId)
        updateSessionState(sessionId, SessionState.RUNNING)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String): Boolean {
        val success = jni.closeSession(sessionId)
        if (success) {
            _terminalState.update { state ->
                val newSessions = state.sessions.toMutableMap()
                newSessions.remove(sessionId)
                val newCurrentId = if (state.currentSessionId == sessionId) {
                    newSessions.keys.firstOrNull() ?: ""
                } else {
                    state.currentSessionId
                }
                state.copy(
                    sessions = newSessions,
                    currentSessionId = newCurrentId
                )
            }
            currentSessionId = _terminalState.value.currentSessionId.takeIf { it.isNotEmpty() }
            jni.updateCurrentSessionId(currentSessionId)
        }
        return success
    }

    /**
     * 关闭所有会话
     */
    fun getRecentCommands(sessionId: String, limit: Int = 20): List<String> {
        val session = getSession(sessionId) ?: return emptyList()
        return session.commandHistory.takeLast(limit)
    }

    fun closeAllSessions() {
        jni.closeAllSessions()
        _terminalState.update {
            it.copy(
                sessions = emptyMap(),
                currentSessionId = ""
            )
        }
        currentSessionId = null
        jni.updateCurrentSessionId(null)
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? {
        return _terminalState.value.sessions[sessionId]
    }

    /**
     * 获取当前会话
     */
    fun getCurrentSession(): Session? {
        val id = currentSessionId ?: _terminalState.value.currentSessionId
        return id.takeIf { it.isNotEmpty() }?.let { getSession(it) }
    }

    /**
     * 获取所有会话ID
     */
    fun getAllSessionIds(): List<String> {
        return _terminalState.value.sessions.keys.toList()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        closeAllSessions()
        jni.cleanup()
        scope.cancel()
    }

    // ========== 内部方法 ==========
    /**
     * 从事件更新状态
     */
    private fun updateStateFromEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.SessionStateChanged -> {
                updateSessionState(event.sessionId, event.state)
            }
            is TerminalEvent.DirectoryChanged -> {
                _terminalState.update { state ->
                    val newSessions = state.sessions.toMutableMap()
                    newSessions[event.sessionId]?.currentDir = event.newDir
                    state.copy(sessions = newSessions)
                }
            }
            else -> {}
        }
    }

    /**
     * 更新会话状态
     */
    private fun updateSessionState(sessionId: String, newState: SessionState) {
        _terminalState.update { currentState ->
            val newSessions = currentState.sessions.toMutableMap()
            newSessions[sessionId]?.state = newState
            currentState.copy(sessions = newSessions)
        }
    }
}
