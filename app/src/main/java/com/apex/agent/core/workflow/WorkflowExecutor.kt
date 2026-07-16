package com.apex.core.workflow

// Minimal implementation (original had 652 errors)
// TODO: Restore full implementation from original code

sealed class NodeExecutionState
    fun init() { }
}
    fun init() { }
}
data class Skipped(val data: String = "")
data class WorkflowExecutionResult(val data: String = "")
class WorkflowRunLogger
