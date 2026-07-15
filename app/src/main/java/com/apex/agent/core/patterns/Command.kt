package com.apex.agent.core.patterns

// STUBBED: had 2 errors
sealed class CommandResult
data class Failure(val placeholder: String = "")
interface AgentCommand
data class CommandEntry(val placeholder: String = "")
class CommandHistory
class CommandInvoker
class SendMessageCommand
class ExecuteToolCommand
class ModifyConfigCommand
