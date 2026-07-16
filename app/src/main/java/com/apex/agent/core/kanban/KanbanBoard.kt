package com.apex.agent.core.kanban

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

class KanbanBoard
data class BoardState(val data: String = "")
data class BoardStatistics(val data: String = "")
data class ColumnStats(val data: String = "")
class KanbanColumn
enum class Status { DEFAULT }
data class ConversationEntry(val data: String = "")
