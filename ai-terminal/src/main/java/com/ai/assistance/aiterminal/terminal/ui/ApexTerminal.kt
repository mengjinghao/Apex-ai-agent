package com.ai.assistance.aiterminal.terminal.ui

import com.ai.assistance.aiterminal.terminal.burst.BurstTerminalIntegration
import com.ai.assistance.aiterminal.terminal.multiagent.MultiAgentTerminalAdapter
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole
import kotlinx.coroutines.flow.StateFlow

/**
 * Apex 终端门面。
 *
 * 统一入口，聚合所有终端子系统：
 * - 会话管理（[sessionManager]）
 * - 多 Agent 适配（[multiAgentAdapter]）
 * - 狂暴模式集成（[burstIntegration]）
 * - 命令处理（[commandProcessor]）
 *
 * # 使用示例
 *
 * ```
 * val terminal = ApexTerminal.create()
 *
 * // 创建会话
 * val session = terminal.createSession()
 *
 * // 处理用户输入
 * terminal.input(session.id, "help")
 *
 * // 注册 Agent
 * terminal.registerAgent("file_agent", "FileAgent", TerminalAgentRole.WORKER)
 *
 * // 切换到多 Agent 模式
 * terminal.switchMode(session.id, AgentMode.MULTI)
 *
 * // 启动狂暴模式
 * terminal.startBurst(session.id, "分析代码")
 *
 * // 观察消息
 * terminal.messages(session.id).collect { messages ->
 *     messages.forEach { renderMessage(it) }
 * }
 * ```
 */
interface ApexTerminal {

    /** 会话管理器。 */
    val sessionManager: TerminalSessionManager

    /** 多 Agent 适配器。 */
    val multiAgentAdapter: MultiAgentTerminalAdapter

    /** 狂暴模式集成。 */
    val burstIntegration: BurstTerminalIntegration

    /** 命令处理器。 */
    val commandProcessor: TerminalCommandProcessor

    /**
     * 创建新会话。
     */
    fun createSession(name: String = "apex-${sessionManager.getAllSessions().size + 1}"): TerminalSessionData

    /**
     * 用户输入。
     *
     * @param sessionId 会话 ID
     * @param input 用户输入文本
     */
    fun input(sessionId: String, input: String)

    /**
     * 注册 Agent。
     */
    fun registerAgent(agentId: String, name: String, role: TerminalAgentRole)

    /**
     * 切换模式。
     */
    fun switchMode(sessionId: String, mode: AgentMode)

    /**
     * 启动狂暴模式任务。
     */
    fun startBurst(sessionId: String, taskDescription: String)

    /**
     * 观察会话消息。
     */
    fun messages(sessionId: String): StateFlow<List<TerminalMessage>>?

    /**
     * 观察状态栏。
     */
    fun statusBar(sessionId: String): StateFlow<TerminalStatusBar>?

    /**
     * 获取所有会话。
     */
    fun getAllSessions(): List<TerminalSessionData>

    /**
     * 关闭终端（清理所有资源）。
     */
    fun shutdown()

    companion object {
        /**
         * 创建 Apex 终端。
         */
        fun create(): ApexTerminal = ApexTerminalImpl()
    }
}

/**
 * [ApexTerminal] 的默认实现。
 */
internal class ApexTerminalImpl : ApexTerminal {

    override val sessionManager = TerminalSessionManager()
    override val multiAgentAdapter = MultiAgentTerminalAdapter(sessionManager)
    override val burstIntegration = BurstTerminalIntegration(sessionManager, multiAgentAdapter)
    override val commandProcessor = TerminalCommandProcessor(
        sessionManager, multiAgentAdapter, burstIntegration
    )

    override fun createSession(name: String): TerminalSessionData {
        return sessionManager.createSession(name)
    }

    override fun input(sessionId: String, input: String) {
        commandProcessor.process(sessionId, input)
    }

    override fun registerAgent(agentId: String, name: String, role: TerminalAgentRole) {
        multiAgentAdapter.registerAgent(agentId, name, role)
    }

    override fun switchMode(sessionId: String, mode: AgentMode) {
        when (mode) {
            AgentMode.NONE -> multiAgentAdapter.disableAgentMode(sessionId)
            AgentMode.SINGLE -> {
                val agents = multiAgentAdapter.getAllAgents()
                if (agents.isNotEmpty()) {
                    multiAgentAdapter.enableSingleAgent(sessionId, agents.first().id)
                }
            }
            AgentMode.MULTI -> multiAgentAdapter.enableMultiAgent(sessionId)
            AgentMode.BURST -> multiAgentAdapter.enableBurstMode(sessionId)
        }
    }

    override fun startBurst(sessionId: String, taskDescription: String) {
        burstIntegration.startTask(sessionId, taskDescription)
    }

    override fun messages(sessionId: String): StateFlow<List<TerminalMessage>>? {
        return sessionManager.messages(sessionId)
    }

    override fun statusBar(sessionId: String): StateFlow<TerminalStatusBar>? {
        return sessionManager.statusBar(sessionId)
    }

    override fun getAllSessions(): List<TerminalSessionData> {
        return sessionManager.getAllSessions()
    }

    override fun shutdown() {
        multiAgentAdapter.clearAll()
        sessionManager.closeAll()
    }
}
