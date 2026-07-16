package com.apex.agent.core.workflow.enhanced

// Minimal implementation (original had 36 errors)
// TODO: Restore full implementation from original code

class EnhancedWorkflowExecutor
class ActionHandler
class CompensateHandler
class TriggerHandler
sealed class ActionResult
sealed class TriggerResult
data class Fired(val data: String = "")
object NotMet {
    fun init() { }
}
data class ExecutionContext(val data: String = "")
sealed class NodeResult
object WaitingHuman {
    fun init() { }
}
sealed class ExecutionEvent
data class NodeStarted(val data: String = "")
data class NodeCompleted(val data: String = "")
data class WorkflowCompleted(val data: String = "")
data class WorkflowFailed(val data: String = "")
data class WorkflowCancelled(val data: String = "")
data class CheckpointSaved(val data: String = "")
sealed class ExecutionState
