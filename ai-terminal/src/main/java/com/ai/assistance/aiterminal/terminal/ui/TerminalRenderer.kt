package com.ai.assistance.aiterminal.terminal.ui

/**
 * 终端富文本渲染器。
 *
 * 将 [TerminalMessage] 渲染为带颜色的终端输出格式。
 * 支持 ANSI 转义码、颜色映射、表格、进度条、边框等。
 *
 * # 渲染规则
 *
 * | 消息类型 | 颜色 | 前缀 | 格式 |
 * |----------|------|------|------|
 * | COMMAND | 电光青 | ▌ | 加粗 |
 * | OUTPUT | 柔和白 | ▸ | 正常 |
 * | ERROR | 玫瑰红 | ✗ | 加粗 |
 * | SYSTEM | 灰色 | ⚡ | 斜体 |
 * | AGENT | 角色色 | 🤖 | 带角色标签 |
 * | BURST | 珊瑚粉 | 🔥 | 闪烁 |
 * | SUCCESS | 薄荷绿 | ✓ | 加粗 |
 * | WARNING | 琥珀金 | ⚠ | 正常 |
 * | INFO | 天空蓝 | ℹ | 正常 |
 * | DIVIDER | 灰色 | ── | 居中分隔线 |
 */
object TerminalRenderer {

    /** ANSI 转义码。 */
    object Ansi {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val DIM = "\u001B[2m"
        const val ITALIC = "\u001B[3m"
        const val UNDERLINE = "\u001B[4m"
        const val BLINK = "\u001B[5m"
        const val REVERSE = "\u001B[7m"

        // 前景色（256 色）
        const val FG_CYAN = "\u001B[38;5;51m"       // 电光青
        const val FG_PINK = "\u001B[38;5;204m"      // 珊瑚粉
        const val FG_GREEN = "\u001B[38;5;120m"     // 薄荷绿
        const val FG_YELLOW = "\u001B[38;5;220m"    // 琥珀金
        const val FG_RED = "\u001B[38;5;203m"       // 玫瑰红
        const val FG_BLUE = "\u001B[38;5;75m"       // 天空蓝
        const val FG_WHITE = "\u001B[38;5;255m"     // 柔和白
        const val FG_GRAY = "\u001B[38;5;245m"      // 灰色
        const val FG_DIM_GRAY = "\u001B[38;5;238m"  // 暗灰

        // Agent 角色色
        const val FG_SUPERVISOR = "\u001B[38;5;51m"  // 电光青
        const val FG_WORKER = "\u001B[38;5;120m"     // 薄荷绿
        const val FG_REVIEWER = "\u001B[38;5;220m"   // 琥珀金
        const val FG_CRITIC = "\u001B[38;5;204m"     // 珊瑚粉
        const val FG_OBSERVER = "\u001B[38;5;75m"    // 天空蓝
        const val FG_SYSTEM_AGENT = "\u001B[38;5;245m" // 灰色
    }

    /**
     * 渲染单条消息为带颜色的字符串。
     *
     * @param message 终端消息
     * @param useAnsi 是否使用 ANSI 颜色码（false 则纯文本）
     * @return 渲染后的字符串
     */
    fun render(message: TerminalMessage, useAnsi: Boolean = true): String {
        if (useAnsi) {
            return renderAnsi(message)
        }
        return renderPlain(message)
    }

    /**
     * ANSI 彩色渲染。
     */
    private fun renderAnsi(message: TerminalMessage): String {
        val time = message.formattedTime
        return when (message.type) {
            TerminalMessageType.COMMAND -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_CYAN}${Ansi.BOLD}▌ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.OUTPUT -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_WHITE}▸ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.ERROR -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_RED}${Ansi.BOLD}✗ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.SYSTEM -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_GRAY}${Ansi.ITALIC}⚡ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.AGENT -> {
                val roleColor = getAgentColor(message.agentRole)
                val roleName = message.agentRole ?: "Agent"
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${roleColor}🤖 [$roleName] ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.BURST -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_PINK}🔥 ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.SUCCESS -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_GREEN}${Ansi.BOLD}✓ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.WARNING -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_YELLOW}⚠ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.INFO -> {
                "${Ansi.FG_DIM_GRAY}[$time]${Ansi.RESET} ${Ansi.FG_BLUE}ℹ ${message.content}${Ansi.RESET}"
            }
            TerminalMessageType.DIVIDER -> {
                renderDivider(message.content)
            }
        }
    }

    /**
     * 纯文本渲染（无 ANSI）。
     */
    private fun renderPlain(message: TerminalMessage): String {
        val time = message.formattedTime
        return when (message.type) {
            TerminalMessageType.COMMAND -> "[$time] ▌ ${message.content}"
            TerminalMessageType.OUTPUT -> "[$time] ▸ ${message.content}"
            TerminalMessageType.ERROR -> "[$time] ✗ ${message.content}"
            TerminalMessageType.SYSTEM -> "[$time] ⚡ ${message.content}"
            TerminalMessageType.AGENT -> "[$time] 🤖 [${message.agentRole ?: "Agent"}] ${message.content}"
            TerminalMessageType.BURST -> "[$time] 🔥 ${message.content}"
            TerminalMessageType.SUCCESS -> "[$time] ✓ ${message.content}"
            TerminalMessageType.WARNING -> "[$time] ⚠ ${message.content}"
            TerminalMessageType.INFO -> "[$time] ℹ ${message.content}"
            TerminalMessageType.DIVIDER -> renderPlainDivider(message.content)
        }
    }

    /**
     * 渲染分隔线。
     *
     * @param title 标题（可选）
     * @return 带标题的分隔线，如 "─── 标题 ───"
     */
    fun renderDivider(title: String = "", width: Int = 50, useAnsi: Boolean = true): String {
        val titleText = if (title.isNotBlank()) " $title " else ""
        val lineCount = ((width - titleText.length) / 2).coerceAtLeast(2)
        val line = "─" * lineCount
        return if (useAnsi) {
            "${Ansi.FG_DIM_GRAY}$line${Ansi.FG_GRAY}$titleText${Ansi.RESET}${Ansi.FG_DIM_GRAY}$line${Ansi.RESET}"
        } else {
            "$line$titleText$line"
        }
    }

    private fun renderPlainDivider(title: String = "", width: Int = 50): String {
        val titleText = if (title.isNotBlank()) " $title " else ""
        val lineCount = ((width - titleText.length) / 2).coerceAtLeast(2)
        val line = "─" * lineCount
        return "$line$titleText$line"
    }

    /**
     * 渲染进度条。
     *
     * @param progress 0..1
     * @param width 进度条宽度（字符数）
     * @param label 标签
     * @param useAnsi 是否使用颜色
     * @return 进度条字符串
     */
    fun renderProgressBar(
        progress: Float,
        width: Int = 30,
        label: String = "",
        useAnsi: Boolean = true
    ): String {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val filled = (progress * width).toInt().coerceIn(0, width)
        val empty = width - filled
        val bar = "█" * filled + "░" * empty

        return if (useAnsi) {
            val color = when {
                percent >= 100 -> Ansi.FG_GREEN
                percent >= 50 -> Ansi.FG_CYAN
                percent >= 25 -> Ansi.FG_YELLOW
                else -> Ansi.FG_RED
            }
            val labelText = if (label.isNotBlank()) "$label: " else ""
            "${Ansi.FG_GRAY}$labelText${Ansi.RESET}$color$bar${Ansi.RESET} $percent%"
        } else {
            val labelText = if (label.isNotBlank()) "$label: " else ""
            "$labelText$bar $percent%"
        }
    }

    /**
     * 渲染表格。
     *
     * @param headers 表头
     * @param rows 数据行
     * @param useAnsi 是否使用颜色
     * @return 表格字符串
     */
    fun renderTable(
        headers: List<String>,
        rows: List<List<String>>,
        useAnsi: Boolean = true
    ): String {
        if (headers.isEmpty()) return ""

        // 计算每列宽度
        val colWidths = headers.indices.map { colIndex ->
            val headerWidth = headers[colIndex].length
            val maxRowWidth = rows.maxOfOrNull { row ->
                row.getOrNull(colIndex)?.length ?: 0
            } ?: 0
            maxOf(headerWidth, maxRowWidth) + 2
        }

        val separator = "┌${colWidths.joinToString("┬") { "─" * it }}┐"
        val headerLine = buildString {
            append("│")
            headers.forEachIndexed { i, h ->
                append(" ${h.padEnd(colWidths[i] - 1)}│")
            }
        }
        val midSeparator = "├${colWidths.joinToString("┼") { "─" * it }}┤"
        val bottomSeparator = "└${colWidths.joinToString("┴") { "─" * it }}┘"

        val sb = StringBuilder()
        if (useAnsi) {
            sb.append("${Ansi.FG_DIM_GRAY}$separator${Ansi.RESET}\n")
            sb.append("${Ansi.FG_CYAN}${Ansi.BOLD}$headerLine${Ansi.RESET}\n")
            sb.append("${Ansi.FG_DIM_GRAY}$midSeparator${Ansi.RESET}\n")
            rows.forEach { row ->
                sb.append("${Ansi.FG_WHITE}│")
                headers.indices.forEach { i ->
                    val cell = row.getOrNull(i) ?: ""
                    sb.append(" ${cell.padEnd(colWidths[i] - 1)}│")
                }
                sb.append(Ansi.RESET)
                sb.append("\n")
            }
            sb.append("${Ansi.FG_DIM_GRAY}$bottomSeparator${Ansi.RESET}")
        } else {
            sb.append("$separator\n")
            sb.append("$headerLine\n")
            sb.append("$midSeparator\n")
            rows.forEach { row ->
                sb.append("│")
                headers.indices.forEach { i ->
                    val cell = row.getOrNull(i) ?: ""
                    sb.append(" ${cell.padEnd(colWidths[i] - 1)}│")
                }
                sb.append("\n")
            }
            sb.append(bottomSeparator)
        }

        return sb.toString()
    }

    /**
     * 渲染状态栏。
     *
     * @param bar 状态栏数据
     * @param useAnsi 是否使用颜色
     * @return 状态栏字符串
     */
    fun renderStatusBar(bar: TerminalStatusBar, useAnsi: Boolean = true): String {
        val modeColor = when (bar.agentMode) {
            AgentMode.NONE -> Ansi.FG_GRAY
            AgentMode.SINGLE -> Ansi.FG_BLUE
            AgentMode.MULTI -> Ansi.FG_CYAN
            AgentMode.BURST -> Ansi.FG_PINK + Ansi.BLINK
        }

        return if (useAnsi) {
            buildString {
                append("${Ansi.FG_DIM_GRAY}┌─${Ansi.RESET}")
                append("${Ansi.FG_CYAN}${Ansi.BOLD}${bar.sessionName}${Ansi.RESET}")
                append("${Ansi.FG_DIM_GRAY}:${Ansi.RESET}")
                append("${Ansi.FG_WHITE}${bar.currentDir.takeLast(25)}${Ansi.RESET}")
                append("${Ansi.FG_DIM_GRAY} │ ${Ansi.RESET}")
                append("$modeColor${bar.agentMode.displayName}${Ansi.RESET}")
                if (bar.activeAgentCount > 0) {
                    append("${Ansi.FG_GRAY}(${bar.activeAgentCount})${Ansi.RESET}")
                }
                if (bar.agentMode == AgentMode.BURST) {
                    append("${Ansi.FG_DIM_GRAY} │ ${Ansi.RESET}")
                    append("${Ansi.FG_PINK}BURST:${bar.burstState}${Ansi.RESET}")
                }
                if (bar.cpuUsage >= 0) {
                    append("${Ansi.FG_DIM_GRAY} │ CPU:${Ansi.RESET}")
                    append("${if (bar.cpuUsage > 80) Ansi.FG_RED else Ansi.FG_GREEN}${bar.cpuUsage}%${Ansi.RESET}")
                }
                if (bar.memUsage >= 0) {
                    append("${Ansi.FG_DIM_GRAY} MEM:${Ansi.RESET}")
                    append("${if (bar.memUsage > 80) Ansi.FG_RED else Ansi.FG_GREEN}${bar.memUsage}%${Ansi.RESET}")
                }
                append("${Ansi.FG_DIM_GRAY} ─┐${Ansi.RESET}")
            }
        } else {
            bar.statusText
        }
    }

    /**
     * 渲染欢迎横幅。
     *
     * @param useAnsi 是否使用颜色
     * @return 横幅字符串
     */
    fun renderBanner(useAnsi: Boolean = true): String {
        val banner = listOf(
            "    ╔═══════════════════════════════════════════════╗",
            "    ║         🪌 APEX TERMINAL · 深海极光 🪌          ║",
            "    ╠═══════════════════════════════════════════════╣",
            "    ║  多 Agent · 狂暴模式 · 技能市场 · 模型平台      ║",
            "    ╚═══════════════════════════════════════════════╝"
        )

        return if (useAnsi) {
            banner.joinToString("\n") { line ->
                "${Ansi.FG_CYAN}$line${Ansi.RESET}"
            }
        } else {
            banner.joinToString("\n")
        }
    }

    /**
     * 获取 Agent 角色对应的 ANSI 颜色码。
     */
    private fun getAgentColor(role: String?): String = when (role?.lowercase()) {
        "supervisor", "主管" -> Ansi.FG_SUPERVISOR
        "worker", "执行", "执行者" -> Ansi.FG_WORKER
        "reviewer", "审查", "审查者" -> Ansi.FG_REVIEWER
        "critic", "批评", "批评者" -> Ansi.FG_CRITIC
        "observer", "观察", "观察者" -> Ansi.FG_OBSERVER
        "system", "系统" -> Ansi.FG_SYSTEM_AGENT
        else -> Ansi.FG_CYAN
    }

    /**
     * 字符串重复操作符。
     */
    private operator fun String.times(n: Int): String = this.repeat(n)
}
