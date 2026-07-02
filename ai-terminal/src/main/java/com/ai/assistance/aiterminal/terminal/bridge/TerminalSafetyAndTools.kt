package com.ai.assistance.aiterminal.terminal.bridge

/**
 * 危险命令模式库 + 输出摘要器 + 终端工具定义。
 *
 * 融合自原 Apex-agent 仓库的 3 个有价值组件。
 */

// ===== 危险命令模式库 =====

object DangerousCommandPatterns {

    data class RiskAssessment(
        val level: RiskLevel,
        val score: Int,
        val warnings: List<String>,
        val precautions: List<String>,
        val reversible: Boolean,
        val requiresConfirmation: Boolean
    )

    data class CommandPattern(
        val pattern: Regex,
        val riskLevel: RiskLevel,
        val description: String,
        val precautions: List<String>,
        val reversible: Boolean = false
    )

    private val criticalPatterns = listOf(
        CommandPattern(Regex("(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\*)", RegexOption.IGNORE_CASE), RiskLevel.CRITICAL, "删除根目录", listOf("不可逆!", "系统完全损坏"), false),
        CommandPattern(Regex("(format|mkfs)\\s+.*(/dev|/system|/data)", RegexOption.IGNORE_CASE), RiskLevel.CRITICAL, "格式化系统分区", listOf("清除所有数据"), false),
        CommandPattern(Regex("dd\\s+.*of=/dev/", RegexOption.IGNORE_CASE), RiskLevel.CRITICAL, "直接写入设备", listOf("可能损坏系统"), false),
        CommandPattern(Regex("(reboot\\s+-f|fastboot\\s+oem\\s+unlock)", RegexOption.IGNORE_CASE), RiskLevel.CRITICAL, "强制重启/解锁", listOf("可能变砖"), false),
        CommandPattern(Regex("(wipe|reset)\\s+.*(data|all|factory)", RegexOption.IGNORE_CASE), RiskLevel.CRITICAL, "恢复出厂设置", listOf("所有数据删除"), false)
    )

    private val highRiskPatterns = listOf(
        CommandPattern(Regex("rm\\s+(-rf|-r)\\s+/?(data|system|vendor)", RegexOption.IGNORE_CASE), RiskLevel.HIGH, "删除系统目录", listOf("功能异常"), false),
        CommandPattern(Regex("(reboot|shutdown|poweroff)", RegexOption.IGNORE_CASE), RiskLevel.HIGH, "重启/关机", listOf("未保存工作丢失"), true),
        CommandPattern(Regex("kill\\s+-9\\s+1$", RegexOption.IGNORE_CASE), RiskLevel.HIGH, "杀死init", listOf("系统崩溃"), false)
    )

    private val mediumRiskPatterns = listOf(
        CommandPattern(Regex("chmod\\s+777\\s+/(system|data)", RegexOption.IGNORE_CASE), RiskLevel.MEDIUM, "系统目录777权限", listOf("安全漏洞"), true),
        CommandPattern(Regex("(mount|umount)\\s+", RegexOption.IGNORE_CASE), RiskLevel.MEDIUM, "挂载/卸载", listOf("数据不可访问"), true),
        CommandPattern(Regex("su\\s+-c\\s+", RegexOption.IGNORE_CASE), RiskLevel.MEDIUM, "root执行", listOf("需格外谨慎"), true)
    )

    fun assess(command: String): RiskAssessment {
        for (pattern in criticalPatterns + highRiskPatterns + mediumRiskPatterns) {
            if (pattern.pattern.containsMatchIn(command)) {
                val score = when (pattern.riskLevel) { RiskLevel.CRITICAL -> 100; RiskLevel.HIGH -> 75; RiskLevel.MEDIUM -> 50; RiskLevel.LOW -> 10 }
                return RiskAssessment(pattern.riskLevel, score, listOf(pattern.description), pattern.precautions, pattern.reversible, pattern.riskLevel in listOf(RiskLevel.CRITICAL, RiskLevel.HIGH))
            }
        }
        return RiskAssessment(RiskLevel.LOW, 10, emptyList(), emptyList(), true, false)
    }

    fun isSafe(command: String) = assess(command).level == RiskLevel.LOW
    fun requiresConfirmation(command: String) = assess(command).requiresConfirmation
    fun isBlocked(command: String) = assess(command).level == RiskLevel.CRITICAL
}

// ===== 输出摘要器 =====

object OutputSummarizer {

    data class Summary(val originalLength: Int, val summary: String, val keyPoints: List<String>, val statistics: Stats, val outputType: OutputType)
    data class Stats(val totalLines: Int, val nonEmptyLines: Int, val errorCount: Int, val warningCount: Int, val successCount: Int)

    enum class OutputType { COMMAND_OUTPUT, ERROR_OUTPUT, SYSTEM_INFO, FILE_LIST, PROCESS_LIST, NETWORK_INFO, LOG_OUTPUT, UNKNOWN }

    private val errorPatterns = listOf(Regex("(?i)error|failed|fatal"), Regex("(?i)exception|traceback"), Regex("(?i)permission denied"), Regex("(?i)no such file|not found"), Regex("(?i)segfault|abort"))
    private val warningPatterns = listOf(Regex("(?i)warning|deprecated"), Regex("(?i)caution"))
    private val successPatterns = listOf(Regex("(?i)success|ok|done|complete"), Regex("(?i)installed|created"))

    fun summarize(output: String, maxLength: Int = 500): Summary {
        val lines = output.lines()
        val errorLines = lines.filter { l -> errorPatterns.any { it.containsMatchIn(l) } }
        val warningLines = lines.filter { l -> warningPatterns.any { it.containsMatchIn(l) } }
        val successLines = lines.filter { l -> successPatterns.any { it.containsMatchIn(l) } }
        val outputType = detectOutputType(output)

        val keyPoints = mutableListOf<String>()
        if (errorLines.isNotEmpty()) { keyPoints.add("Errors (" + errorLines.size + "):"); keyPoints.addAll(errorLines.take(3).map { "  $it" }) }
        if (warningLines.isNotEmpty()) { keyPoints.add("Warnings (" + warningLines.size + "):"); keyPoints.addAll(warningLines.take(2).map { "  $it" }) }

        val summary = if (output.length <= maxLength) output else buildString {
            appendLine("[Summary] " + output.length + " chars, " + lines.size + " lines, type: " + outputType)
            if (keyPoints.isNotEmpty()) { appendLine("[Key]"); keyPoints.forEach { appendLine(it) } }
            append("[First " + (maxLength / 2) + " chars]"); append(output.take(maxLength / 2)); append("[...]")
        }

        return Summary(output.length, summary, keyPoints, Stats(lines.size, lines.count { it.isNotBlank() }, errorLines.size, warningLines.size, successLines.size), outputType)
    }

    private fun detectOutputType(output: String): OutputType {
        val lower = output.lowercase()
        return when {
            errorPatterns.any { it.containsMatchIn(lower) } -> OutputType.ERROR_OUTPUT
            lower.contains("total ") && lower.contains("drwx") -> OutputType.FILE_LIST
            lower.contains("pid") && lower.contains("user") -> OutputType.PROCESS_LIST
            lower.contains("inet ") || lower.contains("eth0") -> OutputType.NETWORK_INFO
            lower.contains("android version") || lower.contains("sdk") -> OutputType.SYSTEM_INFO
            lower.contains("2024-") || lower.contains("2025-") || lower.contains("2026-") -> OutputType.LOG_OUTPUT
            else -> OutputType.COMMAND_OUTPUT
        }
    }
}

// ===== 终端工具定义 =====

object TerminalToolDefinitions {

    data class TerminalTool(val name: String, val description: String, val parameters: List<ToolParam>, val category: ToolCategory)
    data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = false)

    enum class ToolCategory(val displayName: String) { FILE("文件"), SYSTEM("系统"), PROCESS("进程"), NETWORK("网络"), APP("应用"), SHELL("Shell") }

    val tools: List<TerminalTool> = listOf(
        TerminalTool("terminal_execute_command", "执行终端命令", listOf(ToolParam("command", "string", "命令", true), ToolParam("require_root", "boolean", "root权限")), ToolCategory.SHELL),
        TerminalTool("terminal_list_files", "列目录", listOf(ToolParam("path", "string", "路径", true)), ToolCategory.FILE),
        TerminalTool("terminal_read_file", "读文件", listOf(ToolParam("path", "string", "路径", true), ToolParam("max_lines", "number", "最大行数")), ToolCategory.FILE),
        TerminalTool("terminal_write_file", "写文件", listOf(ToolParam("path", "string", "路径", true), ToolParam("content", "string", "内容", true), ToolParam("append", "boolean", "追加")), ToolCategory.FILE),
        TerminalTool("terminal_search_files", "搜索文件", listOf(ToolParam("directory", "string", "目录", true), ToolParam("pattern", "string", "模式", true)), ToolCategory.FILE),
        TerminalTool("terminal_get_system_info", "系统信息", emptyList(), ToolCategory.SYSTEM),
        TerminalTool("terminal_check_root", "检查root", emptyList(), ToolCategory.SYSTEM),
        TerminalTool("terminal_list_processes", "列进程", emptyList(), ToolCategory.PROCESS),
        TerminalTool("terminal_kill_process", "杀进程", listOf(ToolParam("pid", "number", "PID", true)), ToolCategory.PROCESS),
        TerminalTool("terminal_get_network_info", "网络信息", emptyList(), ToolCategory.NETWORK),
        TerminalTool("terminal_list_apps", "列应用", listOf(ToolParam("system_apps", "boolean", "含系统应用")), ToolCategory.APP),
        TerminalTool("terminal_get_app_info", "应用信息", listOf(ToolParam("package_name", "string", "包名", true)), ToolCategory.APP)
    )

    fun toJsonSchema(): String {
        val sb = StringBuilder("[")
        tools.forEachIndexed { i, tool ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":\"" + tool.name + "\",\"description\":\"" + tool.description + "\",\"parameters\":{\"type\":\"object\",\"properties\":{")
            tool.parameters.forEachIndexed { j, p -> if (j > 0) sb.append(","); sb.append("\"" + p.name + "\":{\"type\":\"" + p.type + "\",\"description\":\"" + p.description + "\"}") }
            sb.append("},\"required\":[")
            sb.append(tool.parameters.filter { it.required }.map { "\"" + it.name + "\"" }.joinToString(","))
            sb.append("]}}")
        }
        sb.append("]")
        return sb.toString()
    }
}
