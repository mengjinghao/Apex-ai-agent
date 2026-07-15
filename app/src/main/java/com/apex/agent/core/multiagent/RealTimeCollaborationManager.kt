package com.apex.agent.core.multiagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.apex.agent.core.multiagent.Operation

class RealTimeCollaborationManager(private val context: Context) {

    companion object {
        private const val TAG = "RealTimeCollaborationManager"
        private const val RECONNECT_INTERVAL = 3000L
        private const val HEARTBEAT_INTERVAL = 5000L
        private const val MAX_PENDING_OPS = 1000
        private const val SYNC_INTERVAL = 1000L
    }
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val webSocketManager = WebSocketManager()
        private val crdtEngine = CRDTEngine()
        private val otEngine = OTEngine()
        private val collaborationSessions = ConcurrentHashMap<String, CollaborationSession>()
        private val agentStates = ConcurrentHashMap<String, AgentCollaborationState>()
        private val pendingOperations = ConcurrentHashMap<String, MutableList<Operation>>()
        private val operationHistory = ConcurrentHashMap<String, MutableList<Operation>>()
        private val _connectionState = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
        val connectionState: StateFlow<Map<String, ConnectionState>> = _connectionState

    private val _collaborationEvents = MutableSharedFlow<CollaborationEvent>()
        val collaborationEvents: SharedFlow<CollaborationEvent> = _collaborationEvents

    private val _syncProgress = MutableStateFlow(SyncProgress())
        val syncProgress: StateFlow<SyncProgress> = _syncProgress

    private var syncJob: Job? = null

    init {
        startSyncLoop()
    }

    data class CollaborationSession(
        val sessionId: String,
        val taskId: String,
        val participants: MutableSet<String>,
        val state: MutableMap<String, Any>,
        val vectorClock: VectorClock,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class VectorClock(
        val clocks: MutableMap<String, Long> = ConcurrentHashMap()
    ) {
        fun increment(agentId: String) {
            clocks[agentId] = (clocks[agentId] ?: 0) + 1
        }
        fun merge(other: VectorClock) {
            other.clocks.forEach { (agentId, time) ->
                clocks[agentId] = maxOf(clocks[agentId] ?: 0, time)
            }
        }
        fun compare(other: VectorClock): Int {
            var thisGreater = false
            var otherGreater = false

            val allAgents = (clocks.keys + other.clocks.keys)
            allAgents.forEach { agent ->
                val thisTime = clocks[agent] ?: 0
                val otherTime = other.clocks[agent] ?: 0
                if (thisTime > otherTime) thisGreater = true
                if (otherTime > thisTime) otherGreater = true
            }
        return when {
                thisGreater && !otherGreater -> 1
                otherGreater && !thisGreater -> -1
                else -> 0
            }
        }
    }

    data class AgentCollaborationState(
        val agentId: String,
        var cursor: Int = 0,
        var selection: IntRange? = null,
        var lastKnownState: String = "",
        var pendingOps: MutableList<Operation> = mutableListOf(),
        var acknowledgedOps: MutableList<Operation> = mutableListOf()
    )

    data class Operation(
        val opId: String,
        val agentId: String,
        val type: OperationType,
        val payload: Map<String, Any>,
        val timestamp: Long,
        val vectorClock: VectorClock,
        var status: OpStatus = OpStatus.PENDING,
        var serverTimestamp: Long = 0
    ) {
        enum class OperationType {
            INSERT, DELETE, UPDATE, MOVE, COPY, PASTE, LOCK, UNLOCK
        }

        enum class OpStatus {
            PENDING, SENT, ACKNOWLEDGED, REJECTED
        }
    }

    data class ConnectionState(
        val agentId: String,
        val connected: Boolean,
        val lastHeartbeat: Long,
        val latency: Long = 0
    )

    data class SyncProgress(
        val pendingOps: Int = 0,
        val syncedOps: Int = 0,
        val conflicts: Int = 0,
        val lastSyncTime: Long = 0
    )

    data class CollaborationEvent(
        val type: EventType,
        val sessionId: String,
        val agentId: String,
        val data: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class EventType {
            AGENT_JOINED, AGENT_LEFT, STATE_CHANGED, CONFLICT_DETECTED, CONFLICT_RESOLVED,
            OPERATION_APPLIED, SYNC_COMPLETED, HEARTBEAT_MISSED, CONNECTION_LOST
        }
    }

    data class ConflictRecord(
        val conflictId: String,
        val sessionId: String,
        val operations: List<Operation>,
        val resolvedOperation: Operation?,
        val resolution: ConflictResolution,
        val timestamp: Long
    ) {
        enum class ConflictResolution {
            AUTO_MERGE, AGENT_VOTE, SERVER_AUTHORITY, ROLLBACK
        }
    }

    suspend fun createSession(taskId: String, initialParticipants: Set<String>): String {
        val sessionId = UUID.randomUUID().toString()
        val session = CollaborationSession(
            sessionId = sessionId,
            taskId = taskId,
            participants = initialParticipants.toMutableSet(),
            state = mutableMapOf(),
            vectorClock = VectorClock()
        )

        collaborationSessions[sessionId] = session
        pendingOperations[sessionId] = mutableListOf()
        operationHistory[sessionId] = mutableListOf()

        initialParticipants.forEach { agentId ->
            agentStates[agentId] = AgentCollaborationState(agentId)
            session.vectorClock.clocks[agentId] = 0
        }

        webSocketManager.connect(sessionId, initialParticipants)

        _connectionState.value = _connectionState.value + mapOf(
            agentId to ConnectionState(
                agentId = agentId,
                connected = true,
                lastHeartbeat = System.currentTimeMillis()
            )
        )

        _collaborationEvents.emit(
            CollaborationEvent(
                type = CollaborationEvent.EventType.AGENT_JOINED,
                sessionId = sessionId,
                agentId = initialParticipants.first()
            )
        )
        return sessionId
    }

    suspend fun joinSession(sessionId: String, agentId: String): Boolean {
        val session = collaborationSessions[sessionId] ?: return false

        session.participants.add(agentId)
        session.vectorClock.clocks[agentId] = 0
        agentStates[agentId] = AgentCollaborationState(agentId)

        _collaborationEvents.emit(
            CollaborationEvent(
                type = CollaborationEvent.EventType.AGENT_JOINED,
                sessionId = sessionId,
                agentId = agentId
            )
        )
        return true
    }

    suspend fun leaveSession(sessionId: String, agentId: String) {
        val session = collaborationSessions[sessionId] ?: return

        session.participants.remove(agentId)
        session.vectorClock.increment(agentId)
        agentStates.remove(agentId)

        _collaborationEvents.emit(
            CollaborationEvent(
                type = CollaborationEvent.EventType.AGENT_LEFT,
                sessionId = sessionId,
                agentId = agentId
            )
        )
    }

    suspend fun applyOperation(
        sessionId: String,
        agentId: String,
        type: Operation.OperationType,
        payload: Map<String, Any>
    ): Operation? {
        val session = collaborationSessions[sessionId] ?: return null
        val state = agentStates[agentId] ?: return null

        session.vectorClock.increment(agentId)
        val operation = Operation(
            opId = UUID.randomUUID().toString(),
            agentId = agentId,
            type = type,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            vectorClock = VectorClock().also { it.clocks.putAll(session.vectorClock.clocks) },
            status = Operation.OpStatus.PENDING
        )

        state.pendingOps.add(operation)
        pendingOperations[sessionId]?.add(operation)
        operationHistory[sessionId]?.add(operation)
        val transformedOps = transformAgainstPending(operation, sessionId)

        transformedOps.forEach { transformedOp ->
            crdtEngine.applyOperation(transformedOp)
            otEngine.applyOperation(transformedOp)
        }

        webSocketManager.sendOperation(sessionId, transformedOps)

        updateSyncProgress()
        return operation
    }
        private fun transformAgainstPending(operation: Operation, sessionId: String): List<Operation> {
        val pending = pendingOperations[sessionId] ?: return listOf(operation)
        val transformed = mutableListOf<Operation>()

        pending.filter { it.agentId != operation.agentId }.forEach { pendingOp ->
            val transformedOp = otEngine.transform(operation, pendingOp)
        if (transformedOp != null) {
                transformed.add(transformedOp)
            }
        }
        return if (transformed.isEmpty()) listOf(operation) else transformed
    }

    suspend fun resolveConflict(sessionId: String, conflictId: String, resolution: ConflictRecord.ConflictResolution): Boolean {
        val conflict = findConflict(sessionId, conflictId) ?: return false

        val resolvedOp = when (resolution) {
            ConflictRecord.ConflictResolution.AUTO_MERGE -> {
                crdtEngine.mergeOperations(conflict.operations)
            }
            ConflictRecord.ConflictResolution.SERVER_AUTHORITY -> {
                conflict.operations.maxByOrNull { it.timestamp }
            }
            ConflictRecord.ConflictResolution.ROLLBACK -> {
                null
            }
            ConflictRecord.ConflictResolution.AGENT_VOTE -> {
                performAgentVote(sessionId, conflict.operations)
            }
        }

        resolvedOp?.let { op ->
            crdtEngine.applyOperation(op)
            webSocketManager.sendOperation(sessionId, listOf(op))
        }

        _collaborationEvents.emit(
            CollaborationEvent(
                type = CollaborationEvent.EventType.CONFLICT_RESOLVED,
                sessionId = sessionId,
                agentId = "",
                data = mapOf("conflictId" to conflictId, "resolution" to resolution.name)
            )
        )
        return true
    }
        private fun performAgentVote(sessionId: String, operations: List<Operation>): Operation? {
        val votes = mutableMapOf<String, Int>()
        operations.forEach { op ->
            votes[op.agentId] = (votes[op.agentId] ?: 0) + 1
        }
        return operations.maxByOrNull { votes[it.agentId] ?: 0 }
    }
        private fun findConflict(sessionId: String, conflictId: String): ConflictRecord? {
        return null
    }
        fun getSessionState(sessionId: String): Map<String, Any>? {
        return collaborationSessions[sessionId]?.state
    }
        fun getAgentState(agentId: String): AgentCollaborationState? {
        return agentStates[agentId]
    }
        private fun startSyncLoop() {
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL)
                performSync()
            }
        }
    }
        private suspend fun performSync() {
        collaborationSessions.keys.forEach { sessionId ->
            val session = collaborationSessions[sessionId] ?: return@forEach

            val remoteOps = webSocketManager.receiveOperations(sessionId)

            remoteOps.forEach { remoteOp ->
                session.vectorClock.merge(remoteOp.vectorClock)
        val localOps = pendingOperations[sessionId] ?: emptyList()
        val transformedOp = otEngine.transformAgainstHistory(remoteOp, localOps)
        if (transformedOp != null) {
                    crdtEngine.applyOperation(transformedOp)
                    operationHistory[sessionId]?.add(transformedOp)
                }

                checkAndResolveConflicts(sessionId, remoteOp, transformedOp)
            }
        val acknowledgedOps = webSocketManager.getAcknowledgedOperations(sessionId)
            acknowledgedOps.forEach { ackOpId ->
                pendingOperations[sessionId]?.removeAll { it.opId == ackOpId }
                agentStates.values.forEach { state ->
                    state.pendingOps.removeAll { it.opId == ackOpId }
                    state.acknowledgedOps.add(
                        Operation(
                            opId = ackOpId,
                            agentId = "",
                            type = Operation.OperationType.UPDATE,
                            payload = emptyMap(),
                            timestamp = 0,
                            vectorClock = VectorClock(),
                            status = Operation.OpStatus.ACKNOWLEDGED
                        )
                    )
                }
            }

            updateSyncProgress()
        }
    }
        private suspend fun checkAndResolveConflicts(sessionId: String, remoteOp: Operation, localOp: Operation) {
        if (localOp == null) return

        val conflict = ConflictRecord(
            conflictId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            operations = listOf(remoteOp, localOp),
            resolvedOperation = null,
            resolution = ConflictRecord.ConflictResolution.AUTO_MERGE,
            timestamp = System.currentTimeMillis()
        )

        _collaborationEvents.emit(
            CollaborationEvent(
                type = CollaborationEvent.EventType.CONFLICT_DETECTED,
                sessionId = sessionId,
                agentId = remoteOp.agentId,
                data = mapOf("conflictId" to conflict.conflictId)
            )
        )

        _syncProgress.value = _syncProgress.value.copy(conflicts = _syncProgress.value.conflicts + 1)
    }
        private fun updateSyncProgress() {
        val pending = pendingOperations.values.sumOf { it.size }
        val synced = operationHistory.values.sumOf { it.size }

        _syncProgress.value = SyncProgress(
            pendingOps = pending,
            syncedOps = synced,
            conflicts = _syncProgress.value.conflicts,
            lastSyncTime = System.currentTimeMillis()
        )
    }
        fun shutdown() {
        syncJob?.cancel()
        scope.cancel()
        collaborationSessions.keys.forEach { webSocketManager.disconnect(it) }
    }
}

class WebSocketManager {

    private val connections = ConcurrentHashMap<String, WebSocketConnection>()
        private val operationQueues = ConcurrentHashMap<String, MutableList<Operation>>()
        private val acknowledgedOps = ConcurrentHashMap<String, MutableSet<String>>()

    data class WebSocketConnection(
        val sessionId: String,
        val participants: Set<String>,
        var isConnected: Boolean = false,
        var lastHeartbeat: Long = System.currentTimeMillis()
    )
        fun connect(sessionId: String, participants: Set<String>) {
        connections[sessionId] = WebSocketConnection(sessionId, participants, true)
        operationQueues[sessionId] = mutableListOf()
        acknowledgedOps[sessionId] = mutableSetOf()
    }
        fun disconnect(sessionId: String) {
        connections[sessionId]?.isConnected = false
        connections.remove(sessionId)
        operationQueues.remove(sessionId)
        acknowledgedOps.remove(sessionId)
    }
        fun sendOperation(sessionId: String, operations: List<Operation>) {
        operations.forEach { op ->
            operationQueues[sessionId]?.add(op)
        }
    }
        fun receiveOperations(sessionId: String): List<Operation> {
        return operationQueues[sessionId]?.toList()?.also {
            operationQueues[sessionId]?.clear()
        } ?: emptyList()
    }
        fun getAcknowledgedOperations(sessionId: String): Set<String> {
        return acknowledgedOps[sessionId] ?: emptySet()
    }
        fun acknowledgeOperation(sessionId: String, opId: String) {
        acknowledgedOps[sessionId]?.add(opId)
    }
        fun isConnected(sessionId: String): Boolean {
        return connections[sessionId]?.isConnected == true
    }
        fun updateHeartbeat(sessionId: String) {
        connections[sessionId]?.lastHeartbeat = System.currentTimeMillis()
    }
}

class CRDTEngine {

    private val state = ConcurrentHashMap<String, CRDTValue>()
        private val tombstones = ConcurrentHashMap<String, Long>()

    data class CRDTValue(
        val id: String,
        val value: Any,
        val timestamp: Long,
        val agentId: String,
        val vectorClock: Map<String, Long>
    )
        fun applyOperation(operation: Operation): Boolean {
        return try {
            when (operation.type) {
                Operation.OperationType.INSERT -> applyInsert(operation)
                Operation.OperationType.DELETE -> applyDelete(operation)
                Operation.OperationType.UPDATE -> applyUpdate(operation)
                else -> true
            }
        } catch (e: Exception) {
            false
        }
    }
        private fun applyInsert(operation: Operation): Boolean {
        val key = operation.payload["key"] as? String ?: return false
        val value = operation.payload["value"] ?: return false

        val crdtValue = CRDTValue(
            id = operation.opId,
            value = value,
            timestamp = operation.timestamp,
            agentId = operation.agentId,
            vectorClock = operation.vectorClock.clocks
        )

        state[key] = crdtValue
        return true
    }
        private fun applyDelete(operation: Operation): Boolean {
        val key = operation.payload["key"] as? String ?: return false
        state.remove(key)
        tombstones[operation.opId] = operation.timestamp
        return true
    }
        private fun applyUpdate(operation: Operation): Boolean {
        val key = operation.payload["key"] as? String ?: return false
        val value = operation.payload["value"] ?: return false

        val existing = state[key]
        if (existing != null && existing.timestamp > operation.timestamp) {
            return false
        }

        state[key] = CRDTValue(
            id = operation.opId,
            value = value,
            timestamp = operation.timestamp,
            agentId = operation.agentId,
            vectorClock = operation.vectorClock.clocks
        )
        return true
    }
        fun mergeOperations(operations: List<Operation>): Operation? {
        if (operations.isEmpty()) return null

        return operations.maxByOrNull { it.timestamp }
    }
        fun getValue(key: String): Any? {
        return state[key]?.value
    }
        fun getAllValues(): Map<String, Any> {
        return state.mapValues { it.value.value }
    }
}

class OTEngine {

    private val documentStates = ConcurrentHashMap<String, String>()
        private val pendingOperations = ConcurrentHashMap<String, MutableList<Operation>>()
        fun applyOperation(operation: Operation): Boolean {
        return try {
            when (operation.type) {
                Operation.OperationType.INSERT -> applyInsert(operation)
                Operation.OperationType.DELETE -> applyDelete(operation)
                Operation.OperationType.UPDATE -> applyUpdate(operation)
                else -> true
            }
        } catch (e: Exception) {
            false
        }
    }
        private fun applyInsert(operation: Operation): Boolean {
        val docId = operation.payload["docId"] as? String ?: return false
        val position = operation.payload["position"] as? Int ?: 0
        val content = operation.payload["content"] as? String ?: ""
        val currentState = documentStates[docId] ?: ""
        val newState = StringBuilder(currentState).insert(position, content).toString()
        documentStates[docId] = newState

        return true
    }
        private fun applyDelete(operation: Operation): Boolean {
        val docId = operation.payload["docId"] as? String ?: return false
        val position = operation.payload["position"] as? Int ?: 0
        val length = operation.payload["length"] as? Int ?: 1

        val currentState = documentStates[docId] ?: ""
        if (position < 0 || position + length > currentState.length) {
            return false
        }
        val newState = StringBuilder(currentState).delete(position, position + length).toString()
        documentStates[docId] = newState

        return true
    }
        private fun applyUpdate(operation: Operation): Boolean {
        return true
    }
        fun transform(op1: Operation, op2: Operation): Operation? {
        if (op1.type == Operation.OperationType.INSERT && op2.type == Operation.OperationType.INSERT) {
            return transformInsertInsert(op1, op2)
        }
        if (op1.type == Operation.OperationType.DELETE && op2.type == Operation.OperationType.DELETE) {
            return transformDeleteDelete(op1, op2)
        }
        if (op1.type == Operation.OperationType.INSERT && op2.type == Operation.OperationType.DELETE) {
            return transformInsertDelete(op1, op2)
        }
        if (op1.type == Operation.OperationType.DELETE && op2.type == Operation.OperationType.INSERT) {
            return transformDeleteInsert(op1, op2)
        }
        return op1
    }
        private fun transformInsertInsert(op1: Operation, op2: Operation): Operation {
        val pos1 = op1.payload["position"] as? Int ?: 0
        val pos2 = op2.payload["position"] as? Int ?: 0

        if (pos1 <= pos2) {
            val newPos2 = pos2 + (op1.payload["content"] as? String ?: "").length
            return op2.copy(payload = op2.payload + mapOf("position" to newPos2))
        }
        return op2
    }
        private fun transformDeleteDelete(op1: Operation, op2: Operation): Operation {
        val pos1 = op1.payload["position"] as? Int ?: 0
        val len1 = op1.payload["length"] as? Int ?: 1
        val pos2 = op2.payload["position"] as? Int ?: 0
        val len2 = op2.payload["length"] as? Int ?: 1

        val end1 = pos1 + len1
        val end2 = pos2 + len2

        return when {
            end1 <= pos2 -> op2
            end2 <= pos1 -> op1.copy(
                payload = op1.payload + mapOf("position" to (pos1 - len2))
            )
            pos1 == pos2 -> op1.copy(payload = op1.payload + mapOf("length" to maxOf(len1, len2)))
            pos1 < pos2 -> op1.copy(payload = op1.payload + mapOf("length" to (len1 - len2)))
            else -> op2.copy(payload = op2.payload + mapOf("length" to (len2 - len1)))
        }
    }
        private fun transformInsertDelete(op1: Operation, op2: Operation): Operation {
        val insertPos = op1.payload["position"] as? Int ?: 0
        val deletePos = op2.payload["position"] as? Int ?: 0
        val deleteLen = op2.payload["length"] as? Int ?: 1

        if (insertPos <= deletePos) {
            return op2.copy(payload = op2.payload + mapOf("position" to (deletePos + 1)))
        }
        return op2
    }
        private fun transformDeleteInsert(op1: Operation, op2: Operation): Operation {
        val deletePos = op1.payload["position"] as? Int ?: 0
        val deleteLen = op1.payload["length"] as? Int ?: 1
        val insertPos = op2.payload["position"] as? Int ?: 0

        if (insertPos <= deletePos) {
            return op2.copy(payload = op2.payload + mapOf("position" to (insertPos - deleteLen).coerceAtLeast(0)))
        }
        return op2
    }
        fun transformAgainstHistory(operation: Operation, history: List<Operation>): Operation? {
        var transformed = operation
        history.forEach { histOp ->
            val newTransformed = transform(transformed, histOp)
        if (newTransformed != null) {
                transformed = newTransformed
            }
        }
        return transformed
    }
        fun getDocumentState(docId: String): String {
        return documentStates[docId] ?: ""
    }
}
