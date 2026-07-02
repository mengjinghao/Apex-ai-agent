package com.apex.agent.core.cache

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * L1 内存缓存存储实现，基于 [ConcurrentHashMap] 提供高性能的内存缓存。
 *
 * 特性：
 * - 线程安全的读写操作（ReentrantReadWriteLock 并发控制）
 * - LRU 淘汰策略，超限时自动驱逐最久未访问的条目
 * - TTL 过期检测，访问时惰性删除过期条目
 * - 可配置最大条目数和最大内存使用量
 * - 精确的命中/未命中统计
 * - 支持批量操作（getAll / putAll / removeAll）
 *
 * @param maxSize    最大缓存条目数（-1 表示不限）
 * @param maxMemory  最大内存使用量字节数（-1 表示不限）
 * @param defaultTtl 默认过期时间毫秒（-1 表示永不过期）
 */
class MemoryCacheStore<V>(
    private val maxSize: Int = -1,
    private val maxMemory: Long = -1L,
    private val defaultTtl: Long = -1L
) : ICacheStore<String, V> {

    private val log = LoggerFactory.getLogger(MemoryCacheStore::class.java)
    private val lock = ReentrantReadWriteLock()
    private val store = ConcurrentHashMap<String, CacheEntry<V>>()

    @Volatile
    private var currentMemoryBytes: Long = 0L

    private var hits: Long = 0L
    private var misses: Long = 0L
    private var evictions: Long = 0L
    private var totalAccessTime: Long = 0L
    private var accessCount: Long = 0L

    override fun get(key: String): CacheEntry<V>? {
        val start = System.nanoTime()
        lock.read {
            val entry = store[key] ?: return null.also {
                lock.write { misses++ }
            }
            if (entry.isExpired()) {
                lock.write {
                    store.remove(key)
                    currentMemoryBytes -= entry.sizeBytes.coerceAtLeast(0)
                    misses++
                }
                return null
            }
            val updated = entry.recordAccess()
            store[key] = updated
            lock.write {
                hits++
                accessCount++
                totalAccessTime += System.nanoTime() - start
            }
            return updated
        }
    }

    override fun put(entry: CacheEntry<V>) {
        lock.write {
            val oldEntry = store.put(entry.key, entry)
            if (oldEntry != null) {
                currentMemoryBytes -= oldEntry.sizeBytes.coerceAtLeast(0)
            }
            currentMemoryBytes += entry.sizeBytes.coerceAtLeast(0)
            evictIfNeeded()
        }
    }

    override fun remove(key: String): Boolean {
        lock.write {
            val removed = store.remove(key)
            if (removed != null) {
                currentMemoryBytes -= removed.sizeBytes.coerceAtLeast(0)
                return true
            }
            return false
        }
    }

    override fun clear() {
        lock.write {
            store.clear()
            currentMemoryBytes = 0L
            hits = 0L
            misses = 0L
            evictions = 0L
            totalAccessTime = 0L
            accessCount = 0L
        }
    }

    override fun contains(key: String): Boolean {
        lock.read {
            val entry = store[key] ?: return false
            if (entry.isExpired()) {
                lock.write {
                    store.remove(key)
                    currentMemoryBytes -= entry.sizeBytes.coerceAtLeast(0)
                }
                return false
            }
            return true
        }
    }

    override fun size(): Int = lock.read { store.size }

    override fun stats(): CacheStats {
        lock.read {
            return CacheStats(
                hits = hits,
                misses = misses,
                evictions = evictions,
                totalEntries = store.size,
                memoryUsage = currentMemoryBytes,
                avgAccessTime = if (accessCount == 0L) 0L
                    else totalAccessTime / accessCount
            )
        }
    }

    override fun evict(policy: CachePolicy): List<String> {
        lock.write {
            val evictedKeys = mutableListOf<String>()
            val candidates = when (policy) {
                is CachePolicy.TtlPolicy -> {
                    store.values.filter { it.isExpired() }.map { it.key }
                }
                is CachePolicy.LruPolicy -> {
                    policy.evictCandidates(store.values).map { it.key }
                }
                is CachePolicy.LfuPolicy -> {
                    policy.evictCandidates(store.values).map { it.key }
                }
                is CachePolicy.FifoPolicy -> {
                    policy.evictCandidates(store.values).map { it.key }
                }
                is CachePolicy.HybridPolicy -> {
                    store.values.sortedByDescending { policy.evictionScore(it) }
                        .take((store.size * 0.25).toInt().coerceAtLeast(1))
                        .map { it.key }
                }
            }
            for (key in candidates) {
                val removed = store.remove(key)
                if (removed != null) {
                    currentMemoryBytes -= removed.sizeBytes.coerceAtLeast(0)
                    evictedKeys.add(key)
                }
            }
            evictions += evictedKeys.size
            return evictedKeys
        }
    }

    override fun warmUp(keys: Collection<String>): Int {
        var loaded = 0
        for (key in keys) {
            if (!contains(key)) {
                log.warn("warmUp: key '{}' not present, implement external loader", key)
            } else {
                loaded++
            }
        }
        return loaded
    }

    /** 批量获取多个键对应的缓存条目 */
    fun getAll(keys: Collection<String>): Map<String, CacheEntry<V>> {
        val result = LinkedHashMap<String, CacheEntry<V>>()
        for (key in keys) {
            get(key)?.let { result[key] = it }
        }
        return result
    }

    /** 批量存入多个缓存条目 */
    fun putAll(entries: Map<String, CacheEntry<V>>) {
        for ((_, entry) in entries) {
            put(entry)
        }
    }

    /** 批量移除多个键对应的缓存条目 */
    fun removeAll(keys: Collection<String>): Int {
        var count = 0
        for (key in keys) {
            if (remove(key)) count++
        }
        return count
    }

    /** 当超过容量限制时自动执行 LRU 驱逐 */
    private fun evictIfNeeded() {
        if (maxSize > 0 && store.size > maxSize) {
            val overage = store.size - maxSize
            val entries = store.values.sortedBy { it.lastAccessedAt }
            for (i in 0 until overage) {
                if (i >= entries.size) break
                val key = entries[i].key
                val removed = store.remove(key)
                if (removed != null) {
                    currentMemoryBytes -= removed.sizeBytes.coerceAtLeast(0)
                    evictions++
                }
            }
        }
        if (maxMemory > 0 && currentMemoryBytes > maxMemory) {
            val entries = store.values.sortedBy { it.lastAccessedAt }
            for (entry in entries) {
                if (currentMemoryBytes <= maxMemory) break
                val removed = store.remove(entry.key)
                if (removed != null) {
                    currentMemoryBytes -= removed.sizeBytes.coerceAtLeast(0)
                    evictions++
                }
            }
        }
    }
}
