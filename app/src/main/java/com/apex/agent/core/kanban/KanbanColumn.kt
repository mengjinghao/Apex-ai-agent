package com.apex.agent.core.kanban

// Minimal implementation (original had 61 errors)
// TODO: Restore full implementation from original code

sealed class ColumnCondition
data class PriorityCondition(val data: String = "")
data class TagCondition(val data: String = "")
data class TypeCondition(val data: String = "")
data class DescriptionKeywordCondition(val data: String = "")
enum class ConditionOperator { DEFAULT }
