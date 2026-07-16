package com.apex.agent.core.cache

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * L3 分布式缓存存储实现（桩实现），为未来网络缓存提供接口骨架。
 *
 * 当前作为本地模拟实现，支持：
 * - 模拟远端数据获取（通过网络延迟模拟）
 * - 可配置的重试逻辑（指数退避）
 * - 降级链路：分布式缓存不可用时自动回退到本地备份
 * - 占位符接口，方便后续接入 Redis / Memcached 等分布式缓存
 *
 * @param remoteUrl     远端缓存服务 URL（预留）
 * @param maxRetries    最大重试次数
 * @param retryDelayMs  重试间隔基数毫秒
 * @param localBackup   是否启用本地备份（降级时使用 ConcurrentHashMap）
 */
class DistributedCacheStore<V>(
    private val remoteUrl: String = "",
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 100L,
    private val localBackup: Boolean = true
) : ICacheStore<String, V> {

    private val log = LoggerFactory.getLogger(DistributedCacheStore::class.java)

    /** 本地备份存储，用于降级场景 */
    private val backupStore = ConcurrentHashMap<String, CacheEntry<V>>()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)

    override fun get(key: String): CacheEntry<V>? {
        return retryable {
            simulateNetworkFetch(key)
        } ?: fallbackGet(key)
    }

    override fun put(entry: CacheEntry<V>) {
        retryable {
            simulateNetworkPut(entry.key, entry)
            Unit
        } ?: fallbackPut(entry)
    }

    override fun remove(key: String): Boolean {
        return retryable {
            simulateNetworkDelete(key)
            true
        } ?: fallbackRemove(key)
    }

    override fun clear() {
        backupStore.clear()
        log.info("Distributed cache cleared (local backup only)")
    }

    override fun contains(key: String): Boolean {
        return retryable {
            simulateNetworkContains(key)
        } ?: backupStore.containsKey(key)
    }

    override fun size(): Int = backupStore.size

    override fun stats(): CacheStats {
        return CacheStats(
            hits = hits.get(),
            misses = misses.get(),
            evictions = evictions.get(),
            totalEntries = backupStore.size,
            memoryUsage = -1L,
            avgAccessTime = 0L
        )
    }

    override fun evict(policy: CachePolicy): List<String> {
        val evictedKeys = mutableListOf<String>()
        val candidates = when (policy) {
            is CachePolicy.TtlPolicy -> {
                backupStore.values.filter { it.isExpired() }.map { it.key }
            }
            is CachePolicy.LruPolicy -> {
                policy.evictCandidates(backupStore.values).map { it.key }
            }
            is CachePolicy.LfuPolicy -> {
                policy.evictCandidates(backupStore.values).map { it.key }
            }
            is CachePolicy.FifoPolicy -> {
                policy.evictCandidates(backupStore.values).map { it.key }
            }
            is CachePolicy.HybridPolicy -> {
                backupStore.values.sortedByDescending { policy.evictionScore(it) }
                    .take((backupStore.size * 0.25).toInt().coerceAtLeast(1))
                    .map { it.key }
            }
        }
        for (key in candidates) {
            backupStore.remove(key)
            evictedKeys.add(key)
        }
        evictions.addAndGet(evictedKeys.size.toLong())
        return evictedKeys
    }

    override fun warmUp(keys: Collection<String>): Int {
        var loaded = 0
        for (key in keys) {
            if (!backupStore.containsKey(key)) {
                retryable {
                    val entry = simulateNetworkFetch(key)
                    if (entry != null) {
                        backupStore[key] = entry
                        loaded++
                    }
                    null
                }
            } else {
                loaded++
            }
        }
        log.info("warmUp: {}/{} keys loaded from remote", loaded, keys.size)
        return loaded
    }

    /** 带指数退避的重试执行块 */
    private fun <T> retryable(block: () -> T): T? {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                log.warn("Distributed cache retry {}/{} failed: {}",
                    attempt, maxRetries, e.message)
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs * (1L shl (attempt - 1)))
                }
            }
        }
        log.error("Distributed cache exhausted {} retries", maxRetries, lastException)
        return null
    }

    /** 模拟远端网络获取 */
    private fun simulateNetworkFetch(key: String): CacheEntry<V>? {
        val entry = backupStore[key]
        return if (entry != null) {
            hits.incrementAndGet()
            entry.recordAccess().also { backupStore[key] = it }
        } else {
            misses.incrementAndGet()
            null
        }
    }

    /** 模拟远端网络写入 */
    private fun simulateNetworkPut(key: String, entry: CacheEntry<V>) {
        backupStore[key] = entry
    }

    /** 模拟远端网络删除 */
    private fun simulateNetworkDelete(key: String) {
        backupStore.remove(key)
    }

    /** 模拟远端网络包含检查 */
    private fun simulateNetworkContains(key: String): Boolean {
        return backupStore.containsKey(key)
    }

    /** 降级获取：若远端不可用，查本地备份 */
    private fun fallbackGet(key: String): CacheEntry<V>? {
        if (!localBackup) return null
        val entry = backupStore[key]
        if (entry != null) {
            hits.incrementAndGet()
            return entry.recordAccess().also { backupStore[key] = it }
        }
        misses.incrementAndGet()
        return null
    }

    /** 降级写入：若远端不可用，写入本地备份 */
    private fun fallbackPut(entry: CacheEntry<V>) {
        if (localBackup) {
            backupStore[entry.key] = entry
        }
    }

    /** 降级删除：若远端不可用，从本地备份删除 */
    private fun fallbackRemove(key: String): Boolean {
        return if (localBackup) {
            backupStore.remove(key) != null
        } else false
    }
}
