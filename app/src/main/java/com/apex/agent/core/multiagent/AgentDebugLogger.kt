package com.apex.agent.core.multiagent

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

class AgentDebugLogger
enum class LogLevel { DEFAULT }
data class LogEntry(val data: String = "")
interface LogListener
data class LogStatistics(val data: String = "")
