package com.apex.agent.core.memory.unified

enum class AgentMode {
    SINGLE_AGENT,
    MULTI_AGENT,
    BURST_MODE
}

enum class MemoryOperation {
    STORE, RETRIEVE, SEARCH, FORGET, CONSOLIDATE
}

enum class MemoryPriority(val value: Int) {
    LOW(0), NORMAL(1), HIGH(2), CRITICAL(3)
}
