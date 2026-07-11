package com.ai.assistance.aiterminal.terminal.ai

interface LLMAPI

class AITerminalHelper

enum class Mode { DEFAULT }

enum class ReasoningMode { DEFAULT }

data class OptimizationSuggestion(val placeholder: String = "")

data class ScriptGenerationResult(val placeholder: String = "")

data class CodeExplanation(val placeholder: String = "")

data class TaskStep(val placeholder: String = "")

data class TaskPlanResult(val placeholder: String = "")
