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
class DebateReviewMode @Inject constructor(
    @ApplicationContext context: Context,
    agentManager: AgentManager
) : AbstractCollaborationMode<DebateReviewMode.DebateState>(context, agentManager) {

    class DebateState(task: Task, agents: List<Agent>) : ExecutionState(task, agents) {
        var phase: DebatePhase = DebatePhase.OPENING_ARGUMENT
        var round: Int = 0
        val maxRounds: Int = 3
        val arguments = mutableListOf<Pair<String, String>>()
        val scores = mutableMapOf<String, Float>()
    }

    override fun createState(task: Task, agents: List<Agent>): DebateState {
        return DebateState(task, agents)
    }

    override suspend fun runStep(state: DebateState) {
        when (state.phase) {
            DebatePhase.OPENING_ARGUMENT -> executeOpeningArguments(state)
            DebatePhase.CROSS_EXAMINATION -> executeCrossExamination(state)
            DebatePhase.REBUTTAL -> executeRebuttal(state)
            DebatePhase.FINAL_ARGUMENT -> executeFinalArguments(state)
            DebatePhase.JUDGMENT -> executeJudgment(state)
        }
    }

    private fun executeOpeningArguments(state: DebateState) {
        val agents = state.agents
        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WORKING)
            val argument = generateOpeningArgument(agent, state.task.description)
            state.arguments.add(agent.id to argument)
            updateAgentProgress(state, agent.id, 0.25f)
        }

        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WAITING)
        }

        state.round = 1
        state.phase = DebatePhase.CROSS_EXAMINATION
    }

    private fun executeCrossExamination(state: DebateState) {
        val agents = state.agents
        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WORKING)
            val question = generateQuestion(agent, state.arguments)
            state.arguments.add(agent.id to question)
            updateAgentProgress(state, agent.id, 0.25f + 0.25f * (state.round - 1) / state.maxRounds)
        }

        state.round++

        if (state.round > state.maxRounds) {
            state.phase = DebatePhase.REBUTTAL
        }
    }

    private fun executeRebuttal(state: DebateState) {
        val agents = state.agents
        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WORKING)
            val rebuttal = generateRebuttal(agent, state.arguments)
            state.arguments.add(agent.id to rebuttal)
            updateAgentProgress(state, agent.id, 0.75f)
        }

        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WAITING)
        }

        state.phase = DebatePhase.FINAL_ARGUMENT
    }

    private fun executeFinalArguments(state: DebateState) {
        val agents = state.agents
        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.WORKING)
            val finalArgument = generateFinalArgument(agent)
            state.arguments.add(agent.id to finalArgument)
            updateAgentProgress(state, agent.id, 0.9f)
        }

        agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.FINISHED)
        }

        state.phase = DebatePhase.JUDGMENT
    }

    private fun executeJudgment(state: DebateState) {
        state.agents.forEach { agent ->
            val score = calculateScore(agent, state.arguments)
            state.scores[agent.id] = score
        }

        getSupervisorAgent(state)?.let { supervisor ->
            updateAgentStatus(state, supervisor.id, AgentStatus.FINISHED)
        }

        state.arguments.clear()
        state.running.set(false)
    }

    private fun generateOpeningArgument(agent: Agent, taskDescription: String): String {
        return context.getString(R.string.debate_opening_argument_format, agent.name, taskDescription)
    }

    private fun generateQuestion(agent: Agent, previousArguments: List<Pair<String, String>>): String {
        return context.getString(R.string.debate_question_format, agent.name)
    }

    private fun generateRebuttal(agent: Agent, previousArguments: List<Pair<String, String>>): String {
        return context.getString(R.string.debate_rebuttal_format, agent.name)
    }

    private fun generateFinalArgument(agent: Agent): String {
        return context.getString(R.string.debate_final_argument_format, agent.name)
    }

    private fun calculateScore(agent: Agent, arguments: List<Pair<String, String>>): Float {
        return (Math.random() * 0.3 + 0.7).toFloat()
    }

    override suspend fun onMessage(state: DebateState, message: AgentMessage) {
        state.arguments.add(message.senderId to message.content)
    }

    enum class DebatePhase {
        OPENING_ARGUMENT,
        CROSS_EXAMINATION,
        REBUTTAL,
        FINAL_ARGUMENT,
        JUDGMENT
    }
}
