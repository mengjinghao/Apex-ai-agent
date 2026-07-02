package com.apex.agent.orchestration.core

data class AllocationRequest(
    val taskDescription: String,
    val requiredSkills: List<String> = emptyList(),
    val complexityReport: ComplexityReport? = null,
    val preferredAgentId: String? = null,
    val excludedAgentIds: List<String> = emptyList(),
    val useAIEnhancement: Boolean = false,
    val maxCandidates: Int = 3
)

data class AllocationResult(
    val selectedAgentId: String,
    val confidence: Float,
    val runnerUpAgentId: String? = null,
    val scoreBreakdown: Map<String, Float> = emptyMap(),
    val allScores: List<AgentScore> = emptyList(),
    val reasoning: String = ""
)

data class AgentScore(
    val agentId: String,
    val totalScore: Float,
    val capabilityMatch: Float = 0f,
    val specialtyOverlap: Float = 0f,
    val historicalSuccess: Float = 0f,
    val loadBalance: Float = 0f,
    val modelCapability: Float = 0f
)

data class ComplexityReport(
    val category: String,
    val difficulty: Int,
    val complexityScore: Float,
    val resourceRequirement: ResourceRequirement,
    val riskLevel: Int,
    val estimatedTimeMinutes: Int,
    val requiredSkills: List<String>,
    val reasoning: String = ""
) {
    data class ResourceRequirement(
        val memory: Int,
        val cpu: Int,
        val network: Int,
        val storage: Int
    )
}
