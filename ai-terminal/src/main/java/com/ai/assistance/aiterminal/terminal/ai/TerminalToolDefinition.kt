package com.ai.assistance.aiterminal.terminal.ai

import com.ai.assistance.aiterminal.terminal.data.model.ToolParameterSchema
import com.ai.assistance.aiterminal.terminal.data.model.ToolPrompt

object TerminalToolDefinition {

    val terminalTools: List<ToolPrompt> = listOf(
        ToolPrompt(
            id = "terminal_execute_command",
            toolName = "terminal_execute_command",
            prompt = "执行终端命令并返回结果",
            context = "在Android设备的终端中执行指定的命令，返回执行结果。适用于文件操作、系统信息查询、应用管理等场景。",
            constraints = listOf("需要root权限的操作会返回错误")
        ),
        ToolPrompt(
            id = "terminal_list_files",
            toolName = "terminal_list_files",
            prompt = "列出指定目录中的文件和文件夹",
            context = "列出指定目录中的所有文件和文件夹，包括隐藏文件，显示详细信息。"
        ),
        ToolPrompt(
            id = "terminal_search_files",
            toolName = "terminal_search_files",
            prompt = "在指定目录中搜索文件",
            context = "在指定目录及其子目录中搜索匹配指定模式的文件。"
        ),
        ToolPrompt(
            id = "terminal_read_file",
            toolName = "terminal_read_file",
            prompt = "读取文件内容",
            context = "读取并返回指定文件的内容。"
        ),
        ToolPrompt(
            id = "terminal_write_file",
            toolName = "terminal_write_file",
            prompt = "写入文件内容",
            context = "将指定内容写入文件，可选择追加或覆盖。"
        ),
        ToolPrompt(
            id = "terminal_get_system_info",
            toolName = "terminal_get_system_info",
            prompt = "获取系统信息",
            context = "获取Android设备的系统信息，包括版本、型号、CPU、内存等。"
        ),
        ToolPrompt(
            id = "terminal_list_apps",
            toolName = "terminal_list_apps",
            prompt = "列出已安装的应用",
            context = "列出设备上已安装的应用包。"
        ),
        ToolPrompt(
            id = "terminal_get_app_info",
            toolName = "terminal_get_app_info",
            prompt = "获取应用信息",
            context = "获取指定应用的详细信息。"
        ),
        ToolPrompt(
            id = "terminal_kill_process",
            toolName = "terminal_kill_process",
            prompt = "结束进程",
            context = "强制结束指定的进程。"
        ),
        ToolPrompt(
            id = "terminal_list_processes",
            toolName = "terminal_list_processes",
            prompt = "列出运行中的进程",
            context = "列出当前运行的进程及其资源使用情况。"
        ),
        ToolPrompt(
            id = "terminal_check_root",
            toolName = "terminal_check_root",
            prompt = "检查Root权限",
            context = "检查设备是否具有Root权限。"
        ),
        ToolPrompt(
            id = "terminal_get_network_info",
            toolName = "terminal_get_network_info",
            prompt = "获取网络信息",
            context = "获取设备的网络连接信息。"
        )
    )

    fun getToolByName(name: String): ToolPrompt? {
        return terminalTools.find { it.toolName == name }
    }

    fun getAllToolNames(): List<String> {
        return terminalTools.map { it.toolName }
    }

    fun getToolDescriptions(): Map<String, String> {
        return terminalTools.associate { it.toolName to it.prompt }
    }
}
