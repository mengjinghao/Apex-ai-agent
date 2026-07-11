package com.ai.assistance.aiterminal.terminal.bridge

object DangerousCommandPatterns

data class RiskAssessment(val placeholder: String = "")

data class CommandPattern(val placeholder: String = "")

object OutputSummarizer

data class Summary(val placeholder: String = "")

data class Stats(val placeholder: String = "")

enum class OutputType { DEFAULT }

object TerminalToolDefinitions

data class TerminalTool(val placeholder: String = "")

data class ToolParam(val placeholder: String = "")

enum class ToolCategory { DEFAULT }
