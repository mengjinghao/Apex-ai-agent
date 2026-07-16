
package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap

class AgentCapabilityProfile {

    data class CapabilityUpdate(
        val capability: String,
        val score: Double,
        val confidence: Double = 1.0
    )

    data class CapabilityProfile(
        val agentId: String,
        val capabilities: Map<String, Double> = emptyMap(),
        val skills: List<String> = emptyList(),
        val averagePerformance: Double = 0.0,
        val taskCount: Int = 0,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private val profiles = ConcurrentHashMap<String, CapabilityProfile>()

    fun initializeProfile(agent: Agent) {
        profiles[agent.id] = CapabilityProfile(agentId = agent.id)
    }

    fun getProfile(agentId: String): CapabilityProfile? {
        return profiles[agentId]
    }

    fun updateProfile(agentId: String, update: CapabilityUpdate) {
        val current = profiles[agentId] ?: return
        val newCapabilities = current.capabilities + (update.capability to update.score)
        profiles[agentId] = current.copy(capabilities = newCapabilities, lastUpdated = System.currentTimeMillis())
    }

    fun getAgentsByCapability(capability: String): List<CapabilityProfile> {
        return profiles.values.filter { it.capabilities.containsKey(capability) }
            .sortedByDescending { it.capabilities[capability] ?: 0.0 }
    }

    fun getAgentsBySkill(skill: String): List<CapabilityProfile> {
        return profiles.values.filter { skill in it.skills }
    }

    fun getTopAgentsForTask(category: String, skills: List<String>, count: Int): List<CapabilityProfile> {
        return profiles.values
            .filter { it.skills.any { s -> s in skills } }
            .sortedByDescending { it.averagePerformance }
            .take(count)
    }

    fun updateAgentPerformance(agentId: String, taskId: String, success: Boolean, quality: Double, responseTime: Long) {
        val current = profiles[agentId] ?: return
        val newCount = current.taskCount + 1
        val newAvg = (current.averagePerformance * current.taskCount + quality) / newCount
        profiles[agentId] = current.copy(averagePerformance = newAvg, taskCount = newCount, lastUpdated = System.currentTimeMillis())
    }

    fun getAllProfiles(): List<CapabilityProfile> {
        return profiles.values.toList()
    }

    fun clearProfiles() {
        profiles.clear()
    }
}
