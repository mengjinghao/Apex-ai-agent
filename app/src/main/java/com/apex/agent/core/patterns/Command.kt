package com.apex.agent.core.patterns

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

sealed class CommandResult
interface AgentCommand
data class CommandEntry(val data: String = "")
class CommandHistory
class CommandInvoker
class SendMessageCommand
class ExecuteToolCommand
class ModifyConfigCommand
