package com.apex.agent.core.cache

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多级缓存编排器，协调 L1（内存）、L2（磁盘）、L3（分布式）三级缓存。
 *
 * 核心策略：
 * - **Read-Through**：读取时按 L1 → L2 → L3 顺序查找，命中后逐级回写（缓存传播）
 * - **Write-Through**：写入时同步写入 L1 和 L2，异步提交 L3
 * - **Write-Back**（回写）：写入 L1 后异步批量刷入 L2
 * - **缓存一致性**：任意级别更新时，逐级传播使所有级别数据一致
 * - **条目晋升/降级**：高频访问条目自动晋升到 L1，低频条目自动降级到 L2/L3
 * - **可配置逐条目策略**：每个 key 可绑定独立的 [CachePolicy]
 * - **批量操作**：支持 get / put / remove 的批量版本
 * - **指标聚合**：汇总三级缓存的 [CacheStats]
 *
 * @param memoryStore       L1 内存缓存存储
 * @param diskStore         L2 磁盘缓存存储
 * @param distributedStore  L3 分布式缓存存储
 * @param defaultPolicy     默认淘汰策略
 * @param promotionThreshold 访问次数超过此阈值时晋升条目到 L1
 * @param demotionThreshold  访问次数低于此阈值时降级条目到 L2
 * @param writeBackIntervalMs 回写间隔毫秒（Write-Back 模式使用）
 */
class CacheManager<V>(
    private val memoryStore: MemoryCacheStore<V>,
    private val diskStore: DiskCacheStore? = null,
    private val distributedStore: DistributedCacheStore<V>? = null,
    private val defaultPolicy: CachePolicy = CachePolicy.LruPolicy(1000),
    private val promotionThreshold: Long = 10L,
    private val demotionThreshold: Long = 3L,
    private val writeBackIntervalMs: Long = 5000L
) {

    private val log = LoggerFactory.getLogger(CacheManager::class.java)

    /** 条目级别的策略配置，key 为缓存键，value 为该条目绑定的策略 */
    private val entryPolicies = ConcurrentHashMap<String, CachePolicy>()

    /** 条目访问计数器，用于晋升/降级决策 */
    private val accessCounters = ConcurrentHashMap<String, AtomicLong>()

    /** 回写队列（Write-Back 模式）：待异步写入 L2 的条目 */
    private val writeBackQueue = ConcurrentHashMap<String, CacheEntry<V>>()

    /** 是否启用 Write-Back 模式 */
    private val useWriteBack: Boolean = diskStore != null && writeBackIntervalMs > 0

    /** 回写调度器 */
    private val writeBackScheduler = if (useWriteBack) {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cache-writeback").apply { isDaemon = true }
        }.also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { flushWriteBack() },
                writeBackIntervalMs,
                writeBackIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
    } else null

    private val closed = AtomicBoolean(false)

    /**
     * 获取缓存值：按 L1 → L2 → L3 顺序查找。
     * 命中后会将值传播到更高优先级的缓存级别。
     */
    fun get(key: String): V? {
        checkNotClosed()
        // L1
        memoryStore.get(key)?.let { entry ->
            recordAccess(key)
        return entry.value
        }
        // L2
        diskStore?.get(key)?.let { entry ->
            memoryStore.put(CacheEntry(key, entry.value))
            recordAccess(key)
        return entry.value
        }
        // L3
        distributedStore?.get(key)?.let { entry ->
            val v = entry.value
            memoryStore.put(CacheEntry(key, v))
            diskStore?.put(CacheEntry(key, v.toString()))
            recordAccess(key)
        return v
        }
        return null
    }

    /**
     * 存入缓存值。
     * - Write-Through 模式：同步写入 L1 + L2
     * - Write-Back 模式：写入 L1，入队异步写入 L2
     * - L3 始终异步写入
     */
    fun put(key: String, value: V, ttl: Long = -1L) {
        checkNotClosed()
        val entry = CacheEntry(key, value, ttl = ttl)
        memoryStore.put(entry)
        if (useWriteBack) {
            writeBackQueue[key] = entry
        } else {
            diskStore?.put(CacheEntry(key, value.toString()))
        }
        distributedStore?.put(entry)
        promoteIfNeeded(key)
    }

    /** 移除缓存值（从所有级别移除） */
    fun remove(key: String): Boolean {
        checkNotClosed()
        val fromMem = memoryStore.remove(key)
        diskStore?.remove(key)
        distributedStore?.remove(key)
        accessCounters.remove(key)
        entryPolicies.remove(key)
        writeBackQueue.remove(key)
        return fromMem
    }

    /** 清空所有缓存级别 */
    fun clear() {
        memoryStore.clear()
        diskStore?.clear()
        distributedStore?.clear()
        accessCounters.clear()
        entryPolicies.clear()
        writeBackQueue.clear()
    }

    /** 判断指定键是否在任何缓存级别中存在 */
    fun contains(key: String): Boolean {
        return memoryStore.contains(key) ||
            diskStore?.contains(key) == true ||
            distributedStore?.contains(key) == true
    }

    /** 返回各缓存级别的总条目数 */
    fun size(): Int {
        return memoryStore.size() +
            (diskStore?.size() ?: 0) +
            (distributedStore?.size() ?: 0)
    }

    /** 聚合三级缓存的统计信息 */
    fun stats(): CacheStats {
        var aggregated = memoryStore.stats()
        diskStore?.let { aggregated = aggregated.merge(it.stats()) }
        distributedStore?.let { aggregated = aggregated.merge(it.stats()) }
        return aggregated
    }

    /**
     * 为指定键绑定独立的淘汰策略。
     * 若不指定，则使用 [defaultPolicy]。
     */
    fun setPolicy(key: String, policy: CachePolicy) {
        entryPolicies[key] = policy
    }

    /** 移除指定键的独立策略绑定（恢复为默认策略） */
    fun removePolicy(key: String) {
        entryPolicies.remove(key)
    }

    /** 批量获取多个缓存值 */
    fun getAll(keys: Collection<String>): Map<String, V> {
        val result = LinkedHashMap<String, V>()
        for (key in keys) {
            get(key)?.let { result[key] = it }
        }
        return result
    }

    /** 批量存入多个缓存值 */
    fun putAll(entries: Map<String, V>) {
        for ((key, value) in entries) {
            put(key, value)
        }
    }

    /** 批量移除多个缓存键 */
    fun removeAll(keys: Collection<String>): Int {
        return keys.count { remove(it) }
    }

    /** 对指定键执行驱逐（使用该键绑定的策略或默认策略） */
    fun evict(key: String): Boolean {
        val policy = entryPolicies[key] ?: defaultPolicy
        val result = when (policy) {
            is CachePolicy.TtlPolicy -> {
                val entry = memoryStore.get(key) ?: return false
                policy.isExpired(entry)
            }
            else -> true
        }
        if (result) {
            remove(key)
        }
        return result
    }

    /** 在各级缓存上执行批量驱逐 */
    fun evictAll(): List<String> {
        val evicted = mutableListOf<String>()
        evicted.addAll(memoryStore.evict(defaultPolicy))
        diskStore?.evict(defaultPolicy)?.let { evicted.addAll(it) }
        distributedStore?.evict(defaultPolicy)?.let { evicted.addAll(it) }
        return evicted
    }

    /** 预热缓存：从分布式或磁盘加载指定键到 L1 */
    fun warmUp(keys: Collection<String>): Int {
        var loaded = 0
        for (key in keys) {
            if (memoryStore.contains(key)) {
                loaded++
                continue
            }
        val fromDisk = diskStore?.get(key)
        if (fromDisk != null) {
                memoryStore.put(CacheEntry(key, fromDisk.value as V))
                loaded++
                continue
            }
        val fromRemote = distributedStore?.get(key)
        if (fromRemote != null) {
                memoryStore.put(CacheEntry(key, fromRemote.value))
                loaded++
                continue
            }
        }
        log.info("warmUp: {}/{} keys loaded", loaded, keys.size)
        return loaded
    }

    /** 关闭缓存管理器，释放资源 */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            flushWriteBack()
            writeBackScheduler?.shutdown()
            try {
                writeBackScheduler?.awaitTermination(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                writeBackScheduler?.shutdownNow()
            }
        }
    }

    /** 晋升：高频访问条目从 L2/L3 提升到 L1 */
    private fun promoteIfNeeded(key: String) {
        val count = accessCounters[key]?.get() ?: return
        if (count >= promotionThreshold) {
            diskStore?.get(key)?.let { entry ->
                memoryStore.put(CacheEntry(key, entry.value as V))
            }
            distributedStore?.get(key)?.let { entry ->
                memoryStore.put(CacheEntry(key, entry.value))
            }
            accessCounters[key]?.set(0)
        }
    }

    /** 降级：低频访问条目从 L1 降至 L2 */
    private fun demoteIfNeeded(key: String) {
        val count = accessCounters[key]?.get() ?: return
        if (count <= demotionThreshold && memoryStore.contains(key)) {
            memoryStore.get(key)?.let { entry ->
                diskStore?.put(CacheEntry(key, entry.value.toString()))
            }
        }
    }

    /** 记录一次条目访问，更新访问计数器 */
    private fun recordAccess(key: String) {
        val counter = accessCounters.getOrPut(key) { AtomicLong(0) }
        counter.incrementAndGet()
        promoteIfNeeded(key)
    }

    /** 刷新回写队列：将待写入条目批量刷入 L2 */
    private fun flushWriteBack() {
        if (writeBackQueue.isEmpty()) return
        val batch = writeBackQueue.values.toList()
        writeBackQueue.clear()
        for (entry in batch) {
            try {
                diskStore?.put(CacheEntry(entry.key, entry.value.toString()))
            } catch (e: Exception) {
                log.warn("flushWriteBack failed for key '{}': {}", entry.key, e.message)
                writeBackQueue[entry.key] = entry
            }
        }
    }

    /** 检查当前实例是否已关闭 */
    private fun checkNotClosed() {
        if (closed.get()) throw IllegalStateException("CacheManager is already closed")
    }
}
