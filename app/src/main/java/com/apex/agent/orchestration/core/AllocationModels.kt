package com.apex.agent.orchestration.core



data class AgentScore(
    val agentId: String,
    val totalScore: Float,
    val capabilityMatch: Float = 0f,
    val specialtyOverlap: Float = 0f,
    val historicalSuccess: Float = 0f,
    val loadBalance: Float = 0f,
    val modelCapability: Float = 0f
)

