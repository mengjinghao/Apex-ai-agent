package com.apex.lib.terminal

import kotlinx.serialization.Serializable

/**
 * 终端类型（三块结构）。
 *
 * - [NORMAL]       — 普通 Agent 模式使用的终端（标准 shell）
 * - [MULTI_AGENT]  — 多 Agent 模式使用的终端（角色分工 + 黑板）
 * - [RAGE]         — 狂暴模式使用的终端（性能档位 + 持续输出）
 */
@Serializable
enum class TerminalType(val displayName: String, val tag: String) {
    NORMAL("普通模式", "normal"),
    MULTI_AGENT("多 Agent 模式", "multi"),
    RAGE("狂暴模式", "rage")
}

/**
 * 会话状态。
 */
@Serializable
enum class SessionStatus(val displayName: String) {
    IDLE("空闲"),      // 已创建但尚未启动 PTY
    RUNNING("运行中"), // PTY 已启动，正在读写
    CLOSED("已关闭")   // 已关闭，不可再写入
}

/**
 * 终端会话核心数据模型。
 *
 * @property id              会话 ID（由 [TerminalSessionManager.newId] 生成）
 * @property type            终端类型（三块之一）
 * @property workingDir      工作目录（"~" 表示用户主目录）
 * @property createdAt       创建时间戳（毫秒）
 * @property shell           shell 路径（如 /system/bin/sh）
 * @property env             环境变量
 * @property initialCommands 启动后自动执行的命令
 * @property bufferLineLimit 输出缓冲最大行数
 * @property metadata        APK 侧附加元数据（如 agentId / burstProfile）
 * @property status          当前状态
 * @property rows            终端行数
 * @property cols            终端列数
 * @property closedAt        关闭时间戳（null 表示未关闭）
 * @property exitCode        退出码（null 表示未结束或不可获取）
 */
data class TerminalSession(
    val id: String,
    val type: TerminalType,
    val workingDir: String,
    val createdAt: Long,
    val shell: String,
    val env: Map<String, String>,
    val initialCommands: List<String>,
    val bufferLineLimit: Int,
    val metadata: Map<String, String> = emptyMap(),
    val status: SessionStatus = SessionStatus.IDLE,
    val rows: Int = 24,
    val cols: Int = 80,
    val closedAt: Long? = null,
    val exitCode: Int? = null
) {
    /** 是否仍可读写。 */
    val isActive: Boolean get() = status == SessionStatus.RUNNING

    /** 简化展示（日志用）。 */
    fun brief(): String = "TerminalSession(id=$id, type=$type, dir=$workingDir, status=$status)"
}

/**
 * PTY 数据块（终端输出的一小段原始字节）。
 *
 * ANSI 转义序列保留在 [data] 中，由上层渲染器解析。
 */
data class PtyChunk(
    val sessionId: String,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtyChunk) return false
        return sessionId == other.sessionId &&
                timestamp == other.timestamp &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    /** 作为 UTF-8 文本（仅用于日志 / 调试，可能含控制字符）。 */
    fun asText(): String = String(data, Charsets.UTF_8)
}

/**
 * 命令历史条目（按会话隔离）。
 *
 * @property sessionId 归属会话
 * @property command   命令文本（已 trim，非空）
 * @property timestamp 执行时间戳
 * @property exitCode  退出码（null 表示未采集）
 */
data class CommandHistoryEntry(
    val sessionId: String,
    val command: String,
    val timestamp: Long,
    val exitCode: Int? = null
)

/**
 * 终端事件（聚合流，由 [TerminalEngine.events] 暴露）。
 *
 * - [Output]         — 收到 PTY 输出
 * - [StatusChanged]  — 会话状态变更
 * - [Closed]         — 会话已关闭
 * - [Error]          — 会话级错误
 */
sealed class TerminalEvent {
    abstract val sessionId: String

    /** PTY 输出。 */
    data class Output(
        override val sessionId: String,
        val chunk: PtyChunk
    ) : TerminalEvent()

    /** 状态变更。 */
    data class StatusChanged(
        override val sessionId: String,
        val status: SessionStatus
    ) : TerminalEvent()

    /** 会话关闭。 */
    data class Closed(
        override val sessionId: String,
        val exitCode: Int?
    ) : TerminalEvent()

    /** 会话错误。 */
    data class Error(
        override val sessionId: String,
        val message: String,
        val throwable: Throwable? = null
    ) : TerminalEvent()
}
