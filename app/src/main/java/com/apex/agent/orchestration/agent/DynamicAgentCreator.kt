package com.apex.agent.orchestration.agent

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.agent.model.ModelConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicAgentCreator constructor(
    private val agentManager: AgentManager
) {

    data class AgentCreationRequest(
        val name: String,
        val role: String,
        val systemPrompt: String = "",
        val modelConfig: ModelConfig = ModelConfig(),
        val specialties: List<String> = emptyList()
    )

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
