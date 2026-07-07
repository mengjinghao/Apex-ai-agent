package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

class ConflictResolver {
    companion object {
        private const val TAG = "ConflictResolver"
    }

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
        RANDOM_SELECTION,
        MANUAL_INTERVENTION
    }

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

    data class AgentOption(
        val agentId: String,
        val agentName: String,
        val description: String,
        val score: Double = 0.0,
        val reasoning: String,
        val confidence: Double = 1.0,
        val votes: Int = 0
    )

    enum class ConflictSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    enum class ConflictStatus {
        DETECTED,
        NEGOTIATING,
        RESOLVED,
        ESCALATED,
        UNRESOLVED
    }

    data class Resolution(
        val strategy: ResolutionStrategy,
        val chosenOption: AgentOption?,
        val reasoning: String,
        val confidence: Double,
        val votes: Map<String, Int> = emptyMap()
    )

    private val conflicts = ConcurrentHashMap<String, Conflict>()
    private val conflictListeners = CopyOnWriteArrayList<ConflictListener>()

    interface ConflictListener {
        fun onConflictDetected(conflict: Conflict)
        fun onConflictResolved(conflict: Conflict)
        fun onConflictEscalated(conflict: Conflict)
    }

    fun detectConflict(
        agentsInvolved: List<String>,
        type: ConflictType,
        description: String,
        options: List<AgentOption>
    ): Conflict {
        val severity = calculateSeverity(type, agentsInvolved.size)

        val conflict = Conflict(
            id = generateConflictId(),
            type = type,
            description = description,
            agentsInvolved = agentsInvolved,
            timestamp = System.currentTimeMillis(),
            severity = severity,
            options = options,
            status = ConflictStatus.DETECTED
        )

        conflicts[conflict.id] = conflict

        conflictListeners.forEach { listener ->
            try {
                listener.onConflictDetected(conflict)
            } catch (e: Exception) {
                AppLogger.e(TAG, "onConflictDetected listener error", e)
            }
        }

        return conflict
    }

    fun resolveByVoting(conflictId: String): Resolution? {
        val conflict = conflicts[conflictId] ?: return null

        val votingResult = performVoting(conflict.options)
        val chosenOption = votingResult.winner

        val resolution = Resolution(
            strategy = ResolutionStrategy.VOTING,
            chosenOption = chosenOption,
            reasoning = "通过投票决定，支持票 ${chosenOption?.votes ?: 0}",
            confidence = votingResult.confidence,
            votes = votingResult.allVotes
        )

        updateConflictResolution(conflictId, resolution)
        return resolution
    }

    fun resolveBySupervisor(
        conflictId: String,
        supervisorAgentId: String,
        supervisorReasoning: String
    ): Resolution? {
        val conflict = conflicts[conflictId] ?: return null

        val bestOption = conflict.options.maxByOrNull { it.score }

        val resolution = Resolution(
            strategy = ResolutionStrategy.SUPERVISOR_ARBITRATION,
            chosenOption = bestOption,
            reasoning = supervisorReasoning,
            confidence = 0.8,
            votes = emptyMap()
        )

        updateConflictResolution(conflictId, resolution)
        return resolution
    }

    fun resolveByConsensus(conflictId: String, consensusOption: AgentOption, reasoning: String): Resolution? {
        val conflict = conflicts[conflictId] ?: return null

        val resolution = Resolution(
            strategy = ResolutionStrategy.CONSENSUS_BUILDING,
            chosenOption = consensusOption,
            reasoning = reasoning,
            confidence = 0.95,
            votes = emptyMap()
        )

        updateConflictResolution(conflictId, resolution)
        return resolution
    }

    fun resolveByWeightedScoring(conflictId: String, criteriaWeights: Map<String, Double>): Resolution? {
        val conflict = conflicts[conflictId] ?: return null

        val scoredOptions = conflict.options.map { option ->
            val weightedScore = calculateWeightedScore(option, criteriaWeights)
            option.copy(score = weightedScore)
        }

        val bestOption = scoredOptions.maxByOrNull { it.score }

        val resolution = Resolution(
            strategy = ResolutionStrategy.WEIGHTED_SCORING,
            chosenOption = bestOption,
            reasoning = "基于加权评分选择，最高分: ${bestOption?.score ?: 0.0}",
            confidence = 0.75,
            votes = emptyMap()
        )

        updateConflictResolution(conflictId, resolution)
        return resolution
    }

    fun escalateConflict(conflictId: String): Conflict? {
        val conflict = conflicts[conflictId] ?: return null

        val escalatedConflict = conflict.copy(
            status = ConflictStatus.ESCALATED,
            severity = when (conflict.severity) {
                ConflictSeverity.LOW -> ConflictSeverity.MEDIUM
                ConflictSeverity.MEDIUM -> ConflictSeverity.HIGH
                ConflictSeverity.HIGH -> ConflictSeverity.CRITICAL
                ConflictSeverity.CRITICAL -> ConflictSeverity.CRITICAL
            }
        )

        conflicts[conflictId] = escalatedConflict

        conflictListeners.forEach { listener ->
            try {
                listener.onConflictEscalated(escalatedConflict)
            } catch (e: Exception) {
                AppLogger.e(TAG, "onConflictEscalated listener error", e)
            }
        }

        return escalatedConflict
    }

    private fun updateConflictResolution(conflictId: String, resolution: Resolution) {
        val conflict = conflicts[conflictId] ?: return

        val resolvedConflict = conflict.copy(
            status = ConflictStatus.RESOLVED,
            resolution = resolution
        )

        conflicts[conflictId] = resolvedConflict

        conflictListeners.forEach { listener ->
            try {
                listener.onConflictResolved(resolvedConflict)
            } catch (e: Exception) {
                AppLogger.e(TAG, "onConflictResolved listener error", e)
            }
        }
    }

    private fun performVoting(options: List<AgentOption>): VotingResult {
        val allVotes = mutableMapOf<String, Int>()
        var totalWeight = 0.0

        options.forEach { option ->
            val weight = option.confidence
            totalWeight += weight
            allVotes[option.agentId] = 0
        }

        val votedOptions = options.map { option ->
            val votes = (options.size - option.score.toInt()).coerceIn(0, options.size - 1)
            allVotes[option.agentId] = votes
            option.copy(votes = votes)
        }

        val winner = votedOptions.maxByOrNull { it.votes }
        val confidence = if (winner != null) {
            winner.votes.toDouble() / options.size
        } else {
            0.0
        }

        return VotingResult(
            winner = winner,
            allVotes = allVotes,
            confidence = confidence
        )
    }

    private fun calculateWeightedScore(option: AgentOption, weights: Map<String, Double>): Double {
        var score = option.score * (weights["score"] ?: 1.0)
        score += option.confidence * (weights["confidence"] ?: 0.5)

        return score
    }

    private fun calculateSeverity(type: ConflictType, agentsInvolved: Int): ConflictSeverity {
        val baseSeverity = when (type) {
            ConflictType.OPINION_DIFFERENCE -> ConflictSeverity.LOW
            ConflictType.APPROACH_DIVERGENCE -> ConflictSeverity.MEDIUM
            ConflictType.PRIORITY_CONFLICT -> ConflictSeverity.MEDIUM
            ConflictType.GOAL_MISMATCH -> ConflictSeverity.HIGH
            ConflictType.RESOURCE_COMPETITION -> ConflictSeverity.HIGH
        }

        return if (agentsInvolved > 3) {
            when (baseSeverity) {
                ConflictSeverity.LOW -> ConflictSeverity.MEDIUM
                ConflictSeverity.MEDIUM -> ConflictSeverity.HIGH
                ConflictSeverity.HIGH -> ConflictSeverity.CRITICAL
                ConflictSeverity.CRITICAL -> ConflictSeverity.CRITICAL
            }
        } else {
            baseSeverity
        }
    }

    private fun generateConflictId(): String {
        return "conflict_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }

    fun getConflict(conflictId: String): Conflict? = conflicts[conflictId]

    fun getAllConflicts(): List<Conflict> = conflicts.values.toList()

    fun getConflictsByStatus(status: ConflictStatus): List<Conflict> {
        return conflicts.values.filter { it.status == status }
    }

    fun getConflictsByAgent(agentId: String): List<Conflict> {
        return conflicts.values.filter { agentId in it.agentsInvolved }
    }

    fun getActiveConflicts(): List<Conflict> {
        return conflicts.values.filter {
            it.status == ConflictStatus.DETECTED ||
            it.status == ConflictStatus.NEGOTIATING ||
            it.status == ConflictStatus.ESCALATED
        }
    }

    fun addListener(listener: ConflictListener) {
        conflictListeners.add(listener)
    }

    fun removeListener(listener: ConflictListener) {
        conflictListeners.remove(listener)
    }

    fun clearResolvedConflicts() {
        conflicts.entries.removeIf { it.value.status == ConflictStatus.RESOLVED }
    }

    fun getConflictStatistics(): ConflictStatistics {
        val byType = conflicts.values.groupBy { it.type }.mapValues { it.value.size }
        val byStatus = conflicts.values.groupBy { it.status }.mapValues { it.value.size }
        val bySeverity = conflicts.values.groupBy { it.severity }.mapValues { it.value.size }

        val resolutionRate = if (conflicts.isNotEmpty()) {
            conflicts.values.count { it.status == ConflictStatus.RESOLVED }.toDouble() / conflicts.size
        } else {
            0.0
        }

        val avgResolutionTime = conflicts.values
            .filter { it.status == ConflictStatus.RESOLVED && it.resolution != null }
            .map { conflict ->
                val resolution = conflict.resolution!!
                conflict.timestamp
            }
            .average()
            .let { if (it.isNaN()) 0.0 else it }

        return ConflictStatistics(
            totalConflicts = conflicts.size,
            byType = byType,
            byStatus = byStatus,
            bySeverity = bySeverity,
            resolutionRate = resolutionRate,
            averageResolutionTime = avgResolutionTime
        )
    }

    data class ConflictStatistics(
        val totalConflicts: Int,
        val byType: Map<ConflictType, Int>,
        val byStatus: Map<ConflictStatus, Int>,
        val bySeverity: Map<ConflictSeverity, Int>,
        val resolutionRate: Double,
        val averageResolutionTime: Double
    )

    data class VotingResult(
        val winner: AgentOption?,
        val allVotes: Map<String, Int>,
        val confidence: Double
    )

    companion object {
        private var instance: ConflictResolver? = null

        fun getInstance(): ConflictResolver {
            return instance ?: synchronized(this) {
                instance ?: ConflictResolver().also { instance = it }
            }
        }
    }
}
