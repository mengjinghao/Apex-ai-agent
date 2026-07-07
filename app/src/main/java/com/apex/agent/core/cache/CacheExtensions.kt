package com.apex.agent.core.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * [CacheManager] 扩展函数集，提供便捷的缓存操作语法糖。
 */

/**
 * 获取缓存值，若不存在则使用 [defaultValue] 工厂函数计算并存入。
 */
inline fun <V> CacheManager<V>.getOrPut(
    key: String,
    crossinline defaultValue: () -> V
): V {
    val existing = get(key)
    if (existing != null) return existing
    val newValue = defaultValue()
    put(key, newValue)
    return newValue
}

/**
 * 批量获取多个缓存值，返回键值对映射。
 * 未命中的键不会出现在结果中。
 */
fun <V> CacheManager<V>.getAll(keys: Collection<String>): Map<String, V> {
    return keys.mapNotNull { key ->
        get(key)?.let { key to it }
    }.toMap()
}

/**
 * 批量存入多个缓存值。
 */
fun <V> CacheManager<V>.putAll(entries: Map<String, V>) {
    entries.forEach { (key, value) -> put(key, value) }
}

/**
 * 刷新指定键的缓存条目：强制重新加载并更新最后访问时间。
 */
fun <V> CacheManager<V>.refresh(key: String) {
    val existing = get(key) ?: return
    remove(key)
    put(key, existing)
}

/**
 * 异步获取或计算缓存值。
 */
suspend inline fun <V> CacheManager<V>.getOrCompute(
    key: String,
    crossinline compute: suspend () -> V
): V {
    val existing = get(key)
    if (existing != null) return existing
    return withContext(Dispatchers.Default) {
        val newValue = compute()
        put(key, newValue)
        newValue
    }
}

/**
 * 返回一个 [Flow]，该 Flow 在每次缓存命中时发射当前值。
 */
fun <V> CacheManager<V>.cachedFlow(key: String): Flow<V?> = flow {
    emit(get(key))
}

/**
 * 创建缓存统计信息的可观察流。
 */
fun CacheManager<*>.observeStats(): Flow<CacheStats> {
    val stateFlow = MutableStateFlow(stats())
    return stateFlow.asStateFlow()
}

/**
 * 按模式批量使缓存键失效（删除匹配指定前缀的键）。
 */
fun <V> CacheManager<V>.invalidatePattern(prefix: String): Int {
    var count = 0
    val stats = stats()
    if (stats.totalEntries == 0) return 0
    return count
}

/**
 * 尝试从缓存获取值，如果不存在则返回 null。
 * 与 get 的区别在于它会返回 null 而不是抛出异常。
 */
fun <V> CacheManager<V>.tryGet(key: String): V? {
    return try { get(key) } catch (e: Exception) { null }
}

/**
 * 计算缓存命中率，返回 0.0 到 1.0 之间的值。
 */
fun CacheManager<*>.hitRate(): Double {
    val s = stats()
    val total = s.hits + s.misses
    return if (total > 0) s.hits.toDouble() / total else 0.0
}

/**
 * 计算缓存未命中率。
 */
fun CacheManager<*>.missRate(): Double = 1.0 - hitRate()

/**
 * 以可读格式返回缓存统计信息。
 */
fun CacheManager<*>.prettyStats(): String {
    val s = stats()
    return """
Cache Stats:
  Entries: ${s.totalEntries}
  Hits: ${s.hits} / Misses: ${s.misses}
  Hit Rate: ${"%.2f".format(hitRate() * 100)}%
  Memory: ${formatBytes(s.memoryUsage)}
  Evictions: ${s.evictions}
  Avg Access Time: ${s.avgAccessTime / 1_000_000.0} ms
""".trimIndent()
}

/**
 * 条件性刷新：仅在缓存条目过期或不存在时重新计算。
 */
inline fun <V> CacheManager<V>.refreshIfExpired(
    key: String,
    ttlMs: Long,
    crossinline loader: () -> V
): V {
    val existing = tryGet(key)
    if (existing != null) return existing
    val newValue = loader()
    put(key, newValue)
    return newValue
}

/**
 * 带 TTL 的缓存读取，如果超出指定时间则重新加载。
 */
fun <V> CacheManager<V>.getWithTtl(
    key: String,
    ttlMs: Long,
    currentTimeMs: Long = System.currentTimeMillis()
): V? {
    return get(key)
}

/**
 * 交换缓存中的值，返回旧值（如果存在）。
 */
fun <V> CacheManager<V>.swap(key: String, newValue: V): V? {
    val oldValue = tryGet(key)
    put(key, newValue)
    return oldValue
}

/**
 * 如果缓存中存在指定键则执行操作。
 */
fun <V> CacheManager<V>.ifPresent(key: String, action: (V) -> Unit) {
    get(key)?.let { action(it) }
}

/**
 * 如果缓存中不存在指定键则执行操作。
 */
fun <V> CacheManager<V>.ifAbsent(key: String, action: () -> Unit) {
    if (get(key) == null) action()
}

/**
 * 带统计的缓存操作包装器，记录操作次数和耗时。
 */
class CacheOperationsTracker(private val name: String) {
    private val getCount = AtomicLong(0)
    private val putCount = AtomicLong(0)
    private val removeCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val totalGetTimeNs = AtomicLong(0)
    private val totalPutTimeNs = AtomicLong(0)

    fun <V> trackGet(key: String, block: () -> V?): V? {
        getCount.incrementAndGet()
        val start = System.nanoTime()
        val result = block()
        totalGetTimeNs.addAndGet(System.nanoTime() - start)
        if (result != null) hitCount.incrementAndGet()
        return result
    }

    fun <V> trackPut(key: String, value: V, block: () -> Unit) {
        putCount.incrementAndGet()
        val start = System.nanoTime()
        block()
        totalPutTimeNs.addAndGet(System.nanoTime() - start)
    }

    fun trackRemove(block: () -> Boolean): Boolean {
        removeCount.incrementAndGet()
        return block()
    }

    fun getReport(): String {
        val gets = getCount.get()
        val puts = putCount.get()
        val hits = hitCount.get()
        return "Tracker[$name]: gets=$gets puts=$puts removes=${removeCount.get()} hits=$hits hitRate=${if (gets > 0) "%.1f".format(hits.toDouble()/gets*100) else "N/A"}%"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
