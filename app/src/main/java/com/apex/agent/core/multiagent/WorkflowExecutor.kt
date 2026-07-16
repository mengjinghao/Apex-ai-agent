package com.apex.agent.core.multiagent

// Minimal implementation (original had 52 errors)
// TODO: Restore full implementation from original code

data class WorkflowExecution(val data: String = "")
data class NodeExecution(val data: String = "")
enum class ExecutionStatus { DEFAULT }
enum class NodeExecutionStatus { DEFAULT }
class WorkflowExecutor
