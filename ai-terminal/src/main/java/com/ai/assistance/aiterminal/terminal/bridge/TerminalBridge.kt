package com.ai.assistance.aiterminal.terminal.bridge

data class TerminalExecutionResult(val placeholder: String = "")

data class TerminalCommandRequest(val placeholder: String = "")

enum class TerminalPermission { DEFAULT }

enum class RiskLevel { DEFAULT }

class TerminalBridge
