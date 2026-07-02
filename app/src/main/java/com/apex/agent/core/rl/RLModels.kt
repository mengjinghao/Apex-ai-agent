package com.apex.agent.core.rl

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class State(
    val features: Map<String, Double>,
    val context: String = ""
)

@Serializable
data class Action(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val parameters: Map<String, Any>,
    val description: String
)

enum class ActionType {
    TOOL_CALL, TASK_PLAN, DECISION, OBSERVE, REFLECT
}

@Serializable
data class Reward(
    val value: Double,
    val type: RewardType,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RewardType {
    SUCCESS, FAILURE, PROGRESS, EFFICIENCY, QUALITY, SAFETY
}

@Serializable
data class Transition(
    val state: State,
    val action: Action,
    val nextState: State,
    val reward: Reward,
    val done: Boolean
)

@Serializable
data class QValue(
    val stateKey: String,
    val actionKey: String,
    val value: Double,
    val visits: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
data class Policy(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val stateActions: Map<String, List<ActionProbability>>,
    val creationTime: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
data class ActionProbability(
    val actionKey: String,
    val probability: Double
)

@Serializable
data class Episode(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val transitions: List<Transition>,
    val totalReward: Double,
    val success: Boolean,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class TrainingStats(
    val episodesTrained: Int,
    val averageReward: Double,
    val successRate: Double,
    val epsilon: Double,
    val learningRate: Double,
    val discountFactor: Double
)

@Serializable
data class PolicyUpdate(
    val policyId: String,
    val updates: List<QValueUpdate>,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class QValueUpdate(
    val stateKey: String,
    val actionKey: String,
    val delta: Double,
    val newValue: Double
)