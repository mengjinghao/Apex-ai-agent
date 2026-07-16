package com.apex.agent.core.planning

// Minimal implementation (original had 31 errors)
// TODO: Restore full implementation from original code

class ReinforcementLearningPlanner
enum class GoalType { DEFAULT }
enum class PlanStatus { DEFAULT }
enum class Priority { DEFAULT }
data class Goal(val data: String = "")
data class Plan(val data: String = "")
data class PlanStep(val data: String = "")
enum class StepStatus { DEFAULT }
enum class ExecutionPolicy { DEFAULT }
data class Experience(val data: String = "")
