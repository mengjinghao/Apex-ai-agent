package com.apex.agent.core.cache

import kotlinx.serialization.Serializable

/**
 * 缓存条目，表示缓存中存储的一个键值对及其元数据。
 *
 * 支持泛型值类型 [T]，并记录访问时间、命中次数、TTL 等统计信息，
 * 用于实现 LRU / LFU / TTL 等缓存淘汰策略。
 *
 * @param T 缓存值的类型
 * @property key          缓存键，全局唯一标识
 * @property value        缓存值
 * @property createdAt    创建时间戳（毫秒）
 * @property lastAccessedAt 最后访问时间戳（毫秒）
 * @property ttl          存活时间（毫秒），超过此时间视为过期
 * @property hitCount     命中次数，用于 LFU 策略
 * @property sizeBytes    条目估算大小（字节），用于内存/磁盘用量统计
 * @property serialized   是否已序列化为 JSON 字符串
 */
@Serializable
data class CacheEntry<T>(
    val key: String,
    val value: T,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val ttl: Long = -1L,
    val hitCount: Long = 0L,
    val sizeBytes: Long = -1L,
    val serialized: Boolean = false
) {

    /** 判断当前条目是否已过期 */
    fun isExpired(): Boolean {
        if (ttl <= 0) return false
        return (System.currentTimeMillis() - createdAt) > ttl
    }

    /** 返回一个更新了最后访问时间和命中计数的新条目副本 */
    fun recordAccess(): CacheEntry<T> {
        return copy(
            lastAccessedAt = System.currentTimeMillis(),
            hitCount = hitCount + 1
        )
    }

    /** 返回一个标记为已序列化的新条目副本 */
    fun markSerialized(): CacheEntry<T> {
        return copy(serialized = true)
    }

    /** 返回一个标记为未序列化的新条目副本 */
    fun markDeserialized(): CacheEntry<T> {
        return copy(serialized = false)
    }

    /** 估算当前值的序列化大小（简单字符串场景） */
    fun estimateSize(): Long {
        if (sizeBytes > 0) return sizeBytes
        return (key.length + value.toString().length).toLong()
    }
}
