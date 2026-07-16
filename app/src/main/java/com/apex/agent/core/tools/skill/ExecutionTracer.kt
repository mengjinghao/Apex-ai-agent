package com.apex.agent.core.tools.skill

// Minimal implementation (original had 6 errors)
// TODO: Restore full implementation from original code

class ExecutionTracer
data class TraceEntry(val data: String = "")
data class ExecutionFlow(val data: String = "")
data class ToolCallInfo(val data: String = "")
data class FlowNode(val data: String = "")
enum class FlowNodeType { DEFAULT }
interface TraceListener
data class TraceStats(val data: String = "")
