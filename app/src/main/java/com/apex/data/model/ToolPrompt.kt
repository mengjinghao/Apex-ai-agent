package com.apex.data.model

data class ToolPrompt(
    val name: String,
    val description: String,
    val parametersStructured: List<ToolParameterSchema> = emptyList(),
    val default: String? = null
)

data class ToolParameterSchema(
    val name: String,
    val type: String,
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null
)

enum class SystemToolPromptCategory {
    FILE, TERMINAL, WEB, SYSTEM, MEMORY, WORKFLOW, HTTP, UI, MCP, SKILL,
    CHAT, CALCULATOR, DOCUMENT, PACK, CONFIG, ACCESSIBILITY, ROOT, DEBUGGER
}
