package com.apex.agent.core.kanban

// STUBBED: had 81 errors
class KanbanViewModel
data class KanbanUiState(val placeholder: String = "")
data class ColumnUiState(val placeholder: String = "")
data class TaskUiState(val placeholder: String = "")
data class TaskStatistics(val placeholder: String = "")
sealed class BoardEvent
data class TaskAdded(val placeholder: String = "")
data class TaskMoved(val placeholder: String = "")
data class TaskStarted(val placeholder: String = "")
data class TaskCompleted(val placeholder: String = "")
data class TaskDispatched(val placeholder: String = "")
data class TaskBlocked(val placeholder: String = "")
data class DispatchFailed(val placeholder: String = "")
sealed class FlowStep
data class ColumnStart(val placeholder: String = "")
data class TaskInColumn(val placeholder: String = "")
data class ColumnEnd(val placeholder: String = "")
enum class Type { DEFAULT }
