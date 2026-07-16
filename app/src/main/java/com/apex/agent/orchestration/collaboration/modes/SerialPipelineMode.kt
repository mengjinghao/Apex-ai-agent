package com.apex.agent.orchestration.collaboration.modes

import android.content.Context
import com.apex.agent.R
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.collaboration.AgentStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SerialPipelineMode constructor(
    @ApplicationContext context: Context,
    agentManager: AgentManager
) : AbstractCollaborationMode<SerialPipelineMode.PipelineState>(context, agentManager) {

    class PipelineState(task: Task, agents: List<Agent>) : ExecutionState(task, agents) {
        var currentAgentIndex: Int = 0
        var pipelineData: String = ""
    }

    private val pipelineStages = listOf(
        context.getString(R.string.pipeline_stage_requirements),
        context.getString(R.string.pipeline_stage_design),
        context.getString(R.string.pipeline_stage_implementation),
        context.getString(R.string.pipeline_stage_testing),
        context.getString(R.string.pipeline_stage_deployment)
    )

    override fun createState(task: Task, agents: List<Agent>): PipelineState {
        return PipelineState(task, agents)
    }

    override suspend fun runStep(state: PipelineState) {
        val agents = state.agents
        if (agents.isEmpty()) {
            state.running.set(false)
            return
        }

        val currentAgent = agents[state.currentAgentIndex]
        updateAgentStatus(state, currentAgent.id, AgentStatus.WORKING)

        val stageName = pipelineStages.getOrElse(state.currentAgentIndex) {
            context.getString(R.string.pipeline_stage_fallback)
        }
        val inputData = if (state.currentAgentIndex == 0) state.task.description else state.pipelineData

        state.pipelineData = processStage(currentAgent, stageName, inputData)

        updateAgentProgress(state, currentAgent.id, (state.currentAgentIndex + 1).toFloat() / agents.size)
        updateAgentStatus(state, currentAgent.id, AgentStatus.FINISHED)

        state.currentAgentIndex = (state.currentAgentIndex + 1) % agents.size

        if (state.currentAgentIndex == 0) {
            completePipeline(state)
        }
    }

    private fun processStage(agent: Agent, stageName: String, input: String): String {
        return context.getString(R.string.pipeline_stage_processed_format, stageName, agent.name)
    }

    private fun completePipeline(state: PipelineState) {
        getSupervisorAgent(state)?.let { supervisor ->
            updateAgentStatus(state, supervisor.id, AgentStatus.FINISHED)
        }
        state.running.set(false)
    }

    override suspend fun onMessage(state: PipelineState, message: AgentMessage) {
        val currentAgent = state.agents.getOrNull(state.currentAgentIndex)
        if (message.senderId == currentAgent?.id) {
            updateAgentStatus(state, message.senderId, AgentStatus.IDLE)
        }
    }
}
