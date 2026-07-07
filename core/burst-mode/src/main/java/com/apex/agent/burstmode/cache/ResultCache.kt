package com.apex.agent.burstmode.cache

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.BurstSkillResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存策略。
 */
sealed class CacheStrategy {

    /**
     * 不缓存。
     */
    object NoCache : CacheStrategy()

    /**
     * 基于时间的缓存。
     *
     * @property ttlMs 生存时间（毫秒），超过后失效
     * @property maxSize 最大缓存条目数（0 表示无限制）
     */
    data class TimeBased(
        val ttlMs: Long,
        val maxSize: Int = 100
    ) : CacheStrategy()

    /**
     * 基于 LRU 的缓存。
     *
     * @property maxSize 最大缓存条目数
     */
    data class LRU(val maxSize: Int) : CacheStrategy()

    /**
     * 基于 LFU 的缓存。
     *
     * @property maxSize 最大缓存条目数
     */
    data class LFU(val maxSize: Int) : CacheStrategy()
}

/**
 * 缓存条目。
 */
data class CacheEntry(
    val key: String,
    val result: BurstSkillResult,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Long = 0
) {
    /**
     * 是否过期。
     */
    fun isExpired(ttlMs: Long): Boolean {
        return System.currentTimeMillis() - createdAt > ttlMs
    }

    /**
     * 记录访问。
     */
    fun recordAccess(): CacheEntry {
        return copy(
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = accessCount + 1
        )
    }
}

/**
 * 缓存统计。
 */
data class CacheStats(
    val totalRequests: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val currentSize: Int,
    val evictions: Long
) {
    val hitRate: Double
        get() = if (totalRequests > 0) cacheHits.toDouble() / totalRequests else 0.0
}

/**
 * 结果缓存。
 *
 * 缓存任务执行结果，避免重复计算。
 *
 * # 使用示例
 *
 * ```
 * val cache = ResultCache(CacheStrategy.TimeBased(ttlMs = 60_000, maxSize = 100))
 *
 * // 检查缓存
 * val cached = cache.get(task)
 * if (cached != null) return cached
 *
 * // 执行并缓存
 * val result = executeTask(task)
 * cache.put(task, result)
 * ```
 *
 * ## 与 execute 集成
 * ```
 * val burstMode = BurstMode.create(context)
 *     .withCache(CacheStrategy.TimeBased(ttlMs = 300_000))
 *     .initialize()
 *
 * // 相同任务第二次调用直接返回缓存
 * val result1 = burstMode.execute(task)  // 实际执行
 * val result2 = burstMode.execute(task)  // 缓存命中
 * ```
 */
class ResultCache(
    private val strategy: CacheStrategy,
    private val keyGenerator: (BurstTask) -> String = DefaultKeyGenerator
) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val totalRequests = java.util.concurrent.atomic.AtomicLong(0)
    private val cacheHits = java.util.concurrent.atomic.AtomicLong(0)
    private val cacheMisses = java.util.concurrent.atomic.AtomicLong(0)
    private val evictions = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 获取缓存。
     *
     * @param task 任务
     * @return 缓存的结果，不存在或过期返回 null
     */
    fun get(task: BurstTask): BurstSkillResult? {
        if (strategy is CacheStrategy.NoCache) return null

        totalRequests.incrementAndGet()
        val key = keyGenerator(task)
        val entry = cache[key] ?: run {
            cacheMisses.incrementAndGet()
            return null
        }

        // 检查过期（仅 TimeBased）
        if (strategy is CacheStrategy.TimeBased && entry.isExpired(strategy.ttlMs)) {
            cache.remove(key)
            cacheMisses.incrementAndGet()
            return null
        }

        // 更新访问信息
        cache[key] = entry.recordAccess()
        cacheHits.incrementAndGet()
        return entry.result
    }

    /**
     * 写入缓存。
     *
     * @param task 任务
     * @param result 执行结果
     */
    fun put(task: BurstTask, result: BurstSkillResult) {
        if (strategy is CacheStrategy.NoCache) return
        if (!result.success) return  // 失败结果不缓存

        val key = keyGenerator(task)
        val entry = CacheEntry(key = key, result = result)
        cache[key] = entry

        // 执行淘汰
        evictIfNeeded()
    }

    /**
     * 移除指定任务的缓存。
     */
    fun remove(task: BurstTask): Boolean {
        val key = keyGenerator(task)
        return cache.remove(key) != null
    }

    /**
     * 按 key 前缀批量移除。
     */
    fun removeByPrefix(prefix: String): Int {
        var count = 0
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    /**
     * 检查是否命中缓存（不更新访问计数）。
     */
    fun contains(task: BurstTask): Boolean {
        if (strategy is CacheStrategy.NoCache) return false
        val key = keyGenerator(task)
        val entry = cache[key] ?: return false
        if (strategy is CacheStrategy.TimeBased && entry.isExpired(strategy.ttlMs)) {
            return false
        }
        return true
    }

    /**
     * 获取当前缓存大小。
     */
    fun size(): Int = cache.size

    /**
     * 获取缓存统计。
     */
    fun getStats(): CacheStats {
        return CacheStats(
            totalRequests = totalRequests.get(),
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            currentSize = cache.size,
            evictions = evictions.get()
        )
    }

    /**
     * 清空缓存。
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 清理过期条目（仅 TimeBased 策略）。
     *
     * @return 清理的条目数
     */
    fun cleanExpired(): Int {
        if (strategy !is CacheStrategy.TimeBased) return 0
        var count = 0
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(strategy.ttlMs)) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    /**
     * 根据策略淘汰。
     */
    private fun evictIfNeeded() {
        val maxSize = when (strategy) {
            is CacheStrategy.NoCache -> return
            is CacheStrategy.TimeBased -> strategy.maxSize
            is CacheStrategy.LRU -> strategy.maxSize
            is CacheStrategy.LFU -> strategy.maxSize
        }

        if (maxSize <= 0) return  // 无限制

        while (cache.size > maxSize) {
            val toEvict = when (strategy) {
                is CacheStrategy.LRU -> cache.minByOrNull { it.value.lastAccessedAt }
                is CacheStrategy.LFU -> cache.minByOrNull { it.value.accessCount }
                is CacheStrategy.TimeBased -> cache.minByOrNull { it.value.createdAt }
                else -> cache.entries.firstOrNull()
            }

            if (toEvict != null) {
                cache.remove(toEvict.key)
                evictions.incrementAndGet()
            } else {
                break
            }
        }
    }

    companion object {
        /**
         * 默认 key 生成器。
         *
         * 基于任务的 id + description + metadata 生成 SHA-256 哈希。
         */
        val DefaultKeyGenerator: (BurstTask) -> String = { task ->
            val input = buildString {
                append(task.id)
                append("|")
                append(task.description)
                append("|")
                // 排序 metadata 保证相同内容生成相同 key
                task.metadata.toSortedMap().forEach { (k, v) ->
                    append("$k=$v;")
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}

/**
 * 缓存键构建器。
 *
 * 用于自定义缓存 key 生成逻辑。
 *
 * ```
 * val cache = ResultCache(
 *     strategy = CacheStrategy.TimeBased(ttlMs = 60_000),
 *     keyGenerator = { task ->
 *         cacheKey {
 *             include(task.description)
 *             include(task.metadata["taskType"])
 *             exclude(task.id)  // 忽略 id，让相同描述的任务共享缓存
 *         }
 *     }
 * )
 * ```
 */
fun cacheKey(init: CacheKeyBuilder.() -> Unit): String {
    return CacheKeyBuilder().apply(init).build()
}

/**
 * 缓存键构建器。
 */
class CacheKeyBuilder {
    private val parts = mutableListOf<String>()

    fun include(value: Any?) {
        if (value != null) parts.add(value.toString())
    }

    fun exclude(@Suppress("UNUSED_PARAMETER") value: Any?) {
        // 占位方法，语义上表示"排除此项"
    }

    fun build(): String {
        val input = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
