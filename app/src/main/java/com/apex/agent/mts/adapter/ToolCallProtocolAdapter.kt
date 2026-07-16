package com.apex.agent.mts.adapter

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

enum class Protocol { DEFAULT }
data class ToolDefinitions(val data: String = "")
data class ProtocolConfig(val data: String = "")
class ToolCallProtocolAdapter
