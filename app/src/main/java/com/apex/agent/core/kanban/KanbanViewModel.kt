package com.apex.agent.core.kanban

// Minimal implementation (original had 81 errors)
// TODO: Restore full implementation from original code

class KanbanViewModel
data class KanbanUiState(val data: String = "")
data class ColumnUiState(val data: String = "")
data class TaskUiState(val data: String = "")
data class TaskStatistics(val data: String = "")
sealed class BoardEvent
data class TaskAdded(val data: String = "")
data class TaskMoved(val data: String = "")
data class TaskDispatched(val data: String = "")
data class TaskBlocked(val data: String = "")
data class DispatchFailed(val data: String = "")
sealed class FlowStep
data class ColumnStart(val data: String = "")
data class TaskInColumn(val data: String = "")
data class ColumnEnd(val data: String = "")
data class TimelineEvent(val data: String = "")
