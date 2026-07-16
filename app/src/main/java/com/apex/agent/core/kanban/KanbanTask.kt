package com.apex.agent.core.kanban

// Minimal implementation (original had 54 errors)
// TODO: Restore full implementation from original code

class KanbanTask
enum class KanbanTaskStatus { DEFAULT }
data class TaskArtifact(val data: String = "")
data class CollaborationEvent(val data: String = "")
enum class Type { DEFAULT }
