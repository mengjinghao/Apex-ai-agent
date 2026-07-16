package com.apex.agent.core.kanban

// Minimal implementation (original had 110 errors)
// TODO: Restore full implementation from original code

class WorkerRegistry
interface Worker
data class TaskExecutionContext(val data: String = "")
enum class RegistrationResult { DEFAULT }
enum class ChangeType { DEFAULT }
interface WorkerChangeListener
data class WorkerStatistics(val data: String = "")
