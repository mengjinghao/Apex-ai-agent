package com.ai.assistance.aiterminal.terminal.ai

data class TerminalCommandTemplate(val placeholder: String = "")

data class TemplateParameter(val placeholder: String = "")

enum class TemplateCategory { DEFAULT }

enum class RiskLevel { DEFAULT }

object TerminalCommandTemplates
