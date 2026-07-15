package com.apex.agent.orchestration.core

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.core.AllocationModels.AllocationRequest
import com.apex.agent.orchestration.core.AllocationModels.AllocationResult
import com.apex.agent.orchestration.core.AllocationModels.AgentScore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntelligentTaskAllocator @Inject constructor(
    private val agentManager: AgentManager,
    private val capabilityMatcher: CapabilityMatcher,
    private val complexityQuantifier: TaskComplexityQuantifier
) {

    data class AllocatorWeights(
        val capabilityMatch: Float = 0.30f,
        val specialtyOverlap: Float = 0.25f,
        val historicalSuccess: Float = 0.20f,
        val loadBalance: Float = 0.15f,
        val modelCapability: Float = 0.10f
    )
        private var weights = AllocatorWeights()
        fun updateWeights(newWeights: AllocatorWeights) {
        weights = newWeights
    }

    suspend fun allocate(request: AllocationRequest): Result<AllocationResult> {
        val agents = agentManager.getAllAgents()
        return when (agents) {
            is Result.Success -> allocateFromAgents(agents.data, request)
            is Result.Failure -> Result.Failure(agents.exception)
        }
    }

    suspend fun allocate(taskDescription: String, candidateAgentIds: List<String>): Result<AllocationResult> {
        val agents = candidateAgentIds.mapNotNull { id ->
            when (val result = agentManager.getAgent(id)) {
                is Result.Success -> result.data
                is Result.Failure -> null
            }
        }
        val complexity = complexityQuantifier.quantifyTask(taskDescription)
        val request = AllocationRequest(
            taskDescription = taskDescription,
            requiredSkills = complexity.requiredSkills,
            complexityReport = complexity
        )
        return allocateFromAgents(agents, request)
    }
        private suspend fun allocateFromAgents(agents: List<Agent>, request: AllocationRequest): Result<AllocationResult> {
        if (agents.isEmpty()) {
            return Result.Failure(IllegalArgumentException("No candidate agents available"))
        }
        val filteredAgents = if (request.excludedAgentIds.isNotEmpty()) {
            agents.filter { it.id !in request.excludedAgentIds }
        } else agents

        if (filteredAgents.isEmpty()) {
            return Result.Failure(IllegalArgumentException("All candidates excluded"))
        }
        if (request.preferredAgentId != null) {
            val preferred = filteredAgents.find { it.id == request.preferredAgentId }
        if (preferred != null) {
                return Result.Success(
                    AllocationResult(
                        selectedAgentId = preferred.id,
                        confidence = 1.0f,
                        reasoning = "Preferred agent selected: ${preferred.name}"
                    )
                )
            }
        }
        val scores = filteredAgents.map { agent ->
            scoreAgent(agent, request)
        }
        val ranked = scores.sortedByDescending { it.totalScore }
        val top = ranked.firstOrNull() ?: return Result.Failure(IllegalStateException("Scoring produced no results"))
        val runnerUp = ranked.getOrNull(1)
        return Result.Success(
            AllocationResult(
                selectedAgentId = top.agentId,
                confidence = computeConfidence(top, ranked),
                runnerUpAgentId = runnerUp?.agentId,
                scoreBreakdown = mapOf(
                    "total" to top.totalScore,
                    "capabilityMatch" to top.capabilityMatch,
                    "specialtyOverlap" to top.specialtyOverlap,
                    "historicalSuccess" to top.historicalSuccess,
                    "loadBalance" to top.loadBalance,
                    "modelCapability" to top.modelCapability
                ),
                allScores = ranked.take(5),
                reasoning = buildAllocationReasoning(top, ranked, request)
            )
        )
    }
        private fun scoreAgent(agent: Agent, request: AllocationRequest): AgentScore {
        val matchResult = capabilityMatcher.computeMatch(
            requiredSkills = request.requiredSkills,
            specialties = agent.specialties,
            capabilityProfile = agent.capabilityProfile
        )
        val capMatch = matchResult.score
        val specOverlap = matchResult.breakdown["specialty"] ?: 0f
        val histSuccess = computeHistoricalSuccess(agent)
        val loadBal = computeLoadBalance(agent)
        val modelCap = computeModelCapability(agent, request)
        val total = (
            capMatch * weights.capabilityMatch +
            specOverlap * weights.specialtyOverlap +
            histSuccess * weights.historicalSuccess +
            loadBal * weights.loadBalance +
            modelCap * weights.modelCapability
        ) / weights.run { capabilityMatch + specialtyOverlap + historicalSuccess + loadBalance + modelCapability }
        return AgentScore(
            agentId = agent.id,
            totalScore = total.coerceIn(0f, 1f),
            capabilityMatch = capMatch,
            specialtyOverlap = specOverlap,
            historicalSuccess = histSuccess,
            loadBalance = loadBal,
            modelCapability = modelCap
        )
    }
        private fun computeHistoricalSuccess(agent: Agent): Float {
        val profile = agent.capabilityProfile
        val totalExecs = profile.getExecutionCount()
        if (totalExecs == 0) return 0.5f
        val overallRate = profile.getSuccessRate().toFloat()
        val recentTrend = agent.specialties.mapNotNull { spec ->
            val trend = profile.getRecentTrend(spec, 5)
        if (trend > 0f) trend else null
        }.average().toFloat().takeIf { it > 0f } ?: overallRate
        return (overallRate * 0.4f + recentTrend * 0.6f).coerceIn(0f, 1f)
    }
        private fun computeLoadBalance(agent: Agent): Float {
        val maxConcurrent = agent.permissions.maxConcurrentTasks
        if (maxConcurrent <= 0) return 0f
        val currentLoad = agent.capabilityProfile.getExecutionCount() % (maxConcurrent + 1)
        val loadRatio = currentLoad.toFloat() / maxConcurrent
        return (1f - loadRatio).coerceIn(0f, 1f)
    }
        private fun computeModelCapability(agent: Agent, request: AllocationRequest): Float {
        val model = agent.modelConfig
        val report = request.complexityReport
        if (report == null) return 0.5f

        val providerScore = when {
            model.provider.contains("anthropic", ignoreCase = true) -> 0.9f
            model.provider.contains("openai", ignoreCase = true) -> 0.85f
            model.provider.contains("deepseek", ignoreCase = true) -> 0.75f
            model.provider.contains("google", ignoreCase = true) -> 0.7f
            else -> 0.5f
        }
        val modelSizeScore = when {
            model.model.contains("4") || model.model.contains("sonnet") || model.model.contains("opus") -> 0.9f
            model.model.contains("3.5") || model.model.contains("flash") -> 0.7f
            model.model.contains("mini") || model.model.contains("small") -> 0.5f
            else -> 0.6f
        }
        val complexityFit = if (report.difficulty > 7) {
            modelSizeScore
        } else {
            (modelSizeScore * 0.5f + 0.5f).coerceAtMost(1f)
        }
        return (providerScore * 0.4f + modelSizeScore * 0.3f + complexityFit * 0.3f).coerceIn(0f, 1f)
    }
        private fun computeConfidence(top: AgentScore, allScores: List<AgentScore>): Float {
        if (allScores.size <= 1) return 0.8f
        val secondScore = allScores[1].totalScore
        if (secondScore <= 0f) return 0.9f
        val margin = (top.totalScore - secondScore) / secondScore
        return (0.5f + margin.coerceIn(0f, 0.5f)).coerceIn(0f, 1f)
    }
        private fun buildAllocationReasoning(top: AgentScore, ranked: List<AgentScore>, request: AllocationRequest): String {
        val agentName = when (val result = agentManager.getAgent(top.agentId)) {
            is Result.Success -> result.data.name
            is Result.Failure -> top.agentId
        }
        val parts = mutableListOf("Selected: $agentName (score: ${"%.2f".format(top.totalScore)})")
        parts.add("capability=${"%.2f".format(top.capabilityMatch)}, specialty=${"%.2f".format(top.specialtyOverlap)}, history=${"%.2f".format(top.historicalSuccess)}, load=${"%.2f".format(top.loadBalance)}, model=${"%.2f".format(top.modelCapability)}")
        if (ranked.size > 1) {
            val runnerUpId = ranked[1].agentId
            val runnerUpName = when (val r = agentManager.getAgent(runnerUpId)) {
                is Result.Success -> r.data.name
                is Result.Failure -> runnerUpId
            }
            parts.add("Runner-up: $runnerUpName (${"%.2f".format(ranked[1].totalScore)})")
        }
        return parts.joinToString(" | ")
    }
}
