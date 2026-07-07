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
class FreeDialogMode @Inject constructor(
    @ApplicationContext context: Context,
    agentManager: AgentManager
) : AbstractCollaborationMode<FreeDialogMode.DialogState>(context, agentManager) {

    class DialogState(task: Task, agents: List<Agent>) : ExecutionState(task, agents) {
        var messageCount: Int = 0
        val maxMessages: Int = 10
        val dialogHistory = mutableListOf<AgentMessage>()
        val agentMessageCount = mutableMapOf<String, Int>()
    }

    override fun createState(task: Task, agents: List<Agent>): DialogState {
        return DialogState(task, agents)
    }

    override suspend fun runStep(state: DialogState) {
        if (state.messageCount >= state.maxMessages) {
            finishDialog(state)
            return
        }

        val activeAgents = state.agents.filter { agent ->
            val count = state.agentMessageCount.getOrDefault(agent.id, 0)
            count < 3
        }

        if (activeAgents.isEmpty()) {
            finishDialog(state)
            return
        }

        val speaker = selectNextSpeaker(state, activeAgents)
        if (speaker != null) {
            updateAgentStatus(state, speaker.id, AgentStatus.WORKING)

            val response = generateFreeResponse(speaker)
            val message = createAgentMessage(speaker.id, "", response)

            state.dialogHistory.add(message)
            state.agentMessageCount[speaker.id] = state.agentMessageCount.getOrDefault(speaker.id, 0) + 1
            state.messageCount++

            updateAgentProgress(state, speaker.id, state.messageCount.toFloat() / state.maxMessages)
            updateAgentStatus(state, speaker.id, AgentStatus.IDLE)
        }
    }

    private fun selectNextSpeaker(state: DialogState, activeAgents: List<Agent>): Agent? {
        return activeAgents.minByOrNull { state.agentMessageCount.getOrDefault(it.id, 0) }
    }

    private fun generateFreeResponse(agent: Agent): String {
        return context.getString(R.string.free_dialog_response_format, agent.name)
    }

    private fun finishDialog(state: DialogState) {
        state.agents.forEach { agent ->
            updateAgentStatus(state, agent.id, AgentStatus.FINISHED)
        }
        state.dialogHistory.clear()
        state.agentMessageCount.clear()
        state.running.set(false)
    }

    override suspend fun onMessage(state: DialogState, message: AgentMessage) {
        state.dialogHistory.add(message)
        state.agentMessageCount[message.senderId] = state.agentMessageCount.getOrDefault(message.senderId, 0) + 1
        state.messageCount++
    }
}
