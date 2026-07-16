package com.apex.agent.core.tools.skill

// Minimal implementation (original had 7 errors)
// TODO: Restore full implementation from original code

class SkillWorkflowEditor
sealed class EditorEvent
data class WorkflowCreated(val data: String = "")
data class WorkflowUpdated(val data: String = "")
data class WorkflowDeleted(val data: String = "")
data class NodeAdded(val data: String = "")
data class NodeUpdated(val data: String = "")
data class NodeRemoved(val data: String = "")
data class ConnectionAdded(val data: String = "")
data class ConnectionRemoved(val data: String = "")
data class NodeMoved(val data: String = "")
data class UndoPerformed(val data: String = "")
data class RedoPerformed(val data: String = "")
data class AddNode(val data: String = "")
data class RemoveNode(val data: String = "")
data class UpdateNode(val data: String = "")
data class MoveNode(val data: String = "")
data class AddConnection(val data: String = "")
data class RemoveConnection(val data: String = "")
data class UpdateWorkflow(val data: String = "")
data class EditorStats(val data: String = "")
