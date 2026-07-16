
package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap

class AgentManager {

    private val agents = ConcurrentHashMap<String, Agent>()

    fun registerAgent(agent: Agent) {
        agents[agent.id] = agent
    }

    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
    }

    fun getAgent(agentId: String): Agent? {
        return agents[agentId]
    }

    fun getAllAgents(): List<Agent> {
        return agents.values.toList()
    }

    fun clear() {
        agents.clear()
    }
}
