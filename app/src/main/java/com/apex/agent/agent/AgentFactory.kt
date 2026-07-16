package com.apex.agent

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

interface AgentFactory
object AgentFactoryRegistry {
    fun init() { }
}
class BuiltinAgentFactory
object AgentPool {
    fun init() { }
}
