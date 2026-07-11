package com.ai.assistance.aiterminal.terminal.ui

import com.ai.assistance.aiterminal.terminal.burst.BurstTerminalIntegration
import com.ai.assistance.aiterminal.terminal.multiagent.MultiAgentTerminalAdapter
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole

/**
 * 终端命令处理器。
 *
 * 解析用户输入的命令，路由到不同的处理器：
 * - Shell 命令 → 执行并返回输出
 * - Agent 命令 → 转发给 Agent
 * - 狂暴模式命令 → 启动狂暴模式
 * - 内置命令 → help/clear/mode/agents 等
 *
 * # 内置命令
 *
 * | 命令 | 说明 |
 * |------|------|
 * | `help` | 显示帮助 |
 * | `clear` | 清空终端 |
 * | `mode <mode>` | 切换模式（shell/agent/multi/burst）|
 * | `agents` | 列出已注册 Agent |
 * | `burst <task>` | 启动狂暴模式任务 |
 * | `sessions` | 列出所有会话 |
 * | `session <id>` | 切换会话 |
 * | `theme` | 显示当前主题信息 |
 * | `status` | 显示状态栏详情 |
 *
 * # 使用示例
 *
 * ```
 * val processor = TerminalCommandProcessor(sessionManager, multiAgentAdapter, burstIntegration)
 *
 * // 处理用户输入
 * processor.process(sessionId, "help")
 * processor.process(sessionId, "mode burst")
 * processor.process(sessionId, "burst 分析这段代码")
 * ```
 */
class TerminalCommandProcessor(
    private val sessionManager: TerminalSessionManager,
    private val multiAgentAdapter: MultiAgentTerminalAdapter,
    private val burstIntegration: BurstTerminalIntegration
) {

    /**
     * 处理用户输入。
     *
     * @param sessionId 会话 ID
     * @param input 用户输入
     */
    fun process(sessionId: String, input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        // 添加命令到终端
        sessionManager.sendMessage(sessionId, TerminalMessage.command(trimmed))

        // 获取当前模式
        val session = sessionManager.getSession(sessionId)
        val mode = session?.inputMode ?: TerminalInputMode.SHELL

        // 根据模式路由
        when (mode) {
            TerminalInputMode.SHELL -> processShellCommand(sessionId, trimmed)
            TerminalInputMode.AGENT -> processAgentInput(sessionId, trimmed)
            TerminalInputMode.BURST -> processBurstInput(sessionId, trimmed)
        }
    }

    /**
     * 处理 Shell 模式命令。
     */
    private fun processShellCommand(sessionId: String, command: String) {
        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        when (cmd) {
            "help" -> showHelp(sessionId)
            "clear" -> sessionManager.clearMessages(sessionId)
            "mode" -> switchMode(sessionId, args)
            "agents" -> listAgents(sessionId)
            "burst" -> startBurst(sessionId, args)
            "sessions" -> listSessions(sessionId)
            "session" -> switchSession(sessionId, args)
            "theme" -> showThemeInfo(sessionId)
            "status" -> showStatus(sessionId)
            "echo" -> sessionManager.sendMessage(sessionId, TerminalMessage.output(args))
            "about" -> showAbout(sessionId)
            else -> {
                // 未知命令 — 在真实环境中转发给 Shell
                sessionManager.sendMessage(sessionId, TerminalMessage.system(
                    "命令 '$cmd' 将转发给 Shell 执行（需要 TerminalService 绑定）"
                ))
            }
        }
    }

    /**
     * 处理 Agent 模式输入。
     */
    private fun processAgentInput(sessionId: String, input: String) {
        // 在 Agent 模式下，输入作为问题发给 Agent
        val agents = multiAgentAdapter.getSessionAgents(sessionId)
        if (agents.isEmpty()) {
            sessionManager.sendMessage(sessionId, TerminalMessage.warning("没有活跃的 Agent，使用 'mode agent' 注册"))
            return
        }

        sessionManager.sendMessage(sessionId, TerminalMessage.system("📤 发送给 ${agents.size} 个 Agent..."))

        // 模拟 Agent 响应（生产环境应调用真实 Agent）
        for (agent in agents) {
            multiAgentAdapter.agentMessage(
                sessionId, agent.id,
                "收到: \"$input\" — 正在处理...",
                isMultiAgent = agents.size > 1
            )
        }
    }

    /**
     * 处理狂暴模式输入。
     */
    private fun processBurstInput(sessionId: String, input: String) {
        // 在狂暴模式下，输入作为任务描述
        if (input.equals("stop", ignoreCase = true) || input.equals("cancel", ignoreCase = true)) {
            burstIntegration.pauseTask(sessionId)
            return
        }
        if (input.equals("resume", ignoreCase = true)) {
            burstIntegration.resumeTask(sessionId)
            return
        }

        // 启动狂暴模式任务
        burstIntegration.startTask(
            sessionId = sessionId,
            taskDescription = input,
            strategies = listOf("Chain-of-Thought", "Tree-of-Thoughts")
        )
    }

    // ===== 内置命令实现 =====

    private fun showHelp(sessionId: String) {
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("Apex Terminal 帮助"))
        val commands = listOf(
            "help" to "显示此帮助",
            "clear" to "清空终端",
            "mode <shell|agent|multi|burst>" to "切换模式",
            "agents" to "列出已注册 Agent",
            "burst <task>" to "启动狂暴模式任务",
            "sessions" to "列出所有会话",
            "session <id>" to "切换会话",
            "theme" to "显示主题信息",
            "status" to "显示状态详情",
            "about" to "关于 Apex Terminal",
            "echo <text>" to "回显文本"
        )
        for ((cmd, desc) in commands) {
            sessionManager.sendMessage(sessionId, TerminalMessage.output("  $cmd".padEnd(40) + "— $desc"))
        }
        sessionManager.sendMessage(sessionId, TerminalMessage.divider())
    }

    private fun switchMode(sessionId: String, modeArg: String) {
        when (modeArg.lowercase().trim()) {
            "shell", "standard" -> {
                multiAgentAdapter.disableAgentMode(sessionId)
            }
            "agent", "single" -> {
                val agents = multiAgentAdapter.getAllAgents()
                if (agents.isNotEmpty()) {
                    multiAgentAdapter.enableSingleAgent(sessionId, agents.first().id)
                } else {
                    sessionManager.sendMessage(sessionId, TerminalMessage.warning("没有注册的 Agent"))
                }
            }
            "multi" -> {
                multiAgentAdapter.enableMultiAgent(sessionId)
            }
            "burst" -> {
                multiAgentAdapter.enableBurstMode(sessionId)
            }
            else -> {
                sessionManager.sendMessage(sessionId, TerminalMessage.error("未知模式: $modeArg"))
                sessionManager.sendMessage(sessionId, TerminalMessage.info("可用模式: shell / agent / multi / burst"))
            }
        }
    }

    private fun listAgents(sessionId: String) {
        val agents = multiAgentAdapter.getAllAgents()
        if (agents.isEmpty()) {
            sessionManager.sendMessage(sessionId, TerminalMessage.info("没有已注册的 Agent"))
            return
        }

        sessionManager.sendMessage(sessionId, TerminalMessage.divider("已注册 Agent (${agents.size})"))
        for (agent in agents) {
            val status = if (agent.active) "✅" else "❌"
            sessionManager.sendMessage(sessionId, TerminalMessage.output(
                "  $status ${agent.role.icon} ${agent.name.padEnd(20)} [${agent.role.displayName}] 消息: ${agent.messageCount}"
            ))
        }
    }

    private fun startBurst(sessionId: String, taskDescription: String) {
        if (taskDescription.isBlank()) {
            sessionManager.sendMessage(sessionId, TerminalMessage.error("用法: burst <task>"))
            return
        }
        burstIntegration.startTask(sessionId, taskDescription)
    }

    private fun listSessions(sessionId: String) {
        val sessions = sessionManager.getAllSessions()
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("会话列表 (${sessions.size})"))
        for (s in sessions) {
            val active = if (s.id == sessionManager.getActiveSession()?.id) "▶" else " "
            sessionManager.sendMessage(sessionId, TerminalMessage.output(
                "  $active ${s.name.padEnd(20)} [${s.agentMode.displayName}] ${s.currentDir}"
            ))
        }
    }

    private fun switchSession(sessionId: String, targetId: String) {
        if (targetId.isBlank()) {
            sessionManager.sendMessage(sessionId, TerminalMessage.error("用法: session <id>"))
            return
        }
        if (sessionManager.switchSession(targetId)) {
            sessionManager.sendMessage(sessionId, TerminalMessage.success("已切换到会话: $targetId"))
        } else {
            sessionManager.sendMessage(sessionId, TerminalMessage.error("会话不存在: $targetId"))
        }
    }

    private fun showThemeInfo(sessionId: String) {
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("主题信息"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  主题: 深海极光 (Deep Aurora)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  背景: #0A0E1A (深空黑)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  主色: #00E5FF (电光青)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  强调: #FF6B9D (珊瑚粉)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  成功: #4ADE80 (薄荷绿)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  警告: #FBBF24 (琥珀金)"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  错误: #EF4444 (玫瑰红)"))
    }

    private fun showStatus(sessionId: String) {
        val session = sessionManager.getSession(sessionId)
        val bar = sessionManager.statusBar(sessionId)?.value

        sessionManager.sendMessage(sessionId, TerminalMessage.divider("状态详情"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  会话: ${session?.name}"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  目录: ${session?.currentDir}"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  模式: ${session?.agentMode?.displayName}"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  输入: ${session?.inputMode?.displayName}"))
        if (bar != null) {
            sessionManager.sendMessage(sessionId, TerminalMessage.output("  Agent: ${bar.activeAgentCount} 活跃"))
            sessionManager.sendMessage(sessionId, TerminalMessage.output("  狂暴: ${bar.burstState}"))
            if (bar.cpuUsage >= 0) sessionManager.sendMessage(sessionId, TerminalMessage.output("  CPU: ${bar.cpuUsage}%"))
            if (bar.memUsage >= 0) sessionManager.sendMessage(sessionId, TerminalMessage.output("  MEM: ${bar.memUsage}%"))
        }
    }

    private fun showAbout(sessionId: String) {
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("关于 Apex Terminal"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  Apex Terminal v1.0.0"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  深海极光主题 · 多 Agent · 狂暴模式"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  ──────────────────────────────"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  独特配色: 靛蓝底 + 电光青 + 珊瑚粉"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  灵感来源: 深海极光"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  ──────────────────────────────"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  支持: Shell / 单Agent / 多Agent / 狂暴"))
        sessionManager.sendMessage(sessionId, TerminalMessage.output("  主题: ANSI 16色 + Agent角色色 + 狂暴状态色"))
    }
}
