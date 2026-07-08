package com.apex.agent.presentation.multiagent.state

/**
 * Multi-agent page state stub.
 * Local stub to avoid circular dependency on the app module.
 */
data class MultiAgentPageState(
    val agents: List<String> = emptyList(),
    val currentAgent: String = "",
    val isRunning: Boolean = false
)
