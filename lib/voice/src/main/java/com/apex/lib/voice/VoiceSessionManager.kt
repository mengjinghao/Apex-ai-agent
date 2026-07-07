package com.apex.lib.voice

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.Trace
import java.util.concurrent.ConcurrentHashMap

/**
 * 语音会话注册表（CRUD + 活跃追踪）。
 *
 * - 线程安全（基于 [ConcurrentHashMap]）
 * - 维护 [activeSessions] 快照，便于 UI / 监控查询
 * - 自动记录 `lastActiveAt`，配合 [gc] 回收僵尸会话
 *
 * 由 [VoiceEngine] 持有，业务侧不直接 new。
 */
class VoiceSessionManager {

    private val sessions = ConcurrentHashMap<String, VoiceSession>()

    /** 注册一个新会话。 */
    fun register(mode: VoiceMode, config: VoiceConfig): VoiceSession {
        val id = Trace.newId("voice")
        val session = VoiceSession(
            id = id,
            mode = mode,
            config = config,
            createdAt = System.currentTimeMillis(),
            active = true,
            lastActiveAt = System.currentTimeMillis()
        )
        sessions[id] = session
        ApexLog.i(
            ApexSuite.ApkId.VOICE,
            "[SessionManager] registered session: $id (mode=${mode.name}, language=${config.language})"
        )
        return session
    }

    /** 按 id 查询会话。 */
    fun get(sessionId: String): VoiceSession? = sessions[sessionId]

    /** 列出所有会话（按创建时间升序）。 */
    fun list(): List<VoiceSession> =
        sessions.values.sortedBy { it.createdAt }

    /** 列出所有活跃会话。 */
    fun activeSessions(): List<VoiceSession> =
        sessions.values.filter { it.active }.sortedBy { it.createdAt }

    /**
     * 标记会话为非活跃（保留记录以便查询历史）。
     */
    fun markInactive(sessionId: String): Boolean {
        val s = sessions[sessionId] ?: return false
        sessions[sessionId] = s.copy(active = false, lastActiveAt = System.currentTimeMillis())
        return true
    }

    /**
     * 触碰会话活跃时间（接收到事件时调用）。
     */
    fun touch(sessionId: String) {
        val s = sessions[sessionId] ?: return
        sessions[sessionId] = s.copy(lastActiveAt = System.currentTimeMillis())
    }

    /** 删除指定会话。 */
    fun remove(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) {
            ApexLog.i(ApexSuite.ApkId.VOICE, "[SessionManager] removed session: $sessionId")
        }
        return removed
    }

    /**
     * 回收超过 [maxIdleMs] 未活跃的会话。
     *
     * @return 被回收的会话 ID 列表
     */
    fun gc(maxIdleMs: Long = 30 * 60 * 1000L): List<String> {
        val now = System.currentTimeMillis()
        val dead = sessions.entries
            .filter { now - it.value.lastActiveAt > maxIdleMs }
            .map { it.key }
        dead.forEach { sessions.remove(it) }
        if (dead.isNotEmpty()) {
            ApexLog.i(ApexSuite.ApkId.VOICE, "[SessionManager] gc reclaimed ${dead.size} stale sessions")
        }
        return dead
    }

    /** 当前会话总数。 */
    fun size(): Int = sessions.size

    /** 清空所有会话。 */
    fun clear() {
        val n = sessions.size
        sessions.clear()
        ApexLog.i(ApexSuite.ApkId.VOICE, "[SessionManager] cleared $n sessions")
    }
}
