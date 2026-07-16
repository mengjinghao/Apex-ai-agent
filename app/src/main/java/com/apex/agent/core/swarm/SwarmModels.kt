package com.apex.agent.core.swarm

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Debate(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val participants: List<String>,
    val arguments: List<Argument>,
    val status: DebateStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class DebateStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    ABORTED
}

@Serializable

enum class Stance {
    FOR,
    AGAINST,
    NEUTRAL
}

@Serializable
data class Consensus(
    val id: String = UUID.randomUUID().toString(),
    val debateId: String,
    val conclusion: String,
    val confidence: Float,
    val supportingArguments: List<String>,
    val dissentingArguments: List<String>,
    val voteResults: VoteResults,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class VoteResults(
    val totalVotes: Int,
    val agreeCount: Int,
    val disagreeCount: Int,
    val abstainCount: Int,
    val voterIds: List<String>
)

@Serializable
data class AgentOpinion(
    val agentId: String,
    val opinion: String,
    val confidence: Float,
    val reasoning: String,
    val vote: VoteType
)

enum class VoteType {
    AGREE,
    DISAGREE,
    ABSTAIN
}

@Serializable
data class DebateSummary(
    val debateId: String,
    val topic: String,
    val totalArguments: Int,
    val forCount: Int,
    val againstCount: Int,
    val neutralCount: Int,
    val consensus: Consensus?,
    val durationMs: Long
)

@Serializable
data class SwarmResult(
    val taskId: String,
    val solutions: List<Solution>,
    val consensus: Consensus?,
    val qualityScore: Float,
    val diversityScore: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class Solution(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val content: String,
    val qualityScore: Float,
    val supportingEvidence: List<String> = emptyList()
)

@Serializable
data class SwarmTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val type: TaskType,
    val maxAgents: Int = 5,
    val timeoutMs: Long = 60000,
    val requireConsensus: Boolean = true
)
