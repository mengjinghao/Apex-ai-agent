package com.apex.lib.terminal

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.Trace
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端会话注册表。
 *
 * **能力**：
 *   - CRUD（register / get / update / remove / list）
 *   - 按 id 查询（[get]）
 *   - 按类型查询（[listByType]）
 *   - 活跃会话追踪（[activeSessions]）
 *   - 最大会话数限制（[maxSessions]）
 *   - 新会话 ID 生成（[newId]）
 *
 * **线程安全**：
 *   - 底层用 [ConcurrentHashMap] 保存
 *   - 涉及"读-改-写"的操作（register / update / remove）用 `@Synchronized` 串行化
 *
 * @param maxSessions 最大并发会话数（默认 16）
 */
class TerminalSessionManager(
    private val maxSessions: Int = 16
) {

    private val sessions: ConcurrentHashMap<String, TerminalSession> = ConcurrentHashMap()

    /**
     * 注册新会话。
     * @return true=注册成功；false=已达 [maxSessions] 上限
     */
    @Synchronized
    fun register(session: TerminalSession): Boolean {
        if (sessions.size >= maxSessions && !sessions.containsKey(session.id)) {
            ApexLog.w(
                ApexSuite.ApkId.TERMINAL,
                "[SessionManager] max sessions reached ($maxSessions), reject ${session.id}"
            )
            return false
        }
        sessions[session.id] = session
        ApexLog.d(ApexSuite.ApkId.TERMINAL, "[SessionManager] registered: ${session.brief()}")
        return true
    }

    /** 按 id 查询。 */
    fun get(sessionId: String): TerminalSession? = sessions[sessionId]

    /** 是否包含某会话。 */
    fun contains(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /**
     * 原子更新某会话。
     * @return 更新后的会话；null = 会话不存在
     */
    @Synchronized
    fun update(sessionId: String, transform: (TerminalSession) -> TerminalSession): TerminalSession? {
        val current = sessions[sessionId] ?: return null
        val updated = transform(current)
        sessions[sessionId] = updated
        return updated
    }

    /** 移除某会话。返回被移除的会话（null = 不存在）。 */
    @Synchronized
    fun remove(sessionId: String): TerminalSession? = sessions.remove(sessionId)

    /** 全部会话（快照副本）。 */
    fun list(): List<TerminalSession> = sessions.values.toList()

    /** 按类型筛选。 */
    fun listByType(type: TerminalType): List<TerminalSession> =
        sessions.values.filter { it.type == type }

    /** 仅活跃会话（status != CLOSED）。 */
    fun activeSessions(): List<TerminalSession> =
        sessions.values.filter { it.status != SessionStatus.CLOSED }

    /** 当前会话总数。 */
    fun count(): Int = sessions.size

    /** 按类型计数。 */
    fun countByType(type: TerminalType): Int =
        sessions.values.count { it.type == type }

    /** 清空所有会话（不关闭底层 PTY，仅清注册表）。 */
    @Synchronized
    fun clear() {
        sessions.clear()
    }

    /**
     * 生成新会话 ID。
     * 格式：`<prefix>-<traceId>`，traceId 由 [Trace.newId] 生成。
     */
    fun newId(type: TerminalType): String {
        val prefix = when (type) {
            TerminalType.NORMAL -> "term-normal"
            TerminalType.MULTI_AGENT -> "term-multi"
            TerminalType.RAGE -> "term-rage"
        }
        return Trace.newId(prefix)
    }
}
