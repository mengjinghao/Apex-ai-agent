package com.ai.assistance.aiterminal.terminal.ai

enum class DeepSeekModel { DEFAULT }

enum class ReasoningEffort { DEFAULT }

data class ToolDefinition(val placeholder: String = "")

data class DeepSeekToolCall(val placeholder: String = "")

class DeepSeekApi

data class FunctionCallResponse(val placeholder: String = "")
