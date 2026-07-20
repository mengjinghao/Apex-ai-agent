package com.ai.assistance.aiterminal.terminal

import android.util.Log
import com.ai.assistance.aiterminal.terminal.model.Session
import com.ai.assistance.aiterminal.terminal.model.SessionState
import com.ai.assistance.aiterminal.terminal.model.TerminalEvent
import com.ai.assistance.aiterminal.terminal.model.TerminalState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 终端管理器（原ApexTerminalCore的TerminalManager）
 * 单例入口，统一管理会话、状态、事件
 */
class TerminalManager private constructor() {
    companion object {
        private const val TAG = "TerminalManager"
        // D-3: a session with no activity for this long is auto-closed.
        private const val IDLE_TIMEOUT_MS = 30L * 60 * 1000           // 30 min
        // D-3: no session may live longer than this, even if still active.
        private const val MAX_LIFETIME_MS = 24L * 60 * 60 * 1000      // 24 h
        // D-3: how often the reaper scans for idle / expired sessions.
        private const val REAPER_INTERVAL_MS = 60L * 1000             // 60 s
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

    // PERF-11: 每命令 StateFlow emit 会分配新 HashMap + 触发所有终端 UI 重组。
    // 改为 SharedFlow<CommandEvent> 投递单命令事件,terminalState 只在会话增删/状态变化时 emit。
    // 需要逐命令通知的调用方订阅 commandEvents 而非 terminalState。
    private val _commandEvents = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 256)
    val commandEvents: SharedFlow<CommandEvent> = _commandEvents.asSharedFlow()

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
        // D-3: session idle/lifetime reaper. Sessions created via
        // createSession live until closeSession is explicitly called —
        // without this guard a leaked/forgotten session pins a PTY + sh
        // subprocess forever. Only closes idle sessions, so no in-flight
        // command is interrupted.
        scope.launch {
            while (isActive) {
                delay(REAPER_INTERVAL_MS)
                try {
                    reapIdleSessions()
                } catch (e: Exception) {
                    Log.w(TAG, "session reaper error", e)
                }
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
            touchSession(sessionId)  // D-3
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
            // PERF-11: 不再每命令 _terminalState.update (会分配新 HashMap + 触发全量重组)。
            // commandHistory 原地追加 (Session 内部状态,向后兼容 getRecentCommands)。
            _terminalState.value.sessions[sessionId]?.commandHistory?.add(command)
            // 记录命令到历史管理器
            CommandHistoryManager.instance.recordCommand(sessionId, command)
            // 逐命令事件投递给订阅者 (替代原 terminalState emit)
            _commandEvents.tryEmit(CommandEvent.Executed(sessionId, command))
            // D-3: refresh idle timer so the reaper does not close an active session.
            touchSession(sessionId)
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
            touchSession(sessionId)  // D-3
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
            touchSession(sessionId)  // D-3
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
        touchSession(sessionId)  // D-3
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
        // TERM-FIX-4A / I-7: flush debounced command history to disk before
        // tearing down. Without this, commands recorded within the last
        // SAVE_DEBOUNCE_MS window (5s) would be lost on service shutdown.
        // Called from TerminalService.onDestroy().
        try {
            CommandHistoryManager.instance.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        closeAllSessions()
        jni.cleanup()
        scope.cancel()
    }

    // ========== 内部方法 ==========

    // D-3: refresh lastActivityAt for a session so the idle reaper does
    // not close it. Creates a new Session instance (copy) so StateFlow
    // equality is violated and collectors actually receive the update.
    //
    // PERF-11: 限流 — 1s 内多次 touchSession 只 emit 一次 StateFlow。
    // reaper 每 60s 扫描一次,1s 粒度绰绰有余。executeCommand 高频调用时
    // terminalState 不再逐命令重组。
    @Volatile
    private var lastTouchEmitTime = 0L
    private fun touchSession(sessionId: String) {
        val now = System.currentTimeMillis()
        if (now - lastTouchEmitTime < 1000) return
        lastTouchEmitTime = now
        _terminalState.update { state ->
            val existing = state.sessions[sessionId] ?: return@update state
            val updated = existing.copy(lastActivityAt = System.currentTimeMillis())
            val newSessions = state.sessions.toMutableMap()
            newSessions[sessionId] = updated
            state.copy(sessions = newSessions)
        }
    }

    // D-3: close sessions idle > IDLE_TIMEOUT_MS or alive > MAX_LIFETIME_MS.
    // Invoked every REAPER_INTERVAL_MS by the reaper coroutine in init.
    // Only idle sessions are closed, so no in-flight command is interrupted.
    private fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        val toClose = _terminalState.value.sessions.values.filter {
            (now - it.lastActivityAt > IDLE_TIMEOUT_MS) ||
                (now - it.createdAt > MAX_LIFETIME_MS)
        }
        if (toClose.isEmpty()) return
        for (s in toClose) {
            val idleSec = (now - s.lastActivityAt) / 1000
            val ageSec = (now - s.createdAt) / 1000
            Log.w(
                TAG,
                "Auto-closing session ${s.sessionId}: idle=${idleSec}s age=${ageSec}s " +
                    "(limits idle=${IDLE_TIMEOUT_MS / 1000}s age=${MAX_LIFETIME_MS / 1000}s)"
            )
            closeSession(s.sessionId)
        }
    }
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

/**
 * PERF-11: 逐命令事件流。替代原 executeCommand 内的 _terminalState.update —
 * 调用方按需订阅 [TerminalManager.commandEvents] 获取单命令通知,
 * 不再触发 terminalState 全量重组 (避免每命令分配新 HashMap)。
 */
sealed class CommandEvent {
    /** 命令已成功提交到会话。 */
    data class Executed(val sessionId: String, val command: String) : CommandEvent()
}
