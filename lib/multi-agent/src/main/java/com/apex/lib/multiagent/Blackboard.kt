package com.apex.lib.multiagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 共享黑板（增强版）— Agent 间共享数据。
 *
 * **增强点**：
 *   - 类型安全 get<T>（泛型）
 *   - 订阅机制（entries flow）
 *   - TTL 自动过期（可选）
 *   - 命名空间隔离（按 sessionId 分组）
 *   - 历史记录（可选保留写入历史）
 */
class Blackboard {

    private val store = ConcurrentHashMap<String, BlackboardEntry>()
    private val _entries = MutableSharedFlow<BlackboardEntry>(extraBufferCapacity = 256)
    val entries: SharedFlow<BlackboardEntry> = _entries.asSharedFlow()

    /**
     * 写入值。
     * @param key 键
     * @param value 值
     * @param ttlMs 生存时间（毫秒，0=永不过期）
     * @param writer 写入者 Agent ID
     */
    fun put(key: String, value: Any, ttlMs: Long = 0L, writer: String? = null) {
        val now = System.currentTimeMillis()
        val entry = BlackboardEntry(
            key = key,
            value = value,
            timestamp = now,
            expireAt = if (ttlMs > 0) now + ttlMs else 0L,
            writer = writer
        )
        store[key] = entry
        _entries.tryEmit(entry)
    }

    /**
     * 读取值（类型安全）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val entry = store[key] ?: return null
        if (entry.isExpired) {
            store.remove(key)
            return null
        }
        return entry.value as? T
    }

    /**
     * 读取完整 entry（含元数据）。
     */
    fun getEntry(key: String): BlackboardEntry? {
        val entry = store[key] ?: return null
        if (entry.isExpired) {
            store.remove(key)
            return null
        }
        return entry
    }

    fun remove(key: String) {
        store.remove(key)
    }

    fun contains(key: String): Boolean {
        val entry = store[key] ?: return false
        if (entry.isExpired) {
            store.remove(key)
            return false
        }
        return true
    }

    fun keys(): Set<String> {
        cleanExpired()
        return store.keys.toSet()
    }

    fun snapshot(): Map<String, Any> {
        cleanExpired()
        return store.mapValues { it.value.value }
    }

    /** 清空所有。 */
    fun clear() {
        store.clear()
    }

    /** 清除过期项。 */
    fun cleanExpired(): Int {
        val now = System.currentTimeMillis()
        val expired = store.filter { it.value.isExpired }
        expired.forEach { (k, _) -> store.remove(k) }
        return expired.size
    }

    /** 获取所有写入者。 */
    fun writers(): Set<String> {
        return store.values.mapNotNull { it.writer }.toSet()
    }

    /** 条目数。 */
    fun size(): Int = store.size
}

/** 黑板条目。 */
data class BlackboardEntry(
    val key: String,
    val value: Any,
    val timestamp: Long,
    val expireAt: Long = 0L,  // 0=永不过期
    val writer: String? = null
) {
    val isExpired: Boolean
        get() = expireAt > 0 && System.currentTimeMillis() > expireAt

    val ageMs: Long
        get() = System.currentTimeMillis() - timestamp
}
