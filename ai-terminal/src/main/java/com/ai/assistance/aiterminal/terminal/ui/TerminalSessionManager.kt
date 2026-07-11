package com.ai.assistance.aiterminal.terminal.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 终端会话。
 *
 * 增强版终端会话，支持多 Agent 和狂暴模式集成。
 * 每个会话维护独立的消息历史、命令历史、Agent 状态。
 *
 * @property id 会话 ID
 * @property name 会话名
 * @property currentDir 当前工作目录
 * @property agentMode Agent 模式
 * @property inputMode 输入模式
 * @property createdAt 创建时间
 */
data class TerminalSessionData(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "apex-session",
    var currentDir: String = "~",
    var agentMode: AgentMode = AgentMode.NONE,
    var inputMode: TerminalInputMode = TerminalInputMode.SHELL,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 终端会话管理器。
 *
 * 管理多个终端会话，支持：
 * - 创建/切换/关闭会话
 * - 消息历史（每会话独立）
 * - 命令历史（上下键导航）
 * - 状态栏信息更新
 * - 多 Agent 和狂暴模式集成
 *
 * # 使用示例
 *
 * ```
 * val manager = TerminalSessionManager()
 *
 * // 创建会话
 * val session = manager.createSession("main")
 *
 * // 添加消息
 * manager.sendMessage(session.id, TerminalMessage.command("ls -la"))
 * manager.sendMessage(session.id, TerminalMessage.output("total 42..."))
 *
 * // 观察消息
 * manager.messages(session.id).collect { messages ->
 *     messages.forEach { println("${it.prefix} ${it.content}") }
 * }
 *
 * // 命令历史导航
 * val prev = manager.previousCommand(session.id)  // 上一条命令
 *
 * // 切换 Agent 模式
 * manager.setAgentMode(session.id, AgentMode.BURST)
 *
 * // 多会话
 * val session2 = manager.createSession("agent-1")
 * manager.switchSession(session2.id)
 * ```
 */
class TerminalSessionManager {

    /** 所有会话。 */
    private val sessions = ConcurrentHashMap<String, TerminalSessionData>()

    /** 每个会话的消息列表。 */
    private val messageStores = ConcurrentHashMap<String, MutableStateFlow<MutableList<TerminalMessage>>>()

    /** 每个会话的命令历史。 */
    private val commandHistories = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    /** 命令历史导航位置（-1 表示在最新位置）。 */
    private val historyPositions = ConcurrentHashMap<String, Int>()

    /** 每个会话的状态栏。 */
    private val statusBars = ConcurrentHashMap<String, MutableStateFlow<TerminalStatusBar>>()

    /** 当前活跃会话 ID。 */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** 最大消息历史数。 */
    private val maxMessages = 10000

    /** 最大命令历史数。 */
    private val maxCommands = 500

    companion object {
        private const val TAG = "TerminalSessionManager"
    }

    /**
     * 创建新会话。
     *
     * @param name 会话名
     * @param dir 初始目录
     * @return 新会话
     */
    fun createSession(name: String = "apex-${sessions.size + 1}", dir: String = "~"): TerminalSessionData {
        val session = TerminalSessionData(name = name, currentDir = dir)
        sessions[session.id] = session
        messageStores[session.id] = MutableStateFlow(mutableListOf())
        commandHistories[session.id] = ConcurrentLinkedQueue()
        historyPositions[session.id] = -1
        statusBars[session.id] = MutableStateFlow(
            TerminalStatusBar(
                currentDir = dir,
                sessionName = name,
                agentMode = AgentMode.NONE
            )
        )

        // 发送欢迎消息
        sendMessage(session.id, TerminalMessage.system("═══ Apex Terminal ═══"))
        sendMessage(session.id, TerminalMessage.info("深海极光主题 · 多 Agent · 狂暴模式"))
        sendMessage(session.id, TerminalMessage.info("输入 'help' 查看可用命令"))
        sendMessage(session.id, TerminalMessage.divider())

        // 如果是第一个会话，设为活跃
        if (_activeSessionId.value == null) {
            _activeSessionId.value = session.id
        }

        return session
    }

    /**
     * 切换活跃会话。
     */
    fun switchSession(sessionId: String): Boolean {
        if (sessionId !in sessions) return false
        _activeSessionId.value = sessionId
        return true
    }

    /**
     * 关闭会话。
     */
    fun closeSession(sessionId: String): Boolean {
        sessions.remove(sessionId) ?: return false
        messageStores.remove(sessionId)
        commandHistories.remove(sessionId)
        historyPositions.remove(sessionId)
        statusBars.remove(sessionId)

        // 如果关闭的是活跃会话，切换到另一个
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = sessions.keys.firstOrNull()
        }
        return true
    }

    /**
     * 获取所有会话。
     */
    fun getAllSessions(): List<TerminalSessionData> = sessions.values.toList()

    /**
     * 获取会话。
     */
    fun getSession(sessionId: String): TerminalSessionData? = sessions[sessionId]

    /**
     * 获取活跃会话。
     */
    fun getActiveSession(): TerminalSessionData? {
        return _activeSessionId.value?.let { sessions[it] }
    }

    /**
     * 观察会话消息。
     */
    fun messages(sessionId: String): StateFlow<List<TerminalMessage>>? {
        val store = messageStores[sessionId] ?: return null
        return store.asStateFlow() as StateFlow<List<TerminalMessage>>
    }

    /**
     * 发送消息到会话。
     */
    fun sendMessage(sessionId: String, message: TerminalMessage) {
        val store = messageStores[sessionId] ?: return
        val current = store.value.toMutableList()
        current.add(message)
        // 保持最大限制
        while (current.size > maxMessages) current.removeAt(0)
        store.value = current

        // 如果是命令，加入命令历史
        if (message.type == TerminalMessageType.COMMAND) {
            val history = commandHistories[sessionId] ?: return
            history.add(message.content)
            while (history.size > maxCommands) {
                // ConcurrentLinkedQueue 没有 removeFirst，用 poll
                history.poll()
            }
            // 重置导航位置
            historyPositions[sessionId] = -1
        }
    }

    /**
     * 批量发送消息。
     */
    fun sendMessages(sessionId: String, messages: List<TerminalMessage>) {
        for (msg in messages) sendMessage(sessionId, msg)
    }

    /**
     * 清空会话消息。
     */
    fun clearMessages(sessionId: String): Boolean {
        val store = messageStores[sessionId] ?: return false
        store.value = mutableListOf()
        return true
    }

    /**
     * 获取命令历史。
     */
    fun getCommandHistory(sessionId: String): List<String> {
        return commandHistories[sessionId]?.toList() ?: emptyList()
    }

    /**
     * 导航到上一条命令（上箭头键）。
     *
     * @return 上一条命令，null 表示没有更多历史
     */
    fun previousCommand(sessionId: String): String? {
        val history = commandHistories[sessionId]?.toList() ?: return null
        if (history.isEmpty()) return null

        val currentPos = historyPositions[sessionId] ?: -1
        val newPos = if (currentPos == -1) history.size - 1 else (currentPos - 1).coerceAtLeast(0)
        historyPositions[sessionId] = newPos
        return history.getOrNull(newPos)
    }

    /**
     * 导航到下一条命令（下箭头键）。
     *
     * @return 下一条命令，null 表示回到最新位置
     */
    fun nextCommand(sessionId: String): String? {
        val history = commandHistories[sessionId]?.toList() ?: return null
        if (history.isEmpty()) return null

        val currentPos = historyPositions[sessionId] ?: -1
        if (currentPos == -1) return null  // 已在最新位置

        val newPos = currentPos + 1
        if (newPos >= history.size) {
            historyPositions[sessionId] = -1
            return null  // 回到最新位置
        }
        historyPositions[sessionId] = newPos
        return history.getOrNull(newPos)
    }

    /**
     * 搜索命令历史。
     *
     * @param query 搜索关键词
     * @return 匹配的命令列表
     */
    fun searchHistory(sessionId: String, query: String): List<String> {
        val history = commandHistories[sessionId]?.toList() ?: return emptyList()
        return history.filter { it.contains(query, ignoreCase = true) }
    }

    /**
     * 设置 Agent 模式。
     */
    fun setAgentMode(sessionId: String, mode: AgentMode) {
        val session = sessions[sessionId] ?: return
        session.agentMode = mode
        session.inputMode = when (mode) {
            AgentMode.NONE -> TerminalInputMode.SHELL
            AgentMode.SINGLE -> TerminalInputMode.AGENT
            AgentMode.MULTI -> TerminalInputMode.AGENT
            AgentMode.BURST -> TerminalInputMode.BURST
        }

        // 更新状态栏
        updateStatusBar(sessionId) { it.copy(agentMode = mode) }

        // 发送模式切换消息
        sendMessage(sessionId, TerminalMessage.system("切换到 ${mode.displayName} 模式"))
    }

    /**
     * 设置狂暴模式状态。
     */
    fun setBurstState(sessionId: String, state: String) {
        updateStatusBar(sessionId) { it.copy(burstState = state) }
    }

    /**
     * 设置活跃 Agent 数。
     */
    fun setActiveAgentCount(sessionId: String, count: Int) {
        updateStatusBar(sessionId) { it.copy(activeAgentCount = count) }
    }

    /**
     * 更新当前目录。
     */
    fun setCurrentDir(sessionId: String, dir: String) {
        sessions[sessionId]?.currentDir = dir
        updateStatusBar(sessionId) { it.copy(currentDir = dir) }
    }

    /**
     * 更新系统资源使用。
     */
    fun setResourceUsage(sessionId: String, cpu: Int, mem: Int) {
        updateStatusBar(sessionId) { it.copy(cpuUsage = cpu, memUsage = mem) }
    }

    /**
     * 观察状态栏。
     */
    fun statusBar(sessionId: String): StateFlow<TerminalStatusBar>? {
        return statusBars[sessionId]?.asStateFlow()
    }

    /**
     * 关闭所有会话。
     */
    fun closeAll() {
        sessions.clear()
        messageStores.clear()
        commandHistories.clear()
        historyPositions.clear()
        statusBars.clear()
        _activeSessionId.value = null
    }

    // ===== 内部方法 =====

    private fun updateStatusBar(sessionId: String, update: (TerminalStatusBar) -> TerminalStatusBar) {
        val bar = statusBars[sessionId] ?: return
        bar.value = update(bar.value)
    }
}
