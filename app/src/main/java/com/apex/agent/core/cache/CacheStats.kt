package com.apex.agent.core.cache

/**
 * 缓存统计信息，用于监控和分析缓存运行状况。
 *
 * 聚合各级缓存的命中率、驱逐次数、内存占用及平均访问时间等指标，
 * 辅助缓存策略调优与性能诊断。
 *
 * @property hits         总命中次数
 * @property misses       总未命中次数
 * @property evictions    总驱逐次数
 * @property totalEntries 当前缓存条目总数
 * @property memoryUsage  内存占用估算（字节）
 * @property avgAccessTime 平均访问时间（纳秒）
 * @property hitRate      命中率（0.0 ~ 1.0），由 hits 与 misses 计算得出
 * @property missRate     未命中率（0.0 ~ 1.0）
 */
data class CacheStats(
    val hits: Long = 0L,
    val misses: Long = 0L,
    val evictions: Long = 0L,
    val totalEntries: Int = 0,
    val memoryUsage: Long = 0L,
    val avgAccessTime: Long = 0L
) {

    /** 命中率 = hits / (hits + misses)，若无请求则返回 0.0 */
    val hitRate: Double
        get() {
            val total = hits + misses
            return if (total == 0L) 0.0 else hits.toDouble() / total
        }

    /** 未命中率 = 1 - hitRate */
    val missRate: Double
        get() = 1.0 - hitRate

    /** 将指定次数的命中和未命中累加到当前统计中 */
    fun record(hit: Boolean, accessTimeNanos: Long = 0L): CacheStats {
        return copy(
            hits = if (hit) hits + 1 else hits,
            misses = if (!hit) misses + 1 else misses,
            avgAccessTime = if (avgAccessTime == 0L) accessTimeNanos
                else (avgAccessTime + accessTimeNanos) / 2
        )
    }

    /** 记录一次驱逐事件 */
    fun recordEviction(): CacheStats {
        return copy(evictions = evictions + 1)
    }

    /** 合并另一个 CacheStats 实例 */
    fun merge(other: CacheStats): CacheStats {
        return copy(
            hits = hits + other.hits,
            misses = misses + other.misses,
            evictions = evictions + other.evictions,
            totalEntries = totalEntries + other.totalEntries,
            memoryUsage = memoryUsage + other.memoryUsage,
            avgAccessTime = maxOf(avgAccessTime, other.avgAccessTime)
        )
    }
}
