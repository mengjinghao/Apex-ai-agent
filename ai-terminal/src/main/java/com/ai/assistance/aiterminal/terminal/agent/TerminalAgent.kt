

class AgentState

object Idle

object Probing

object Thinking

object Executing

object Fixing

object Reflecting

object Done

data class Error(val placeholder: String = "")

data class ExecutionStep(val placeholder: String = "")

enum class StepType { DEFAULT }

class TerminalAgent

data class ExecutionPlan(val placeholder: String = "")

data class CommandInfo(val placeholder: String = "")

data class ExecutionResult(val placeholder: String = "")

data class CommandResult(val placeholder: String = "")
