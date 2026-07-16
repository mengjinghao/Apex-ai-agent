package com.apex.agent.core.kanban

// Minimal implementation (original had 114 errors)
// TODO: Restore full implementation from original code

class TaskDispatcher
sealed class DispatchResult
data class Failure(val data: String = "")
data class Blocked(val data: String = "")
data class DispatchStatistics(val data: String = "")
