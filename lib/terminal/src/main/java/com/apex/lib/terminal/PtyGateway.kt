package com.apex.lib.terminal

import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.flow.Flow

/**
 * PTY 创建请求参数。
 *
 * 由 [TerminalEngine] 在 [TerminalEngine.createSession] 中根据
 * [TerminalPresets] + 调用方覆盖项构造，传给 [PtyGateway.createSession]。
 *
 * @property workingDir      工作目录
 * @property shell           shell 路径
 * @property env             环境变量
 * @property initialCommands 启动后自动执行的命令（已按顺序合并）
 * @property rows            初始行数
 * @property cols            初始列数
 * @property metadata        透传元数据（如终端类型）
 */
data class PtySessionConfig(
    val workingDir: String,
    val shell: String,
    val env: Map<String, String>,
    val initialCommands: List<String> = emptyList(),
    val rows: Int = 24,
    val cols: Int = 80,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * PTY 网关契约 — 由 APK 实现。
 *
 * **设计动机**：
 *   - lib:terminal 是纯 Kotlin 领域层，不直接依赖 :ai-terminal 的 C++ JNI
 *   - 通过本接口抽象出 PTY 的 5 项核心能力，便于：
 *     1. 单元测试用 FakePtyGateway 替换
 *     2. 未来切换为 LocalSocket / Termux / 其他 PTY 后端
 *   - APK 的 [TerminalServiceFacade] 实现本接口，桥接到 :ai-terminal 的 [ApexTerminal]
 *
 * **线程模型**：
 *   - [createSession] / [write] / [resize] / [close] 都是 suspend，APK 可在内部
 *     切换到合适的 Dispatcher
 *   - [readFlow] 必须是 hot 流（推荐 SharedFlow），支持多个收集者同时订阅
 *     同一会话的输出；engine 内部会订阅一次用于缓冲 + 事件聚合
 *
 * **生命周期**：
 *   - 每次 [createSession] 成功返回的 ID 必须能被 [close] 关闭
 *   - 会话关闭后，[readFlow] 应自然 complete（流结束）
 *   - [isAlive] 用于心跳检测（非阻塞）
 */
interface PtyGateway {

    /**
     * 创建底层 PTY 会话。
     *
     * @return 成功返回 ptyId（通常与上层 sessionId 一致）
     */
    suspend fun createSession(config: PtySessionConfig): BridgeResult<String>

    /**
     * 向 PTY 写入字节（用户输入 / 命令 / 控制序列）。
     */
    suspend fun write(sessionId: String, data: ByteArray): BridgeResult<Unit>

    /**
     * 订阅 PTY 输出流（含 ANSI 序列）。
     *
     * 实现应保证：
     *   1. 多次调用返回相同的热流（或可被多次收集的 SharedFlow）
     *   2. 会话关闭后流自动 complete
     *   3. 不存在的 sessionId 返回空流（不抛异常）
     */
    fun readFlow(sessionId: String): Flow<PtyChunk>

    /**
     * 调整 PTY 尺寸（行 / 列）。
     */
    suspend fun resize(sessionId: String, rows: Int, cols: Int): BridgeResult<Unit>

    /**
     * 关闭 PTY 会话并释放底层资源（FD / 进程 / 缓冲）。
     */
    suspend fun close(sessionId: String): BridgeResult<Unit>

    /**
     * 非阻塞查询某 PTY 是否仍存活。
     */
    fun isAlive(sessionId: String): Boolean
}
