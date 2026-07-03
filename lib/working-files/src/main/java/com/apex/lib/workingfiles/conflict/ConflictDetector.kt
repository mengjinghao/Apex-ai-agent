package com.apex.lib.workingfiles.conflict

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * 多 Agent 冲突检测器 — Apex 独有（VSCode/Cline 单 Agent 不需要）。
 *
 * **创新点**：当多个 Agent 同时操作同一文件时，主动检测冲突。
 *
 * **场景**：
 *   - 多 Agent 模式下，Supervisor 分派两个 Worker 同时改一个文件
 *   - 狂暴模式下，并行路径修改同一文件
 *   - 用户手动编辑与 Agent 编辑同时发生
 *
 * **机制**：
 *   - Agent 写入前调用 [acquireLock] 获取文件锁
 *   - 其他 Agent 尝试写入时检测到锁，进入等待或返回冲突
 *   - 锁有 TTL（默认 30 秒），避免 Agent 崩溃后死锁
 *   - 支持锁升级：READ_LOCK → WRITE_LOCK
 *
 * **锁类型**：
 *   - READ_LOCK: 多个 Agent 可同时读
 *   - WRITE_LOCK: 独占写，其他 Agent 必须等待
 *   - EXCLUSIVE_LOCK: 完全独占，连读都不允许
 */
class ConflictDetector {

    /** 文件路径 → 当前活跃的锁列表 */
    private val locks = ConcurrentHashMap<String, MutableList<FileLock>>()

    /** 文件路径 → 待处理的等待者 */
    private val waiters = ConcurrentHashMap<String, MutableList<LockRequest>>()

    /**
     * 获取文件锁。
     *
     * @param filePath 文件路径
     * @param agentId Agent ID
     * @param type 锁类型
     * @param ttlMs 锁有效期（毫秒），超时自动释放
     * @return 锁 token（成功）或 null（失败，已被其他 Agent 锁定）
     */
    @Synchronized
    fun acquireLock(
        filePath: String,
        agentId: String,
        type: LockType,
        ttlMs: Long = 30_000L
    ): String? {
        // 清理过期锁
        cleanExpiredLocks(filePath)

        val existing = locks[filePath] ?: mutableListOf()
        val canAcquire = when (type) {
            LockType.READ_LOCK -> existing.none { it.type == LockType.EXCLUSIVE_LOCK || it.type == LockType.WRITE_LOCK }
            LockType.WRITE_LOCK -> existing.none { it.type != LockType.READ_LOCK } && existing.none { it.agentId != agentId }
            LockType.EXCLUSIVE_LOCK -> existing.isEmpty()
        }

        if (!canAcquire) {
            // 记录等待者
            val request = LockRequest(
                filePath = filePath,
                agentId = agentId,
                type = type,
                requestedAt = System.currentTimeMillis()
            )
            waiters.getOrPut(filePath) { mutableListOf() }.add(request)
            return null
        }

        val lock = FileLock(
            token = generateToken(),
            filePath = filePath,
            agentId = agentId,
            type = type,
            acquiredAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + ttlMs
        )
        existing.add(lock)
        locks[filePath] = existing
        return lock.token
    }

    /**
     * 释放锁。
     */
    @Synchronized
    fun releaseLock(token: String): Boolean {
        for ((filePath, fileLocks) in locks) {
            val idx = fileLocks.indexOfFirst { it.token == token }
            if (idx >= 0) {
                fileLocks.removeAt(idx)
                if (fileLocks.isEmpty()) locks.remove(filePath)
                // 通知等待者（简化版：下次 acquireLock 时自然获取）
                return true
            }
        }
        return false
    }

    /**
     * 释放某 Agent 的所有锁。
     */
    @Synchronized
    fun releaseAllForAgent(agentId: String): Int {
        var count = 0
        val toRemove = mutableListOf<String>()
        for ((filePath, fileLocks) in locks) {
            val before = fileLocks.size
            fileLocks.removeAll { it.agentId == agentId }
            count += before - fileLocks.size
            if (fileLocks.isEmpty()) toRemove.add(filePath)
        }
        toRemove.forEach { locks.remove(it) }
        return count
    }

    /**
     * 检查文件是否被锁定。
     */
    @Synchronized
    fun isLocked(filePath: String): Boolean {
        cleanExpiredLocks(filePath)
        return locks[filePath]?.isNotEmpty() == true
    }

    /**
     * 获取文件的锁状态。
     */
    @Synchronized
    fun getLockStatus(filePath: String): LockStatus {
        cleanExpiredLocks(filePath)
        val fileLocks = locks[filePath] ?: return LockStatus(locks = emptyList(), waiters = emptyList())
        val waitersList = waiters[filePath] ?: emptyList()
        return LockStatus(locks = fileLocks.toList(), waiters = waitersList.toList())
    }

    /**
     * 列出所有有锁的文件。
     */
    @Synchronized
    fun listLockedFiles(): List<String> {
        val now = System.currentTimeMillis()
        return locks.filter { (_, fileLocks) ->
            fileLocks.any { it.expiresAt > now }
        }.keys.toList()
    }

    /**
     * 检测潜在冲突：两个 Agent 是否可能冲突。
     */
    @Synchronized
    fun detectPotentialConflict(filePath: String, agentId: String): ConflictWarning? {
        cleanExpiredLocks(filePath)
        val fileLocks = locks[filePath] ?: return null
        val otherAgentLocks = fileLocks.filter { it.agentId != agentId }
        if (otherAgentLocks.isEmpty()) return null

        val hasWriteLock = otherAgentLocks.any { it.type == LockType.WRITE_LOCK || it.type == LockType.EXCLUSIVE_LOCK }
        if (hasWriteLock) {
            return ConflictWarning(
                filePath = filePath,
                conflictType = ConflictType.WRITE_WRITE,
                conflictingAgents = otherAgentLocks.map { it.agentId }.distinct(),
                message = "文件被其他 Agent 写锁定，强行修改会导致数据丢失"
            )
        }

        // 有 READ_LOCK 时尝试 WRITE 也算冲突
        return null
    }

    private fun cleanExpiredLocks(filePath: String) {
        val now = System.currentTimeMillis()
        val fileLocks = locks[filePath] ?: return
        fileLocks.removeAll { it.expiresAt <= now }
        if (fileLocks.isEmpty()) locks.remove(filePath)
    }

    private fun generateToken(): String {
        val ts = System.currentTimeMillis().toString(36)
        val rand = (0 until 8).map { ('a'..'z').random() }.joinToString("")
        return "lock-$ts-$rand"
    }
}

/** 锁类型。 */
@Serializable
enum class LockType(val displayName: String) {
    READ_LOCK("读锁"),         // 共享读
    WRITE_LOCK("写锁"),        // 独占写
    EXCLUSIVE_LOCK("独占锁")    // 完全独占
}

/** 文件锁。 */
@Serializable
data class FileLock(
    val token: String,
    val filePath: String,
    val agentId: String,
    val type: LockType,
    val acquiredAt: Long,
    val expiresAt: Long
)

/** 锁请求（等待者）。 */
@Serializable
data class LockRequest(
    val filePath: String,
    val agentId: String,
    val type: LockType,
    val requestedAt: Long
)

/** 锁状态。 */
data class LockStatus(
    val locks: List<FileLock>,
    val waiters: List<LockRequest>
) {
    val isLocked: Boolean get() = locks.isNotEmpty()
    val lockHolders: List<String> get() = locks.map { it.agentId }.distinct()
    val waiterCount: Int get() = waiters.size
}

/** 冲突类型。 */
@Serializable
enum class ConflictType(val displayName: String) {
    WRITE_WRITE("写写冲突"),       // 两个 Agent 同时写
    READ_WRITE("读写冲突"),        // 一个读一个写
    EXCLUSIVE_CONFLICT("独占冲突")  // 与独占锁冲突
}

/** 冲突警告。 */
@Serializable
data class ConflictWarning(
    val filePath: String,
    val conflictType: ConflictType,
    val conflictingAgents: List<String>,
    val message: String
)
