package com.apex.agent.infrastructure.monitoring

import com.apex.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

interface AgentMonitor {

    fun recordAgentCreated(agentId: String)
    fun recordAgentDestroyed(agentId: String)
    fun recordAgentError(agentId: String, throwable: Throwable)
    fun activeAgentCount(): Int

    @Singleton
    class Default constructor() : AgentMonitor {
        companion object {
            private const val TAG = "AgentMonitor"
        }

        private val activeAgents = mutableSetOf<String>()

        override fun recordAgentCreated(agentId: String) {
            synchronized(activeAgents) {
                activeAgents.add(agentId)
            }
        }

        override fun recordAgentDestroyed(agentId: String) {
            synchronized(activeAgents) {
                activeAgents.remove(agentId)
            }
        }

        override fun recordAgentError(agentId: String, throwable: Throwable) {
            AppLogger.e(TAG, "Agent error: $agentId", throwable)
        }

        override fun activeAgentCount(): Int {
            return synchronized(activeAgents) { activeAgents.size }
        }
    }
}
