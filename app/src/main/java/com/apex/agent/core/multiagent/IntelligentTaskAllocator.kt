package com.apex.agent.core.multiagent

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class IntelligentTaskAllocator(private val context: Context) {

    data class TaskFeature(
        val category: String = "general",
        val difficulty: Int = 5,
        val expectedDuration: Long = 60000,
        val requiredCapabilities: List<String> = emptyList()
    )

    data class AllocationRequest(
        val taskId: String = "",
        val taskDescription: String,
        val requiredSkills: List<String> = emptyList(),
        val preferredAgents: List<String> = emptyList(),
        val priority: Int = 5,
        val maxCandidates: Int = 3,
        val taskFeature: TaskFeature = TaskFeature()
    )

    data class AgentMatch(
        val agentId: String,
        val agentName: String = "",
        val score: Double = 0.0,
        val capabilityMatch: Double = 0.0,
        val performanceMatch: Double = 0.0,
        val resourceMatch: Double = 0.0,
        val reasoning: String = ""
    )

    data class AllocationResult(
        val taskId: String = "",
        val optimalAgent: AgentMatch = AgentMatch(agentId = "none"),
        val backupAgents: List<AgentMatch> = emptyList(),
        val decisionReport: String = "",
        val executionTime: Long = 0,
        val matchedSkill: String? = null
    )

    private val agentProfiles = ConcurrentHashMap<String, AgentCapabilityProfile.CapabilityProfile>()
    private val cache = ConcurrentHashMap<String, AllocationResult>()
    private val profileManager = AgentCapabilityProfile()
    private val maxCacheSize = 200

    fun initializeAgentProfiles(agents: List<Agent>) {
        agents.forEach { agent -> profileManager.initializeProfile(agent) }
    }

    fun allocateTask(request: AllocationRequest): AllocationResult {
        val taskId = request.taskId.ifEmpty { "task_${System.currentTimeMillis()}" }

        val cacheKey = "${request.taskDescription}_${request.taskFeature.category}_${request.taskFeature.difficulty}"
        cache[cacheKey]?.let { return it }

        val matches = agentProfiles.values.map { profile ->
            val capScore = profile.capabilities.values.average().takeIf { it.isFinite() } ?: 0.0
            AgentMatch(
                agentId = profile.agentId,
                agentName = profile.agentId,
                score = capScore,
                capabilityMatch = capScore,
                performanceMatch = profile.averagePerformance,
                resourceMatch = profile.averagePerformance,
                reasoning = "Capability-based allocation"
            )
        }.sortedByDescending { it.score }

        val primary = matches.firstOrNull() ?: AgentMatch(agentId = "sanxing_libu_hr", agentName = "Libu", score = 0.0)

        val result = AllocationResult(
            taskId = taskId,
            optimalAgent = primary,
            backupAgents = matches.drop(1).take(2),
            decisionReport = "Allocated to ${primary.agentName} with score ${primary.score}",
            executionTime = System.currentTimeMillis(),
            matchedSkill = request.taskFeature.requiredCapabilities.firstOrNull()
        )

        if (cache.size >= maxCacheSize) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        cache[cacheKey] = result
        return result
    }

    fun updateAgentProfile(agentId: String, update: AgentCapabilityProfile.CapabilityUpdate) {
        profileManager.updateProfile(agentId, update)
    }

    fun clearCache() { cache.clear() }
    fun getCacheSize(): Int = cache.size
}
