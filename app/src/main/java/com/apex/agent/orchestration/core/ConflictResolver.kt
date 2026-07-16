package com.apex.agent.orchestration.core

import com.apex.agent.common.result.Result
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConflictResolver constructor() {

    enum class ConflictType {
        OPINION_DIFFERENCE,
        RESOURCE_COMPETITION,
        GOAL_MISMATCH,
        APPROACH_DIVERGENCE,
        PRIORITY_CONFLICT
    }

    enum class ResolutionStrategy {
        VOTING,
        SUPERVISOR_ARBITRATION,
        CONSENSUS_BUILDING,
        WEIGHTED_SCORING,
        MANUAL_INTERVENTION
    }

    enum class ConflictSeverity { LOW, MEDIUM, HIGH, CRITICAL }
    enum class ConflictStatus { DETECTED, NEGOTIATING, RESOLVED, ESCALATED, UNRESOLVED }

    data class AgentOption(
        val agentId: String,
        val agentName: String,
        val description: String,
        val score: Double = 0.0,
        val reasoning: String = "",
        val confidence: Double = 1.0,
        val votes: Int = 0
    )

    data class Resolution(
        val strategy: ResolutionStrategy,
        val chosenOption: AgentOption?,
        val reasoning: String,
        val confidence: Double,
        val votes: Map<String, Int> = emptyMap()
    )

    data class Conflict(
        val id: String,
        val type: ConflictType,
        val description: String,
        val agentsInvolved: List<String>,
        val timestamp: Long,
        val severity: ConflictSeverity,
        val options: List<AgentOption>,
        val status: ConflictStatus,
        val resolution: Resolution? = null
    )

    interface ConflictListener {
        fun onConflictDetected(conflict: Conflict)
        fun onConflictResolved(conflict: Conflict)
        fun onConflictEscalated(conflict: Conflict)
    }

    private val conflicts = ConcurrentHashMap<String, Conflict>()
    private val listeners = CopyOnWriteArrayList<ConflictListener>()

    fun detectConflict(
        agentsInvolved: List<String>,
        type: ConflictType,
        description: String,
        options: List<AgentOption>
    ): Conflict {
        val conflict = Conflict(
            id = "conflict_${System.currentTimeMillis()}",
            type = type,
            description = description,
            agentsInvolved = agentsInvolved,
            timestamp = System.currentTimeMillis(),
            severity = calculateSeverity(type, agentsInvolved.size),
            options = options,
            status = ConflictStatus.DETECTED
        )
        conflicts[conflict.id] = conflict
        listeners.forEach { tryNotify { it.onConflictDetected(conflict) } }
        return conflict
    }

    fun resolveByVoting(conflictId: String): Result<Resolution> {
        val conflict = conflicts[conflictId]
            ?: return Result.Failure(IllegalArgumentException("Conflict not found: ${conflictId}"))

        val votes = conflict.options.associate { it.agentId to it.votes }
        val winner = conflict.options.maxByOrNull { it.votes }
        val totalVotes = votes.values.sum().coerceAtLeast(1)
        val resolution = Resolution(
            strategy = ResolutionStrategy.VOTING,
            chosenOption = winner,
            reasoning = "通过投票决定",
            confidence = (winner?.votes?.toDouble() ?: 0.0) / totalVotes,
            votes = votes
        )
        updateConflict(conflictId, resolution)
        return Result.Success(resolution)
    }

    fun resolveBySupervisor(conflictId: String, supervisorReasoning: String): Result<Resolution> {
        val conflict = conflicts[conflictId]
            ?: return Result.Failure(IllegalArgumentException("Conflict not found: ${conflictId}"))

        val best = conflict.options.maxByOrNull { it.score }
        val resolution = Resolution(
            strategy = ResolutionStrategy.SUPERVISOR_ARBITRATION,
            chosenOption = best,
            reasoning = supervisorReasoning,
            confidence = 0.8
        )
        updateConflict(conflictId, resolution)
        return Result.Success(resolution)
    }

    fun resolveByConsensus(conflictId: String, chosen: AgentOption, reasoning: String): Result<Resolution> {
        val conflict = conflicts[conflictId]
            ?: return Result.Failure(IllegalArgumentException("Conflict not found: ${conflictId}"))

        val resolution = Resolution(
            strategy = ResolutionStrategy.CONSENSUS_BUILDING,
            chosenOption = chosen,
            reasoning = reasoning,
            confidence = 0.95
        )
        updateConflict(conflictId, resolution)
        return Result.Success(resolution)
    }

    fun escalateConflict(conflictId: String): Result<Conflict> {
        val conflict = conflicts[conflictId]
            ?: return Result.Failure(IllegalArgumentException("Conflict not found: ${conflictId}"))

        val escalated = conflict.copy(
            status = ConflictStatus.ESCALATED,
            severity = when (conflict.severity) {
                ConflictSeverity.LOW -> ConflictSeverity.MEDIUM
                ConflictSeverity.MEDIUM -> ConflictSeverity.HIGH
                else -> ConflictSeverity.CRITICAL
            }
        )
        conflicts[conflictId] = escalated
        listeners.forEach { tryNotify { it.onConflictEscalated(escalated) } }
        return Result.Success(escalated)
    }

    private fun updateConflict(conflictId: String, resolution: Resolution) {
        val conflict = conflicts[conflictId] ?: return
        val resolved = conflict.copy(status = ConflictStatus.RESOLVED, resolution = resolution)
        conflicts[conflictId] = resolved
        listeners.forEach { tryNotify { it.onConflictResolved(resolved) } }
    }

    private fun calculateSeverity(type: ConflictType, involved: Int): ConflictSeverity {
        val base = when (type) {
            ConflictType.OPINION_DIFFERENCE -> ConflictSeverity.LOW
            ConflictType.APPROACH_DIVERGENCE -> ConflictSeverity.MEDIUM
            ConflictType.PRIORITY_CONFLICT -> ConflictSeverity.MEDIUM
            ConflictType.GOAL_MISMATCH -> ConflictSeverity.HIGH
            ConflictType.RESOURCE_COMPETITION -> ConflictSeverity.HIGH
        }
        return if (involved > 3 && base != ConflictSeverity.CRITICAL) {
            ConflictSeverity.values()[base.ordinal + 1]
        } else base
    }

    private inline fun tryNotify(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    fun getConflict(conflictId: String): Conflict? = conflicts[conflictId]
    fun getAllConflicts(): List<Conflict> = conflicts.values.toList()
    fun getActiveConflicts(): List<Conflict> = conflicts.values.filter {
        it.status == ConflictStatus.DETECTED || it.status == ConflictStatus.NEGOTIATING || it.status == ConflictStatus.ESCALATED
    }

    fun addListener(listener: ConflictListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConflictListener) {
        listeners.remove(listener)
    }
}
