package com.apex.apk.terminal

import android.content.Context
import com.ai.assistance.aiterminal.terminal.ui.AgentMode
import com.ai.assistance.aiterminal.terminal.ui.ApexTerminal
import com.ai.assistance.aiterminal.terminal.ui.TerminalSessionData
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole
import com.apex.sdk.bridge.LocalStreamServer
import com.apex.sdk.bridge.StreamChannelRegistry
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Terminal APK 的核心服务实现。
 *
 * **三块结构**（按用户要求）：
 *   - [createNormalSession]    → 普通 Agent 模式使用的终端
 *   - [createMultiAgentSession] → 多 Agent 模式使用的终端
 *   - [createBurstSession]     → 狂暴模式使用的终端
 *
 * **底层依赖**：`:ai-terminal` 模块的 [ApexTerminal] 门面
 * （含 JNI PTY + SessionManager + MultiAgentAdapter + BurstIntegration）
 *
 * **流式输出**：
 *   终端 PTY 输出走 [LocalSocket][com.apex.sdk.bridge.LocalStreamClient]，
 *   避免 Binder 1MB 事务限制，适合高频字符流。
 */
class TerminalServiceFacade(private val context: Context) {

    private const val TAG_SUB = "TerminalFacade"

    private var apexTerminal: ApexTerminal? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** sessionId → 终端会话元数据。 */
    private val sessions = mutableMapOf<String, TerminalSessionInfo>()

    /** sessionId → LocalStreamServer（流通道）。 */
    private val streamServers = mutableMapOf<String, LocalStreamServer>()

    /**
     * 初始化终端核心。
     */
    fun initialize(): BridgeResult<Unit> = bridgeRun {
        if (_isInitialized.value) return@bridgeRun
        apexTerminal = ApexTerminal.create()
        _isInitialized.value = true
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] ApexTerminal initialized")
    }

    // ============================================================
    // 三块结构：创建会话
    // ============================================================

    /**
     * **普通 Agent 模式**：创建一个标准终端会话。
     *
     * @param workingDir 工作目录（可选）
     * @return sessionId
     */
    suspend fun createNormalSession(workingDir: String = "~"): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val session = terminal.createSession(name = "normal-${System.currentTimeMillis()}")
        terminal.switchMode(session.id, AgentMode.SINGLE)

        val info = TerminalSessionInfo(
            sessionId = session.id,
            mode = TerminalKind.NORMAL,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = null,
            burstProfile = null
        )
        sessions[session.id] = info

        // 创建 LocalSocket 流通道
        val streamName = "terminal.normal.${session.id}"
        val server = StreamChannelRegistry.open(streamName)
        streamServers[session.id] = server

        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] normal session created: ${session.id} (dir=$workingDir)")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_CREATED,
            mapOf("sessionId" to session.id, "kind" to "normal"),
            ApexSuite.ApkId.TERMINAL
        )

        session.id
    }

    /**
     * **多 Agent 模式**：创建一个多 Agent 终端会话。
     *
     * @param workingDir 工作目录
     * @param agentId 关联的 Agent ID
     * @return sessionId
     */
    suspend fun createMultiAgentSession(
        workingDir: String = "~",
        agentId: String = "builtin.supervisor"
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val session = terminal.createSession(name = "multi-${System.currentTimeMillis()}")
        terminal.switchMode(session.id, AgentMode.MULTI)

        // 注册关联的 Agent
        terminal.registerAgent(agentId, agentId.substringAfterLast('.'), TerminalAgentRole.WORKER)

        val info = TerminalSessionInfo(
            sessionId = session.id,
            mode = TerminalKind.MULTI_AGENT,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = agentId,
            burstProfile = null
        )
        sessions[session.id] = info

        val streamName = "terminal.multi.${session.id}"
        val server = StreamChannelRegistry.open(streamName)
        streamServers[session.id] = server

        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] multi-agent session created: ${session.id} (agent=$agentId)")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_CREATED,
            mapOf("sessionId" to session.id, "kind" to "multi_agent", "agentId" to agentId),
            ApexSuite.ApkId.TERMINAL
        )

        session.id
    }

    /**
     * **狂暴模式**：创建一个狂暴模式终端会话。
     *
     * @param workingDir 工作目录
     * @param burstProfile 狂暴模式预设（PERFORMANCE / BALANCED / POWER_SAVER / 等）
     * @return sessionId
     */
    suspend fun createBurstSession(
        workingDir: String = "~",
        burstProfile: String = "BALANCED"
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val session = terminal.createSession(name = "burst-${System.currentTimeMillis()}")
        terminal.switchMode(session.id, AgentMode.BURST)

        val info = TerminalSessionInfo(
            sessionId = session.id,
            mode = TerminalKind.BURST,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = null,
            burstProfile = burstProfile
        )
        sessions[session.id] = info

        val streamName = "terminal.burst.${session.id}"
        val server = StreamChannelRegistry.open(streamName)
        streamServers[session.id] = server

        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] burst session created: ${session.id} (profile=$burstProfile)")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_CREATED,
            mapOf("sessionId" to session.id, "kind" to "burst", "profile" to burstProfile),
            ApexSuite.ApkId.TERMINAL
        )

        session.id
    }

    // ============================================================
    // 输入输出
    // ============================================================

    /**
     * 向指定会话写入输入。
     */
    suspend fun writeInput(sessionId: String, data: ByteArray): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val text = String(data, Charsets.UTF_8)
        terminal.input(sessionId, text)
    }

    /**
     * 读取指定会话的最新输出（同步小数据量）。
     * 大数据量请走 LocalSocket 流通道。
     */
    suspend fun readOutput(sessionId: String): BridgeResult<ByteArray> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val messages = terminal.messages(sessionId)?.value ?: emptyList()
        val text = messages.joinToString("\n") { it.content }
        text.toByteArray(Charsets.UTF_8)
    }

    /**
     * 获取某会话的 LocalSocket 通道名（用于流式传输）。
     */
    fun getStreamChannel(sessionId: String): String? {
        val info = sessions[sessionId] ?: return null
        return when (info.mode) {
            TerminalKind.NORMAL -> "terminal.normal.$sessionId"
            TerminalKind.MULTI_AGENT -> "terminal.multi.$sessionId"
            TerminalKind.BURST -> "terminal.burst.$sessionId"
        }
    }

    /**
     * 销毁会话。
     */
    suspend fun destroySession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        terminal.sessionManager.closeSession(sessionId)
        sessions.remove(sessionId)
        streamServers.remove(sessionId)?.let { server ->
            StreamChannelRegistry.close(server.channelName)
        }
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] session destroyed: $sessionId")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_DESTROYED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.TERMINAL
        )
        true
    }

    /**
     * 列出所有活跃会话。
     */
    fun listSessions(): List<TerminalSessionInfo> = sessions.values.toList()

    /**
     * 切换会话模式（在不销毁会话的情况下切换）。
     */
    suspend fun switchMode(sessionId: String, mode: TerminalKind): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        val agentMode = when (mode) {
            TerminalKind.NORMAL -> AgentMode.SINGLE
            TerminalKind.MULTI_AGENT -> AgentMode.MULTI
            TerminalKind.BURST -> AgentMode.BURST
        }
        terminal.switchMode(sessionId, agentMode)
        sessions[sessionId]?.mode = mode
    }

    /**
     * 启动狂暴任务（在指定 burst 会话中）。
     */
    suspend fun startBurstTask(sessionId: String, taskDescription: String): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        terminal.startBurst(sessionId, taskDescription)
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] burst task started: $sessionId, task=${taskDescription.take(80)}")
    }

    /**
     * 关闭所有会话并释放资源。
     */
    fun shutdown() {
        apexTerminal?.shutdown()
        apexTerminal = null
        sessions.clear()
        streamServers.values.forEach { it.close() }
        streamServers.clear()
        _isInitialized.value = false
    }

    private fun ensureInitialized() {
        if (!_isInitialized.value) initialize()
    }
}

/** 终端类型（三块结构）。 */
enum class TerminalKind {
    NORMAL,        // 普通 Agent 模式
    MULTI_AGENT,   // 多 Agent 模式
    BURST          // 狂暴模式
}

/** 终端会话信息。 */
data class TerminalSessionInfo(
    val sessionId: String,
    var mode: TerminalKind,
    val workingDir: String,
    val createdAt: Long,
    val agentId: String?,
    val burstProfile: String?
)
