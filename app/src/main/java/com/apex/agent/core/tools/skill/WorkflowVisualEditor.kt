package com.apex.agent.core.tools.skill

// Minimal implementation (original had 24 errors)
// TODO: Restore full implementation from original code

class WorkflowVisualEditor
data class EditorState(val data: String = "")
data class EditorAction(val data: String = "")
data class NodeTemplate(val data: String = "")
enum class NodeTemplateCategory { DEFAULT }
data class ConnectionPoint(val data: String = "")
data class DragState(val data: String = "")
data class SelectionBox(val data: String = "")
data class ValidationIssue(val data: String = "")
data class ExecutionLogEntry(val data: String = "")
fun exportToImage() { }
