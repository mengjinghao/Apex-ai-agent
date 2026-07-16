package com.apex.agent.orchestration.memory

import com.apex.agent.core.memory.unified.SharedMemoryEntry
import com.apex.agent.core.memory.unified.SharedMemoryPool
import com.apex.agent.orchestration.memory.CollaborationSessionMemory
import com.apex.agent.orchestration.memory.Decision
import com.apex.agent.orchestration.memory.DecisionStatus
import com.apex.agent.orchestration.memory.ContextItem
import com.apex.agent.orchestration.memory.ContextCategory
import com.apex.agent.orchestration.memory.ProgressUpdate
import com.apex.agent.orchestration.memory.Artifact
import com.apex.agent.orchestration.memory.ArtifactType
import com.apex.agent.orchestration.memory.AgentMemory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WorkingMemoryManager {

    private val sessions = ConcurrentHashMap<String, CollaborationSessionMemory>()
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val sharedPool: SharedMemoryPool = SharedMemoryPool.getInstance()
    private val artifactCounter = ConcurrentHashMap<String, Int>()

    private fun getLock(sessionId: String): ReentrantLock {
        return sessionLocks.computeIfAbsent(sessionId) { ReentrantLock() }
    }

    fun createSession(sessionId: String, taskId: String, taskDescription: String): CollaborationSessionMemory {
        val session = CollaborationSessionMemory(
            sessionId = sessionId,
            taskId = taskId,
            taskDescription = taskDescription
        )
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): CollaborationSessionMemory? = sessions[sessionId]

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionLocks.remove(sessionId)
    }

    fun registerAgent(sessionId: String, agentId: String, agentName: String, role: String) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            if (session.agents.none { it.agentId == agentId }) {
                session.agents.add(AgentMemory(agentId = agentId, agentName = agentName, role = role))
            }
            session.lastUpdated = System.currentTimeMillis()
        }
    }

    fun updateAgentStatus(sessionId: String, agentId: String, status: String) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            val agent = session.agents.find { it.agentId == agentId } ?: return@withLock
            agent.status = status
            agent.lastActive = System.currentTimeMillis()
            session.lastUpdated = System.currentTimeMillis()
        }
    }

    fun recordMessage(sessionId: String, fromAgent: String, toAgent: String?) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            session.agents.find { it.agentId == fromAgent }?.let {
                it.messagesSent++
                it.lastActive = System.currentTimeMillis()
            }
            if (toAgent != null) {
                session.agents.find { it.agentId == toAgent }?.let {
                    it.messagesReceived++
                }
            }
            session.lastUpdated = System.currentTimeMillis()
        }
    }

    fun addDecision(
        sessionId: String,
        description: String,
        proposedBy: String,
        reasoning: String = ""
    ): Decision {
        val session = sessions[sessionId] ?: error("Session not found: $sessionId")
        return getLock(sessionId).withLock {
            val decision = Decision(
                id = "decision_${System.currentTimeMillis()}_${session.decisions.size}",
                description = description,
                proposedBy = proposedBy,
                reasoning = reasoning
            )
            session.decisions.add(decision)
            session.agents.find { it.agentId == proposedBy }?.decisionsMade?.add(decision.id)
            session.lastUpdated = System.currentTimeMillis()

            writeSharedMemoryEntry(session, "decision: $description", proposedBy, 80)
            decision
        }
    }

    fun voteOnDecision(sessionId: String, decisionId: String, agentId: String, agree: Boolean) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            val decision = session.decisions.find { it.id == decisionId } ?: return@withLock
            if (agree) {
                if (agentId !in decision.agreedBy) decision.agreedBy.add(agentId)
            } else {
                if (agentId !in decision.opposedBy) decision.opposedBy.add(agentId)
            }
            decision.status = DecisionStatus.UNDER_DISCUSSION
            session.lastUpdated = System.currentTimeMillis()
        }
    }

    fun resolveDecision(sessionId: String, decisionId: String, accepted: Boolean) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            val decision = session.decisions.find { it.id == decisionId } ?: return@withLock
            decision.status = if (accepted) DecisionStatus.AGREED else DecisionStatus.REJECTED
            decision.resolvedAt = System.currentTimeMillis()
            session.lastUpdated = System.currentTimeMillis()

            if (accepted) {
                addContextInternal(session, "decision_$decisionId", decision.description, decision.proposedBy, ContextCategory.FACT)
            }
        }
    }

    fun addContext(
        sessionId: String,
        key: String,
        value: String,
        source: String = "",
        category: ContextCategory = ContextCategory.GENERAL,
        confidence: Float = 1.0f
    ) {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            addContextInternal(session, key, value, source, category, confidence)
        }
    }

    private fun addContextInternal(
        session: CollaborationSessionMemory,
        key: String,
        value: String,
        source: String,
        category: ContextCategory,
        confidence: Float
    ) {
        val existing = session.sharedContext.indexOfFirst { it.key == key && it.isActive }
        if (existing >= 0) {
            session.sharedContext[existing] = session.sharedContext[existing].copy(
                value = value, confidence = confidence, timestamp = System.currentTimeMillis()
            )
        } else {
            session.sharedContext.add(
                ContextItem(key = key, value = value, source = source, category = category, confidence = confidence)
            )
        }
        session.lastUpdated = System.currentTimeMillis()
    }

    fun getContext(sessionId: String, category: ContextCategory? = null): List<ContextItem> {
        val session = sessions[sessionId] ?: return emptyList()
        return if (category != null) {
            session.sharedContext.filter { it.category == category && it.isActive }
        } else {
            session.sharedContext.filter { it.isActive }
        }
    }

    fun getContextByKey(sessionId: String, key: String): ContextItem? {
        return sessions[sessionId]?.sharedContext?.find { it.key == key && it.isActive }
    }

    fun recordProgress(sessionId: String, agentId: String, taskRef: String, status: String, progress: Float, note: String = "") {
        val session = sessions[sessionId] ?: return
        getLock(sessionId).withLock {
            session.taskProgress.add(ProgressUpdate(agentId = agentId, taskRef = taskRef, status = status, progress = progress, note = note))
            session.agents.find { it.agentId == agentId }?.status = status
            session.lastUpdated = System.currentTimeMillis()
        }
    }

    fun getProgress(sessionId: String): List<ProgressUpdate> {
        return sessions[sessionId]?.taskProgress?.toList() ?: emptyList()
    }

    fun addArtifact(
        sessionId: String,
        name: String,
        type: ArtifactType,
        content: String,
        createdBy: String
    ): Artifact {
        val session = sessions[sessionId] ?: error("Session not found: $sessionId")
        return getLock(sessionId).withLock {
            val count = artifactCounter.merge(sessionId, 1, Int::plus) ?: 1
            val artifact = Artifact(
                id = "artifact_${sessionId}_$count",
                name = name,
                type = type,
                content = content,
                createdBy = createdBy
            )
            session.artifacts.add(artifact)
            session.lastUpdated = System.currentTimeMillis()

            writeSharedMemoryEntry(session, "[${type.name}] $name", createdBy, 70)
            artifact
        }
    }

    fun getArtifactsByType(sessionId: String, type: ArtifactType): List<Artifact> {
        return sessions[sessionId]?.artifacts?.filter { it.type == type } ?: emptyList()
    }

    fun generateSummary(sessionId: String): String {
        val session = sessions[sessionId] ?: return ""
        val sb = StringBuilder()
        sb.appendLine("Session: ${session.sessionId}")
        sb.appendLine("Task: ${session.taskDescription}")
        sb.appendLine()

        val snapshot = getLock(sessionId).withLock {
            session.copy(
                agents = session.agents.toList(),
                decisions = session.decisions.toList(),
                sharedContext = session.sharedContext.toList(),
                taskProgress = session.taskProgress.toList(),
                artifacts = session.artifacts.toList()
            )
        }

        sb.appendLine("--- Agents ---")
        snapshot.agents.forEach { a ->
            sb.appendLine("  ${a.agentName} (${a.role}): ${a.status}, msgs: ${a.messagesSent} sent / ${a.messagesReceived} recv")
        }

        sb.appendLine()
        sb.appendLine("--- Decisions ---")
        snapshot.decisions.forEach { d ->
            sb.appendLine("  [${d.status}] ${d.description} (by ${d.proposedBy})")
        }

        sb.appendLine()
        sb.appendLine("--- Context ---")
        snapshot.sharedContext.filter { it.isActive }.forEach { c ->
            sb.appendLine("  [${c.category}] ${c.key}: ${c.value.take(100)}")
        }

        sb.appendLine()
        sb.appendLine("--- Progress ---")
        snapshot.taskProgress.takeLast(10).forEach { p ->
            sb.appendLine("  ${p.agentId}: ${p.taskRef} = ${(p.progress * 100).toInt()}% (${p.status})")
        }

        sb.appendLine()
        sb.appendLine("--- Artifacts ---")
        snapshot.artifacts.forEach { a ->
            sb.appendLine("  [${a.type}] ${a.name} v${a.version} by ${a.createdBy}")
        }
        return sb.toString()
    }

    fun getActiveSessions(): List<CollaborationSessionMemory> = sessions.values.toList()

    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionLocks.remove(sessionId)
        artifactCounter.remove(sessionId)
        sharedPool.clearTaskMemory(sessionId)
    }

    fun clearAll() {
        sessions.clear()
        sessionLocks.clear()
        artifactCounter.clear()
    }

    private fun writeSharedMemoryEntry(session: CollaborationSessionMemory, content: String, agentRole: String, priority: Int) {
        sharedPool.writeSharedMemory(
            SharedMemoryEntry(
                entryId = "mem_${System.currentTimeMillis()}_${session.sharedContext.size}",
                taskId = session.taskId,
                content = content,
                agentRole = agentRole,
                priority = priority
            )
        )
    }
}
