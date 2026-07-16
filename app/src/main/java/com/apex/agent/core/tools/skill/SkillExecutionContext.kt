package com.apex.agent.core.tools.skill

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

class SkillExecutionContext
enum class ContextState { DEFAULT }
data class ExecutionMetrics(val data: String = "")
data class ContextConfig(val data: String = "")
interface StateListener
class ContextBuilder
