package com.apex.agent.orchestration.core

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Singleton




    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, CollaborationSession>()
    private val connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    private val _events = MutableSharedFlow<CollaborationEvent>()

    val events: SharedFlow<CollaborationEvent> = _events
    val connections: StateFlow<Map<String, ConnectionState>> = connectionStates

    suspend fun createSession(taskId: String, participants: Set<String>): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = CollaborationSession(
            sessionId = sessionId,
            taskId = taskId,
            participants = participants.toMutableSet()
        )
        participants.forEach { updateConnection(it, true) }
        emitEvent(CollaborationEvent.EventType.AGENT_JOINED, sessionId, participants.firstOrNull() ?: "")
        return sessionId
    }

    suspend fun joinSession(sessionId: String, agentId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        session.participants.add(agentId)
        updateConnection(agentId, true)
        emitEvent(CollaborationEvent.EventType.AGENT_JOINED, sessionId, agentId)
        return true
    }

    suspend fun leaveSession(sessionId: String, agentId: String) {
        val session = sessions[sessionId] ?: return
        session.participants.remove(agentId)
        updateConnection(agentId, false)
        emitEvent(CollaborationEvent.EventType.AGENT_LEFT, sessionId, agentId)
    }

    suspend fun updateState(sessionId: String, agentId: String, key: String, value: Any) {
        val session = sessions[sessionId] ?: return
        session.state[key] = value
        emitEvent(
            CollaborationEvent.EventType.STATE_CHANGED,
            sessionId,
            agentId,
            mapOf("key" to key, "value" to value)
        )
    }

    fun getSession(sessionId: String): CollaborationSession? = sessions[sessionId]
    fun getSessionState(sessionId: String): Map<String, Any>? = sessions[sessionId]?.state?.toMap()

    fun shutdown() {
        scope.cancel()
        sessions.clear()
    }

    private fun updateConnection(agentId: String, connected: Boolean) {
        connectionStates.value = connectionStates.value + (
            agentId to ConnectionState(agentId, connected, System.currentTimeMillis())
        )
    }

    private suspend fun emitEvent(type: CollaborationEvent.EventType, sessionId: String, agentId: String, data: Map<String, Any> = emptyMap()) {
        _events.emit(CollaborationEvent(type, sessionId, agentId, data))
    }
}
