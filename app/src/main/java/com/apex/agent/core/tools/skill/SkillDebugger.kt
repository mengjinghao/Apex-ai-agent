package com.apex.agent.core.tools.skill

// Minimal implementation (original had 10 errors)
// TODO: Restore full implementation from original code

class SkillDebugger
enum class BreakpointType { DEFAULT }
data class Breakpoint(val data: String = "")
data class StackFrame(val data: String = "")
data class DebugSession(val data: String = "")
enum class PauseReason { DEFAULT }
interface DebugListener
