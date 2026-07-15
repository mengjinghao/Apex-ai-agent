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
class ParallelExecutionMode @Inject constructor(
    @ApplicationContext context: Context,
    agentManager: AgentManager
) : AbstractCollaborationMode<ParallelExecutionMode.ParallelState>(context, agentManager) {

    class ParallelState(task: Task, agents: List<Agent>) : ExecutionState(task, agents) {
        var executionRound: Int = 0
        val maxRounds: Int = 3
        val results = mutableMapOf<String, String>()
    }

    override fun createState(task: Task, agents: List<Agent>): ParallelState {
        return ParallelState(task, agents)
    }

    override suspend fun runStep(state: ParallelState) {
        val agents = state.agents
        if (agents.isEmpty()) {
            state.running.set(false)
        return
        }

        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WORKING)
        val result = executeBranch(agent, state.executionRound)
            state.results[agent.id] = result

            updateAgentProgress(state, agent.id, (state.executionRound + 1).toFloat() / state.maxRounds)
        }

        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.FINISHED)
        }

        state.executionRound++

        if (state.executionRound >= state.maxRounds) {
            aggregateResults(state)
        }
    }
        private fun executeBranch(agent: Agent, round: Int): String {
        return context.getString(R.string.parallel_branch_execution_format, agent.name, round + 1)
    }
        private fun aggregateResults(state: ParallelState) {
        val summary = state.results.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        state.results.clear()
        state.running.set(false)
    }

    override suspend fun onMessage(state: ParallelState, message: AgentMessage) {
        state.results[message.senderId] = message.content
    }
}
