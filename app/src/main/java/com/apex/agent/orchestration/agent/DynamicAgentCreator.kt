package com.apex.agent.orchestration.agent

import com.apex.agent.common.result.Result
import com.apex.agent.core.multiagent.Agent
import com.apex.agent.core.multiagent.ModelConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton


    fun createAgent(request: AgentCreationRequest): Result<String> {
        val agent = Agent(
            id = "",
            name = request.name,
            role = request.role,
            systemPrompt = request.systemPrompt,
            modelConfig = request.modelConfig,
            specialties = request.specialties
        )
        return agentManager.createAgent(agent)
    }
}
