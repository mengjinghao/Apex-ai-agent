package com.apex.apk.terminal

import android.content.Context
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole
import com.ai.assistance.aiterminal.terminal.ui.AgentMode
import com.ai.assistance.aiterminal.terminal.ui.ApexTerminal
import com.apex.lib.terminal.PtyChunk
import com.apex.lib.terminal.PtyGateway
import com.apex.lib.terminal.PtySessionConfig
import com.apex.lib.terminal.TerminalEngine
import com.apex.lib.terminal.TerminalEvent
import com.apex.lib.terminal.TerminalType
import com.apex.sdk.bridge.LocalStreamServer
import com.apex.sdk.bridge.StreamChannelRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminal APK 的核心服务实现。
 *
 * **架构（lib:terminal 引入后）**：
 *   ```
 *   TerminalServiceFacade（本类）
 *     ├─ 实现 PtyGateway（桥接 :ai-terminal 的 ApexTerminal）
 *     └─ 持有 TerminalEngine（来自 :lib:terminal）
 *          ├─ TerminalSessionManager（会话注册表）
 *          ├─ CommandHistory × N
 *          ├─ OutputBuffer × N
 *          └─ PtyGateway = this（回调到本类的 createSession/write/...）
 *   ```
 *
 * **职责切分**：
 *   - lib:terminal — 纯领域层（会话模型 / 历史 / 缓冲 / 事件聚合）
 *   - 本类（APK）— Android 平台层（LocalSocket 通道 / SuiteEventBus / ApexTerminal 桥接）
 *
 * **三块结构**（保持原 API 不变）：
 *   - [createNormalSession]    → [TerminalType.NORMAL]
 *   - [createMultiAgentSession] → [TerminalType.MULTI_AGENT]
 *   - [createBurstSession]     → [TerminalType.RAGE]（对外仍叫 BURST / burst profile）
 *
 * **底层依赖**：`:ai-terminal` 模块的 [ApexTerminal] 门面
 * （含 JNI PTY + SessionManager + MultiAgentAdapter + BurstIntegration）
 *
 * **流式输出**：
 *   终端 PTY 输出走 [LocalSocket][com.apex.sdk.bridge.LocalStreamClient]，
 *   避免 Binder 1MB 事务限制，适合高频字符流。
 */
class TerminalServiceFacade(private val context: Context) : PtyGateway {

    private val TAG_SUB = "TerminalFacade"

    private var apexTerminal: ApexTerminal? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** sessionId → 终端会话元数据（APK 侧附加信息，用于 LocalSocket 命名等）。 */
    private val sessions = mutableMapOf<String, TerminalSessionInfo>()

    /** sessionId → LocalStreamServer（流通道）。 */
    private val streamServers = mutableMapOf<String, LocalStreamServer>()

    // ===== PtyGateway 实现所需的内部状态 =====

    /** sessionId → 输出 SharedFlow（推送给 lib:terminal 的 PtyGateway.readFlow）。 */
    private val ptyOutputs: ConcurrentHashMap<String, MutableSharedFlow<PtyChunk>> = ConcurrentHashMap()

    /** sessionId → 已发送到 lib 的消息下标（增量推送，避免重复）。 */
    private val ptyLastSeen: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** sessionId → 消息收集协程 Job（ApexTerminal.messages StateFlow → ptyOutputs）。 */
    private val ptyCollectorJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /** PtyGateway 内部协程作用域（消息收集）。 */
    private val ptyScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 终端引擎（懒加载）。
     *
     * 引擎在首次访问时构造，把本类作为 [PtyGateway] 注入。
     * 因此本类必须先 [initialize] 完成 ApexTerminal 创建，再调用任何 engine 方法。
     */
    private val engine: TerminalEngine by lazy {
        TerminalEngine(ptyGateway = this)
    }

    /** 对外暴露引擎事件流（输出 / 状态 / 关闭 / 错误）。 */
    val events: SharedFlow<TerminalEvent> get() = engine.events

    /** 直接访问引擎（高级调用方使用，如查询历史 / 缓冲）。 */
    fun engine(): TerminalEngine = engine

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
    // PtyGateway 实现（被 TerminalEngine 回调）
    // ============================================================

    override suspend fun createSession(config: PtySessionConfig): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")

        // 按 type 选择 AgentMode（lib 的 RAGE 对应 ai-terminal 的 BURST）
        val typeStr = config.metadata["type"] ?: TerminalType.NORMAL.name
        val agentMode = when (typeStr) {
            TerminalType.MULTI_AGENT.name -> AgentMode.MULTI
            TerminalType.RAGE.name -> AgentMode.BURST
            else -> AgentMode.SINGLE
        }

        val session = terminal.createSession(name = "apex-${System.currentTimeMillis()}")
        terminal.switchMode(session.id, agentMode)

        // 多 Agent 模式：自动注册关联 Agent
        config.metadata["agentId"]?.let { agentId ->
            terminal.registerAgent(
                agentId,
                agentId.substringAfterLast('.'),
                TerminalAgentRole.WORKER
            )
        }

        // 建立 PTY 输出 SharedFlow + 启动消息收集器
        ptyOutputs[session.id] = MutableSharedFlow(extraBufferCapacity = 256)
        ptyLastSeen[session.id] = 0
        startMessageCollector(session.id)

        ApexLog.d(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] PtyGateway.createSession: ${session.id} (type=$typeStr, mode=$agentMode)"
        )
        session.id
    }

    override suspend fun write(sessionId: String, data: ByteArray): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        terminal.input(sessionId, String(data, Charsets.UTF_8))
    }

    override fun readFlow(sessionId: String): Flow<PtyChunk> =
        ptyOutputs[sessionId]?.asSharedFlow() ?: flowOf()

    override suspend fun resize(sessionId: String, rows: Int, cols: Int): BridgeResult<Unit> = bridgeRun {
        // :ai-terminal 当前未暴露 resize API，先记录日志，保持接口契约
        ApexLog.d(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] PtyGateway.resize stubbed: $sessionId -> ${rows}x${cols}"
        )
    }

    override suspend fun close(sessionId: String): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: return@bridgeRun
        terminal.sessionManager.closeSession(sessionId)
        ptyCollectorJobs.remove(sessionId)?.cancel()
        ptyOutputs.remove(sessionId)
        ptyLastSeen.remove(sessionId)
        Unit
    }

    override fun isAlive(sessionId: String): Boolean {
        val terminal = apexTerminal ?: return false
        return terminal.sessionManager.getSession(sessionId) != null
    }

    /**
     * 启动消息收集器：订阅 ApexTerminal.messages StateFlow，
     * 把新增消息（增量）转成 [PtyChunk] 推送到 [ptyOutputs]。
     */
    private fun startMessageCollector(sessionId: String) {
        val terminal = apexTerminal ?: return
        val messagesFlow = terminal.messages(sessionId) ?: return
        val job = ptyScope.launch {
            messagesFlow.collect { messages ->
                val lastSeen = ptyLastSeen[sessionId] ?: 0
                if (messages.size <= lastSeen) return@collect
                val newMessages = messages.subList(lastSeen, messages.size)
                ptyLastSeen[sessionId] = messages.size
                val output = ptyOutputs[sessionId] ?: return@collect
                for (msg in newMessages) {
                    val text = "${msg.prefix} ${msg.content}\n"
                    val chunk = PtyChunk(
                        sessionId = sessionId,
                        data = text.toByteArray(Charsets.UTF_8)
                    )
                    output.tryEmit(chunk)
                }
            }
        }
        ptyCollectorJobs[sessionId] = job
    }

    // ============================================================
    // 三块结构：创建会话（委托给 engine）
    // ============================================================

    /**
     * **普通 Agent 模式**：创建一个标准终端会话。
     *
     * @param workingDir 工作目录（可选）
     * @return sessionId
     */
    suspend fun createNormalSession(workingDir: String = "~"): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val sessionId = engine.createSession(
            type = TerminalType.NORMAL,
            workingDir = workingDir,
            metadata = mapOf("kind" to "normal")
        ).getOrNull() ?: throw IllegalStateException("failed to create normal session")

        sessions[sessionId] = TerminalSessionInfo(
            sessionId = sessionId,
            mode = TerminalKind.NORMAL,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = null,
            burstProfile = null
        )
        openStreamChannel(sessionId, "terminal.normal")
        publishSessionCreated(sessionId, "normal", agentId = null, burstProfile = null)

        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] normal session created: $sessionId (dir=$workingDir)"
        )
        sessionId
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
        val sessionId = engine.createSession(
            type = TerminalType.MULTI_AGENT,
            workingDir = workingDir,
            metadata = mapOf("kind" to "multi_agent", "agentId" to agentId)
        ).getOrNull() ?: throw IllegalStateException("failed to create multi-agent session")

        sessions[sessionId] = TerminalSessionInfo(
            sessionId = sessionId,
            mode = TerminalKind.MULTI_AGENT,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = agentId,
            burstProfile = null
        )
        openStreamChannel(sessionId, "terminal.multi")
        publishSessionCreated(sessionId, "multi_agent", agentId = agentId, burstProfile = null)

        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] multi-agent session created: $sessionId (agent=$agentId)"
        )
        sessionId
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
        val sessionId = engine.createSession(
            type = TerminalType.RAGE,
            workingDir = workingDir,
            metadata = mapOf("kind" to "burst", "burstProfile" to burstProfile)
        ).getOrNull() ?: throw IllegalStateException("failed to create burst session")

        sessions[sessionId] = TerminalSessionInfo(
            sessionId = sessionId,
            mode = TerminalKind.BURST,
            workingDir = workingDir,
            createdAt = System.currentTimeMillis(),
            agentId = null,
            burstProfile = burstProfile
        )
        openStreamChannel(sessionId, "terminal.burst")
        publishSessionCreated(sessionId, "burst", agentId = null, burstProfile = burstProfile)

        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] burst session created: $sessionId (profile=$burstProfile)"
        )
        sessionId
    }

    // ============================================================
    // 输入输出（委托给 engine）
    // ============================================================

    /** 向指定会话写入输入。 */
    suspend fun writeInput(sessionId: String, data: ByteArray): BridgeResult<Unit> =
        engine.write(sessionId, data)

    /**
     * 读取指定会话的最新输出快照（同步小数据量）。
     * 大数据量请走 LocalSocket 流通道（[getStreamChannel]）。
     */
    suspend fun readOutput(sessionId: String): BridgeResult<ByteArray> = bridgeRun {
        val lines = engine.outputSnapshot(sessionId)
        lines.joinToString("\n").toByteArray(Charsets.UTF_8)
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
     * 销毁会话（委托给 engine.closeSession + 清理 APK 侧资源）。
     */
    suspend fun destroySession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        engine.closeSession(sessionId).getOrNull()
            ?: throw IllegalStateException("failed to close session $sessionId")
        sessions.remove(sessionId)
        streamServers.remove(sessionId)?.let { server ->
            StreamChannelRegistry.close(server.channelName)
        }
        publishSessionDestroyed(sessionId)
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[$TAG_SUB] session destroyed: $sessionId")
        true
    }

    /**
     * 列出所有活跃会话（APK 侧视图，含 mode/agentId/burstProfile）。
     */
    fun listSessions(): List<TerminalSessionInfo> = sessions.values.toList()

    /**
     * 切换会话模式（在不销毁会话的情况下切换）。
     *
     * **注意**：本方法绕过 engine，直接调用 ApexTerminal.switchMode，
     * 因为 lib 的 [TerminalType] 在创建后不可变。
     * 仅更新 APK 侧 [TerminalSessionInfo.mode]；engine 中的 session.type 不变。
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
        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] mode switched: $sessionId -> $mode (engine.type unchanged)"
        )
    }

    /**
     * 启动狂暴任务（在指定 burst 会话中）。
     */
    suspend fun startBurstTask(sessionId: String, taskDescription: String): BridgeResult<Unit> = bridgeRun {
        val terminal = apexTerminal ?: throw IllegalStateException("ApexTerminal not initialized")
        terminal.startBurst(sessionId, taskDescription)
        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[$TAG_SUB] burst task started: $sessionId, task=${taskDescription.take(80)}"
        )
    }

    /**
     * 关闭所有会话并释放资源。
     */
    fun shutdown() {
        engine.shutdown()
        apexTerminal?.shutdown()
        apexTerminal = null
        sessions.clear()
        streamServers.values.forEach { it.close() }
        streamServers.clear()
        ptyCollectorJobs.values.forEach { it.cancel() }
        ptyCollectorJobs.clear()
        ptyOutputs.clear()
        ptyLastSeen.clear()
        _isInitialized.value = false
    }

    // ============================================================
    // 历史与缓冲（直接转发到 engine，便于 BridgeImpl 暴露）
    // ============================================================

    /** 获取会话的命令历史。 */
    fun getHistory(sessionId: String) = engine.getHistory(sessionId)

    /** 全文搜索历史。 */
    fun searchHistory(sessionId: String, query: String) = engine.searchHistory(sessionId, query)

    /** 输出快照（行列表）。 */
    fun outputSnapshot(sessionId: String) = engine.outputSnapshot(sessionId)

    // ============================================================
    // 内部辅助
    // ============================================================

    private fun ensureInitialized() {
        if (!_isInitialized.value) initialize()
    }

    /** 打开 LocalSocket 流通道并登记。 */
    private fun openStreamChannel(sessionId: String, prefix: String) {
        val streamName = "$prefix.$sessionId"
        val server = StreamChannelRegistry.open(streamName)
        streamServers[sessionId] = server
    }

    /** 发布会话创建事件到 SuiteEventBus。 */
    private fun publishSessionCreated(
        sessionId: String,
        kind: String,
        agentId: String?,
        burstProfile: String?
    ) {
        val payload = mutableMapOf("sessionId" to sessionId, "kind" to kind)
        agentId?.let { payload["agentId"] = it }
        burstProfile?.let { payload["profile"] = it }
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_CREATED,
            payload,
            ApexSuite.ApkId.TERMINAL
        )
    }

    /** 发布会话销毁事件到 SuiteEventBus。 */
    private fun publishSessionDestroyed(sessionId: String) {
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.TERMINAL_SESSION_DESTROYED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.TERMINAL
        )
    }
}

/** 终端类型（三块结构，APK 侧枚举，对应 lib 的 [TerminalType]）。 */
enum class TerminalKind {
    NORMAL,        // 普通 Agent 模式   ← TerminalType.NORMAL
    MULTI_AGENT,   // 多 Agent 模式     ← TerminalType.MULTI_AGENT
    BURST          // 狂暴模式           ← TerminalType.RAGE
}

/** 终端会话信息（APK 侧视图，含 LocalSocket 命名 / Agent 关联 / Burst 档位）。 */
data class TerminalSessionInfo(
    val sessionId: String,
    var mode: TerminalKind,
    val workingDir: String,
    val createdAt: Long,
    val agentId: String?,
    val burstProfile: String?
)
