package com.ai.assistance.aiterminal.terminal.ai

import com.ai.assistance.aiterminal.terminal.model.ToolPrompt
import com.ai.assistance.aiterminal.terminal.model.ToolParameterSchema

object TerminalToolDefinition {
    val terminalTools: List<ToolPrompt> = listOf(
        ToolPrompt("agent_exec", "Execute shell command", listOf(ToolParameterSchema("command", "string", "Shell command", true))),
        ToolPrompt("agent_exec_batch", "Execute batch commands", listOf(ToolParameterSchema("commands", "array", "Command list", true))),
        ToolPrompt("agent_read_file", "Read file", listOf(ToolParameterSchema("path", "string", "File path", true))),
        ToolPrompt("agent_write_file", "Write file", listOf(ToolParameterSchema("path", "string", "File path", true), ToolParameterSchema("content", "string", "Content", true))),
        ToolPrompt("agent_list_dir", "List directory", listOf(ToolParameterSchema("path", "string", "Dir path", true))),
        ToolPrompt("agent_search", "Search files", listOf(ToolParameterSchema("pattern", "string", "Pattern", true))),
        ToolPrompt("agent_bg_run", "Background task", listOf(ToolParameterSchema("command", "string", "Command", true))),
        ToolPrompt("agent_bg_status", "Check task", listOf(ToolParameterSchema("task_id", "string", "Task ID", true))),
        ToolPrompt("agent_bg_list", "List tasks", emptyList()),
        ToolPrompt("agent_bg_kill", "Kill task", listOf(ToolParameterSchema("task_id", "string", "Task ID", true))),
    )
}
