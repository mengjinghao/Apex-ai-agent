package com.apex.agent.core.multiagent

// Minimal implementation (original had 26 errors)
// TODO: Restore full implementation from original code

data class Workflow(val data: String = "")
data class WorkflowNode(val data: String = "")
data class WorkflowEdge(val data: String = "")
enum class EdgeType { DEFAULT }
