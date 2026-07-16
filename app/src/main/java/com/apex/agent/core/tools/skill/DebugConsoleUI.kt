package com.apex.agent.core.tools.skill

// Minimal implementation (original had 154 errors)
// TODO: Restore full implementation from original code

class DebugConsoleUI
enum class ConsoleLevel { DEFAULT }
data class ConsoleLine(val data: String = "")
data class DebugState(val data: String = "")
data class WatchVariable(val data: String = "")
interface ConsoleListener
