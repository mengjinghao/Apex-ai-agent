package com.ai.assistance.aiterminal.terminal.multiagent

import com.ai.assistance.aiterminal.terminal.ui.AgentMode
import com.ai.assistance.aiterminal.terminal.ui.AgentMode.BURST
import com.ai.assistance.aiterminal.terminal.ui.AgentMode.MULTI
import com.ai.assistance.aiterminal.terminal.ui.AgentMode.NONE
import com.ai.assistance.aiterminal.terminal.ui.AgentMode.SINGLE
import com.ai.assistance.aiterminal.terminal.ui.MessageSource
import com.ai.assistance.aiterminal.terminal.ui.TerminalInputMode
import com.ai.assistance.aiterminal.terminal.ui.TerminalMessage
import com.ai.assistance.aiterminal.terminal.ui.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端 Agent 角色。
 *
 * 在多 Agent 模式下，不同 Agent 承担不同角色。
 */
enum class TerminalAgentRole(val displayName: String, val icon: String) {
    SUPERVISOR("主管", "👑"),
    WORKER("执行者", "⚡"),
    REVIEWER("审查者", "🔍"),
    CRITIC("批评者", "⚠️"),
    OBSERVER("观察者", "👁️"),
    SYSTEM("系统", "⚙️")
}

/**
 * 终端 Agent 状态。
 */
data class TerminalAgentInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: TerminalAgentRole,
    val active: Boolean = true,
    val messageCount: Int = 0,
    val lastActivity: Long = System.currentTimeMillis()
)

/**
 * 多 Agent 终端适配器。
 *
 * 将多 Agent 协作系统与终端 UI 桥接：
 * - 管理 Agent 注册和状态
 * - 将 Agent 消息路由到终端会话
 * - 支持多 Agent 同时输出（带角色标识）
 * - 支持狂暴模式状态显示
 *
 * # 使用示例
 *
 * ```
 * val adapter = MultiAgentTerminalAdapter(sessionManager)
 *
 * // 注册 Agent
 * adapter.registerAgent("agent-1", "FileAgent", TerminalAgentRole.WORKER)
 * adapter.registerAgent("agent-2", "ReviewAgent", TerminalAgentRole.REVIEWER)
 *
 * // 切换到多 Agent 模式
 * adapter.enableMultiAgent(sessionId)
 *
 * // Agent 发送消息到终端
 * adapter.agentMessage(sessionId, "agent-1", "开始处理文件...")
 * adapter.agentMessage(sessionId, "agent-2", "审查完成，无问题")
 *
 * // 狂暴模式
 * adapter.enableBurstMode(sessionId)
 * adapter.burstStatus(sessionId, "executing", "并行推理中...")
 * ```
 */
class MultiAgentTerminalAdapter(
    private val sessionManager: TerminalSessionManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 注册的 Agent（agentId -> info）。 */
    private val agents = ConcurrentHashMap<String, TerminalAgentInfo>()

    /** 每个会话的 Agent 映射（sessionId -> agentIds）。 */
    private val sessionAgents = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 注册 Agent。
     *
     * @param agentId Agent ID
     * @param name 显示名
     * @param role 角色
     */
    fun registerAgent(agentId: String, name: String, role: TerminalAgentRole) {
        agents[agentId] = TerminalAgentInfo(id = agentId, name = name, role = role)
    }

    /**
     * 注销 Agent。
     */
    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
        for (agentSet in sessionAgents.values) agentSet.remove(agentId)
    }

    /**
     * 将 Agent 绑定到会话。
     */
    fun bindAgentToSession(sessionId: String, agentId: String) {
        sessionAgents.computeIfAbsent(sessionId) { java.util.concurrent.CopyOnWriteArraySet() }.add(agentId)
        updateAgentCount(sessionId)
    }

    /**
     * 解绑 Agent。
     */
    fun unbindAgentFromSession(sessionId: String, agentId: String) {
        sessionAgents[sessionId]?.remove(agentId)
        updateAgentCount(sessionId)
    }

    /**
     * 获取会话绑定的所有 Agent。
     */
    fun getSessionAgents(sessionId: String): List<TerminalAgentInfo> {
        val ids = sessionAgents[sessionId] ?: return emptyList()
        return ids.mapNotNull { agents[it] }
    }

    /**
     * 启用单 Agent 模式。
     */
    fun enableSingleAgent(sessionId: String, agentId: String) {
        sessionManager.setAgentMode(sessionId, SINGLE)
        bindAgentToSession(sessionId, agentId)
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("单 Agent 模式"))
        sessionManager.sendMessage(sessionId, TerminalMessage.agent(
            "Agent ${agents[agentId]?.name ?: agentId} 已就绪",
            agentId, agents[agentId]?.role?.displayName ?: "Agent"
        ))
    }

    /**
     * 启用多 Agent 模式。
     */
    fun enableMultiAgent(sessionId: String) {
        sessionManager.setAgentMode(sessionId, MULTI)
        val bound = getSessionAgents(sessionId)
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("多 Agent 协作"))
        sessionManager.sendMessage(sessionId, TerminalMessage.system(
            "已激活 ${bound.size} 个 Agent: ${bound.joinToString { "${it.role.icon} ${it.name}" }}"
        ))
    }

    /**
     * 启用狂暴模式。
     */
    fun enableBurstMode(sessionId: String) {
        sessionManager.setAgentMode(sessionId, BURST)
        sessionManager.setBurstState(sessionId, "active")
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("🔥 狂暴模式已激活"))
        sessionManager.sendMessage(sessionId, TerminalMessage.burst(
            "狂暴模式启动 — 多策略并行推理 + GA 演化优化"
        ))
    }

    /**
     * 禁用 Agent 模式（回到标准终端）。
     */
    fun disableAgentMode(sessionId: String) {
        sessionManager.setAgentMode(sessionId, NONE)
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("标准终端模式"))
    }

    /**
     * Agent 发送消息到终端。
     *
     * @param sessionId 会话 ID
     * @param agentId Agent ID
     * @param content 消息内容
     * @param isMultiAgent 是否为多 Agent 模式消息（影响显示样式）
     */
    fun agentMessage(
        sessionId: String,
        agentId: String,
        content: String,
        isMultiAgent: Boolean = false
    ) {
        val agent = agents[agentId]
        val role = agent?.role ?: TerminalAgentRole.SYSTEM
        val name = agent?.name ?: agentId

        val message = if (isMultiAgent) {
            TerminalMessage.multiAgent(content, agentId, role.displayName)
        } else {
            TerminalMessage.agent(content, agentId, role.displayName)
        }

        // 添加 Agent 图标和名称前缀
        val prefixedContent = "${role.icon} [$name] $content"
        val finalMessage = message.copy(content = prefixedContent)

        sessionManager.sendMessage(sessionId, finalMessage)

        // 更新 Agent 活动统计
        agents[agentId]?.let { current ->
            agents[agentId] = current.copy(
                messageCount = current.messageCount + 1,
                lastActivity = System.currentTimeMillis()
            )
        }
    }

    /**
     * 狂暴模式状态更新。
     *
     * @param sessionId 会话 ID
     * @param state 状态（idle/thinking/executing/paused/failed/berserk）
     * @param message 可选状态描述
     */
    fun burstStatus(sessionId: String, state: String, message: String? = null) {
        sessionManager.setBurstState(sessionId, state)

        if (message != null) {
            val stateIcon = when (state.lowercase()) {
                "thinking" -> "🧠"
                "executing", "running" -> "⚡"
                "paused" -> "⏸️"
                "failed", "error" -> "❌"
                "berserk", "extreme" -> "🔥"
                "completed" -> "✅"
                else -> "📋"
            }
            sessionManager.sendMessage(sessionId, TerminalMessage.burst("$stateIcon $message"))
        }
    }

    /**
     * 多 Agent 协作开始。
     *
     * 在终端显示协作开始的消息。
     *
     * @param sessionId 会话 ID
     * @param taskDescription 任务描述
     * @param participants 参与 Agent 列表
     */
    fun collaborationStart(
        sessionId: String,
        taskDescription: String,
        participants: List<String>
    ) {
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("🎭 多 Agent 协作"))
        sessionManager.sendMessage(sessionId, TerminalMessage.system("任务: $taskDescription"))
        sessionManager.sendMessage(sessionId, TerminalMessage.info(
            "参与 Agent: ${participants.size} 个 — ${participants.joinToString { agents[it]?.name ?: it }}"
        ))
    }

    /**
     * 多 Agent 协作完成。
     */
    fun collaborationComplete(sessionId: String, success: Boolean, summary: String) {
        val icon = if (success) "✅" else "❌"
        sessionManager.sendMessage(sessionId, TerminalMessage.divider("$icon 协作完成"))
        sessionManager.sendMessage(sessionId, TerminalMessage.system(summary))
    }

    /**
     * Agent 间消息（供 UI 显示 Agent 间通信）。
     *
     * @param sessionId 会话 ID
     * @param fromId 发送方 Agent ID
     * @param toId 接收方 Agent ID
     * @param content 消息内容
     */
    fun agentToAgentMessage(
        sessionId: String,
        fromId: String,
        toId: String,
        content: String
    ) {
        val fromName = agents[fromId]?.name ?: fromId
        val toName = agents[toId]?.name ?: toId
        val fromRole = agents[fromId]?.role ?: TerminalAgentRole.SYSTEM

        sessionManager.sendMessage(sessionId, TerminalMessage.multiAgent(
            "💬 $fromName → $toName: $content",
            fromId, fromRole.displayName
        ))
    }

    /**
     * 获取所有注册的 Agent。
     */
    fun getAllAgents(): List<TerminalAgentInfo> = agents.values.toList()

    /**
     * 获取活跃 Agent 数。
     */
    fun getActiveAgentCount(): Int = agents.values.count { it.active }

    /**
     * 清理所有 Agent。
     */
    fun clearAll() {
        agents.clear()
        sessionAgents.clear()
    }

    private fun updateAgentCount(sessionId: String) {
        val count = sessionAgents[sessionId]?.size ?: 0
        sessionManager.setActiveAgentCount(sessionId, count)
    }
}
