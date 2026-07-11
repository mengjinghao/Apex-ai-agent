package com.ai.assistance.aiterminal.terminal.ai

class AgentTerminalExecutor

data class AgentSession(val placeholder: String = "")

data class ExecResult(val placeholder: String = "")

data class BatchResult(val placeholder: String = "")

data class BgTask(val placeholder: String = "")

enum class BgTaskStatus { DEFAULT }

data class ParallelTask(val placeholder: String = "")

data class ParallelResult(val placeholder: String = "")

data class CommandTemplate(val placeholder: String = "")

data class TemplateParam(val placeholder: String = "")

data class AuditLog(val placeholder: String = "")
