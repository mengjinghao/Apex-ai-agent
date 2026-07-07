package com.apex.agent.orchestration.agent

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.agent.model.Agent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentManager @Inject constructor() {

    private val agents = ConcurrentHashMap<String, Agent>()
    private val agentInstances = ConcurrentHashMap<String, AgentInstance>()

    fun createAgent(agent: Agent): Result<String> {
        val agentId = agent.id.ifEmpty { generateUniqueId() }
        agents[agentId] = agent.copy(id = agentId)
        return Result.Success(agentId)
    }

    fun getAgent(agentId: String): Result<Agent> {
        val agent = agents[agentId]
        return if (agent != null) {
            Result.Success(agent)
        } else {
            Result.Failure(NoSuchElementException("Agent not found: ${agentId}"))
        }
    }

    fun updateAgent(agent: Agent): Result<Unit> {
        if (!agents.containsKey(agent.id)) {
            return Result.Failure(NoSuchElementException("Agent not found: ${agent.id}"))
        }

        agents[agent.id] = agent
        return Result.Success(Unit)
    }

    fun deleteAgent(agentId: String): Result<Unit> {
        stopAgent(agentId)
        return if (agents.remove(agentId) != null) {
            Result.Success(Unit)
        } else {
            Result.Failure(NoSuchElementException("Agent not found: ${agentId}"))
        }
    }

    fun startAgent(agentId: String): Result<Unit> {
        val agent = agents[agentId]
            ?: return Result.Failure(NoSuchElementException("Agent not found: ${agentId}"))

        if (agentInstances.containsKey(agentId)) {
            return Result.Success(Unit)
        }

        val instance = AgentInstance(agent)
        instance.start()
        agentInstances[agentId] = instance

        return Result.Success(Unit)
    }

    fun stopAgent(agentId: String): Result<Unit> {
        val instance = agentInstances.remove(agentId)
            ?: return Result.Failure(NoSuchElementException("Agent instance not found: ${agentId}"))

        instance.stop()
        return Result.Success(Unit)
    }

    fun pauseAgent(agentId: String): Result<Unit> {
        val instance = agentInstances[agentId]
            ?: return Result.Failure(NoSuchElementException("Agent instance not found: ${agentId}"))

        instance.pause()
        return Result.Success(Unit)
    }

    fun resumeAgent(agentId: String): Result<Unit> {
        val instance = agentInstances[agentId]
            ?: return Result.Failure(NoSuchElementException("Agent instance not found: ${agentId}"))

        instance.resume()
        return Result.Success(Unit)
    }

    fun getAgentStatus(agentId: String): AgentInstance.Status {
        val instance = agentInstances[agentId]
        return instance?.status ?: AgentInstance.Status.STOPPED
    }

    fun getAllAgents(): Result<List<Agent>> {
        return Result.Success(agents.values.toList())
    }

    fun recordAgentExecution(agentId: String, capability: String, success: Boolean, executionTimeMs: Long, taskDescription: String = "") {
        val agent = agents[agentId] ?: return
        agent.capabilityProfile.recordExecution(capability, success, executionTimeMs, taskDescription)
    }

    fun getAgentMetrics(agentId: String): AgentCapabilityProfile? {
        return agents[agentId]?.capabilityProfile
    }

    fun getTopAgentsByCapability(capability: String, limit: Int = 3): List<Pair<Agent, Double>> {
        return agents.values
            .map { it to it.capabilityProfile.getCapability(capability) }
            .sortedByDescending { it.second }
            .take(limit)
    }

    fun clear() {
        agentInstances.values.forEach { it.stop() }
        agentInstances.clear()
        agents.clear()
    }

    private fun generateUniqueId(): String {
        return "agent_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt()
    }
}
