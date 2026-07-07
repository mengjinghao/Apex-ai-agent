package com.apex.lib.terminal

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端引擎 — :apk:terminal 的核心领域层。
 *
 * **职责**：
 *   - 会话生命周期管理（创建 / 写入 / 读取 / 调整尺寸 / 关闭）
 *   - 命令历史（按会话隔离）
 *   - 输出缓冲（环形，按会话快照）
 *   - 事件聚合（输出 / 状态变更 / 关闭 / 错误）
 *
 * **依赖**：[PtyGateway] — 由 APK 注入（对接 :ai-terminal 的 C++ PTY）
 *
 * **架构位置**：
 *   ```
 *   :apk:terminal
 *     └─ TerminalServiceFacade（实现 PtyGateway，持有 TerminalEngine）
 *          └─ TerminalEngine（本类）
 *               ├─ TerminalSessionManager（会话注册表）
 *               ├─ CommandHistory × N（每会话一份）
 *               ├─ OutputBuffer × N（每会话一份）
 *               └─ PtyGateway（APK 注入，桥接 ApexTerminal）
 *   ```
 *
 * **线程模型**：
 *   - 内部使用 [CoroutineScope]（SupervisorJob + IO Dispatcher）启动每会话的 reader
 *   - sessionManager / histories / buffers 都是线程安全容器
 *   - 事件流走 SharedFlow（replay=0, extraBufferCapacity=512）
 *
 * **API 风格**：
 *   - 所有会触发外部 I/O 的方法用 `suspend fun ... : BridgeResult<T>` + `bridgeRun { }`
 *   - 纯内存查询方法直接返回值
 *   - 日志统一 `ApexLog.x(ApexSuite.ApkId.TERMINAL, ...)`
 *
 * @param ptyGateway    PTY 网关（由 APK 实现）
 * @param maxSessions   最大并发会话数
 * @param coroutineScope 引擎内部协程作用域（默认 SupervisorJob + IO）
 */
class TerminalEngine(
    private val ptyGateway: PtyGateway,
    private val maxSessions: Int = 16,
    private val coroutineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val sessionManager = TerminalSessionManager(maxSessions)
    private val histories: ConcurrentHashMap<String, CommandHistory> = ConcurrentHashMap()
    private val buffers: ConcurrentHashMap<String, OutputBuffer> = ConcurrentHashMap()
    private val readerJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val sessionOutputs: ConcurrentHashMap<String, MutableSharedFlow<PtyChunk>> = ConcurrentHashMap()

    private val _events: MutableSharedFlow<TerminalEvent> = MutableSharedFlow(extraBufferCapacity = 512)
    /** 全局事件流（所有会话的输出 / 状态 / 关闭 / 错误）。 */
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    // ============================================================
    // 会话生命周期
    // ============================================================

    /**
     * 创建会话。
     *
     * @param type            终端类型
     * @param workingDir      工作目录（null = 用预设）
     * @param cols            列数
     * @param rows            行数
     * @param env             额外环境变量（与预设合并，覆盖同名键）
     * @param initialCommands 额外初始命令（与预设合并，预设在前）
     * @param metadata        APK 附加元数据（如 agentId / burstProfile）
     * @return 会话 ID
     */
    suspend fun createSession(
        type: TerminalType,
        workingDir: String? = null,
        cols: Int = 80,
        rows: Int = 24,
        env: Map<String, String> = emptyMap(),
        initialCommands: List<String> = emptyList(),
        metadata: Map<String, String> = emptyMap()
    ): BridgeResult<String> = bridgeRun {
        val preset = TerminalPresets.forType(type)
        val sessionId = sessionManager.newId(type)
        val finalWorkingDir = workingDir ?: preset.defaultWorkingDir
        val finalEnv = preset.env + env
        val finalCommands = preset.initialCommands + initialCommands

        val session = TerminalSession(
            id = sessionId,
            type = type,
            workingDir = finalWorkingDir,
            createdAt = System.currentTimeMillis(),
            shell = preset.shell,
            env = finalEnv,
            initialCommands = finalCommands,
            bufferLineLimit = preset.bufferLineLimit,
            metadata = metadata,
            rows = rows,
            cols = cols
        )

        if (!sessionManager.register(session)) {
            throw IllegalStateException("max sessions reached ($maxSessions)")
        }
        histories[sessionId] = CommandHistory()
        buffers[sessionId] = OutputBuffer(session.bufferLineLimit)
        sessionOutputs[sessionId] = MutableSharedFlow(extraBufferCapacity = 256)

        // 创建底层 PTY
        val ptyConfig = PtySessionConfig(
            workingDir = finalWorkingDir,
            shell = preset.shell,
            env = finalEnv,
            initialCommands = finalCommands,
            rows = rows,
            cols = cols,
            metadata = mapOf("type" to type.name, "sessionId" to sessionId)
        )
        val ptyId = ptyGateway.createSession(ptyConfig).getOrNull()
        if (ptyId == null) {
            // 回滚：清理已注册的会话
            sessionManager.remove(sessionId)
            histories.remove(sessionId)
            buffers.remove(sessionId)
            sessionOutputs.remove(sessionId)
            throw IllegalStateException("failed to create PTY for session $sessionId")
        }

        // 标记 RUNNING
        sessionManager.update(sessionId) { it.copy(status = SessionStatus.RUNNING) }
        emitEvent(TerminalEvent.StatusChanged(sessionId, SessionStatus.RUNNING))

        // 启动 PTY 输出收集器
        startReader(sessionId)

        ApexLog.i(
            ApexSuite.ApkId.TERMINAL,
            "[Engine] session created: $sessionId (type=$type, dir=$finalWorkingDir, pty=$ptyId)"
        )
        sessionId
    }

    /** 向指定会话写入字节。 */
    suspend fun write(sessionId: String, data: ByteArray): BridgeResult<Unit> = bridgeRun {
        ensureSession(sessionId)
        ptyGateway.write(sessionId, data).getOrNull()
            ?: throw IllegalStateException("failed to write to session $sessionId")
        // 把纯文本命令（以 \n 结尾且无控制字符）记入历史
        recordIfCommand(sessionId, data)
        Unit
    }

    /** 关闭会话（释放 PTY + 取消 reader + 标记 CLOSED）。 */
    suspend fun closeSession(sessionId: String): BridgeResult<Unit> = bridgeRun {
        ensureSession(sessionId)
        runCatching { ptyGateway.close(sessionId) }
        readerJobs.remove(sessionId)?.cancel()
        sessionManager.update(sessionId) {
            it.copy(status = SessionStatus.CLOSED, closedAt = System.currentTimeMillis())
        }
        emitEvent(TerminalEvent.Closed(sessionId, exitCode = null))
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[Engine] session closed: $sessionId")
        Unit
    }

    /** 调整终端尺寸。 */
    suspend fun resize(sessionId: String, rows: Int, cols: Int): BridgeResult<Unit> = bridgeRun {
        ensureSession(sessionId)
        ptyGateway.resize(sessionId, rows, cols).getOrNull()
            ?: throw IllegalStateException("failed to resize session $sessionId")
        sessionManager.update(sessionId) { it.copy(rows = rows, cols = cols) }
        Unit
    }

    // ============================================================
    // 读取 / 流
    // ============================================================

    /**
     * 订阅指定会话的输出流。
     * - 不存在的 sessionId 返回空流（不抛异常）
     * - 多次调用返回同一 SharedFlow，支持多订阅者
     */
    fun readFlow(sessionId: String): Flow<PtyChunk> =
        sessionOutputs[sessionId]?.asSharedFlow() ?: flowOf()

    /** 同步快照：返回该会话当前缓冲的所有行。 */
    fun outputSnapshot(sessionId: String): List<String> =
        buffers[sessionId]?.snapshot() ?: emptyList()

    /** 最近 N 行。 */
    fun lastOutputLines(sessionId: String, n: Int): List<String> =
        buffers[sessionId]?.lastN(n) ?: emptyList()

    /** 缓冲统计：行数 + 字节数。 */
    fun bufferStats(sessionId: String): Pair<Int, Long>? {
        val buf = buffers[sessionId] ?: return null
        return buf.lineCount() to buf.totalBytes()
    }

    // ============================================================
    // 会话查询
    // ============================================================

    fun listSessions(): List<TerminalSession> = sessionManager.list()
    fun getSession(sessionId: String): TerminalSession? = sessionManager.get(sessionId)
    fun listByType(type: TerminalType): List<TerminalSession> = sessionManager.listByType(type)
    fun activeSessions(): List<TerminalSession> = sessionManager.activeSessions()
    fun sessionCount(): Int = sessionManager.count()

    // ============================================================
    // 命令历史
    // ============================================================

    /** 记录一条命令到指定会话的历史。 */
    fun recordCommand(sessionId: String, command: String, exitCode: Int? = null) {
        histories[sessionId]?.append(sessionId, command, exitCode)
    }

    /** 获取指定会话的全部历史。 */
    fun getHistory(sessionId: String): List<CommandHistoryEntry> =
        histories[sessionId]?.all() ?: emptyList()

    /** 全文搜索历史。 */
    fun searchHistory(sessionId: String, query: String): List<CommandHistoryEntry> =
        histories[sessionId]?.search(query) ?: emptyList()

    /** 最近 N 条历史。 */
    fun lastHistory(sessionId: String, n: Int): List<CommandHistoryEntry> =
        histories[sessionId]?.last(n) ?: emptyList()

    /** 上一条（向上翻页）。 */
    fun historyPrevious(sessionId: String): CommandHistoryEntry? =
        histories[sessionId]?.previous()

    /** 下一条（向下翻页）。 */
    fun historyNext(sessionId: String): CommandHistoryEntry? =
        histories[sessionId]?.next()

    /** 重置翻页游标。 */
    fun resetHistoryCursor(sessionId: String) {
        histories[sessionId]?.resetCursor()
    }

    /** 清空指定会话的历史。 */
    fun clearHistory(sessionId: String) {
        histories[sessionId]?.clear()
    }

    // ============================================================
    // 关闭
    // ============================================================

    /**
     * 关闭所有会话并释放资源。
     * 在 APK 的 [TerminalApplication.onTerminate] 中调用。
     */
    fun shutdown() {
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[Engine] shutting down (${sessionManager.count()} sessions)")
        // 同步取消所有 reader
        readerJobs.values.forEach { it.cancel() }
        readerJobs.clear()

        // 同步关闭所有 PTY（不走 suspend，用 runBlocking 会阻塞；这里只标记并清理内存）
        sessionManager.list().forEach { s ->
            runCatching {
                sessionManager.update(s.id) {
                    it.copy(status = SessionStatus.CLOSED, closedAt = System.currentTimeMillis())
                }
            }
        }

        histories.clear()
        buffers.clear()
        sessionOutputs.clear()
        sessionManager.clear()
        coroutineScope.cancel()
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[Engine] shutdown complete")
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private fun ensureSession(sessionId: String) {
        if (!sessionManager.contains(sessionId)) {
            throw IllegalArgumentException("session not found: $sessionId")
        }
    }

    /**
     * 启动 PTY 输出收集器（一个协程 Job）。
     * - 从 [PtyGateway.readFlow] 收集 chunk
     * - 写入 OutputBuffer
     * - 推送到 per-session SharedFlow（[sessionOutputs]）
     * - 推送到全局事件流（[events]）
     * - 流自然结束 = PTY 关闭 → 标记 CLOSED + 发 [TerminalEvent.Closed]
     */
    private fun startReader(sessionId: String) {
        val job = coroutineScope.launch {
            runCatching {
                ptyGateway.readFlow(sessionId).collect { chunk ->
                    buffers[sessionId]?.append(chunk.data)
                    sessionOutputs[sessionId]?.tryEmit(chunk)
                    _events.tryEmit(TerminalEvent.Output(sessionId, chunk))
                }
            }.onFailure { t ->
                ApexLog.e(ApexSuite.ApkId.TERMINAL, "[Engine] reader failed for $sessionId", t)
                emitEvent(
                    TerminalEvent.Error(
                        sessionId,
                        t.message ?: t.javaClass.simpleName,
                        t
                    )
                )
            }

            // 流结束（正常或异常）→ 标记 CLOSED
            if (sessionManager.get(sessionId)?.status != SessionStatus.CLOSED) {
                sessionManager.update(sessionId) {
                    it.copy(status = SessionStatus.CLOSED, closedAt = System.currentTimeMillis())
                }
                emitEvent(TerminalEvent.Closed(sessionId, exitCode = null))
            }
            readerJobs.remove(sessionId)
        }
        readerJobs[sessionId] = job
    }

    private fun emitEvent(event: TerminalEvent) {
        _events.tryEmit(event)
    }

    /**
     * 把以 `\n` 结尾的纯文本输入当作命令记入历史。
     * - 含 ESC（0x1B）= 控制序列（方向键 / Tab 补全等），跳过
     * - 多行输入只取第一行
     */
    private fun recordIfCommand(sessionId: String, data: ByteArray) {
        if (data.isEmpty()) return
        // 含 ESC 控制序列 → 不是普通命令
        if (data.any { it == 0x1B.toByte() }) return
        val text = String(data, Charsets.UTF_8)
        val nlIdx = text.indexOf('\n')
        if (nlIdx <= 0) return  // 没换行或换行在开头 → 不记录
        val cmd = text.substring(0, nlIdx).trim()
        if (cmd.isNotEmpty()) recordCommand(sessionId, cmd)
    }
}
