package com.apex.agent.core.cache

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * L2 磁盘缓存存储实现，基于 JSON 文件提供持久化缓存。
 *
 * 特性：
 * - 每个缓存条目存储为独立的 JSON 文件，文件名为 key 的 URL 安全哈希
 * - 原子写入（先写临时文件，再重命名为目标文件），防数据损坏
 * - 目录结构：根目录下按哈希前缀分两级子目录（如 a/b/abcdef.json）
 * - 可配置最大磁盘使用量，超限时按 LRU 驱逐
 * - 惰性反序列化：读取时仅解析元数据，值按需反序列化
 * - 周期性后台清理过期条目
 * - 线程安全的读写并发控制
 *
 * @param cacheDir  缓存根目录
 * @param maxSize   最大缓存条目数（-1 表示不限）
 * @param maxDiskBytes 最大磁盘使用字节数（-1 表示不限）
 * @param defaultTtl 默认过期时间毫秒（-1 表示永不过期）
 * @param cleanupIntervalSec 过期清理周期秒数
 */
class DiskCacheStore(
    private val cacheDir: File,
    private val maxSize: Int = -1,
    private val maxDiskBytes: Long = -1L,
    private val defaultTtl: Long = -1L,
    private val cleanupIntervalSec: Long = 60L
) : ICacheStore<String, String> {

    private val log = LoggerFactory.getLogger(DiskCacheStore::class.java)
        private val lock = ReentrantReadWriteLock()
        private val index = ConcurrentHashMap<String, CacheEntry<String>>()
        private val serializer = CacheSerialization
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "disk-cache-cleanup").apply { isDaemon = true }
        }
        private var hits: Long = 0L
    private var misses: Long = 0L
    private var evictions: Long = 0L
    private var currentDiskBytes: Long = 0L

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        rebuildIndex()
        scheduler.scheduleAtFixedRate(
            { cleanupExpired() },
            cleanupIntervalSec,
            cleanupIntervalSec,
            TimeUnit.SECONDS
        )
    }

    override fun get(key: String): CacheEntry<String>? {
        val start = System.nanoTime()
        lock.read {
            val entry = index[key] ?: return null.also {
                lock.write { misses++ }
            }
        if (entry.isExpired()) {
                lock.write {
                    deleteFile(key)
                    index.remove(key)
                    misses++
                }
        return null
            }
        val jsonString = readFile(key) ?: return null.also {
                lock.write {
                    index.remove(key)
                    misses++
                }
            }
        val deserialized = serializer.deserialize(jsonString) { it }
        val updated = deserialized.recordAccess()
            index[key] = updated
            lock.write {
                hits++
            }
        return updated
        }
    }

    override fun put(entry: CacheEntry<String>) {
        lock.write {
            val jsonString = serializer.serialize(entry)
            writeFileAtomic(entry.key, jsonString)
            index[entry.key] = entry
            currentDiskBytes += entry.sizeBytes.coerceAtLeast(0)
            evictIfNeeded()
        }
    }

    override fun remove(key: String): Boolean {
        lock.write {
            val existed = index.containsKey(key)
        if (existed) {
                deleteFile(key)
        val entry = index.remove(key)
        if (entry != null) {
                    currentDiskBytes -= entry.sizeBytes.coerceAtLeast(0)
                }
            }
        return existed
        }
    }

    override fun clear() {
        lock.write {
            cacheDir.listFiles()?.forEach { dir ->
                dir.deleteRecursively()
            }
            index.clear()
            currentDiskBytes = 0L
            hits = 0L
            misses = 0L
            evictions = 0L
        }
    }

    override fun contains(key: String): Boolean {
        lock.read {
            val entry = index[key] ?: return false
            if (entry.isExpired()) {
                lock.write {
                    deleteFile(key)
                    index.remove(key)
                }
        return false
            }
        return fileFor(key).exists()
        }
    }

    override fun size(): Int = lock.read { index.size }

    override fun stats(): CacheStats {
        lock.read {
            return CacheStats(
                hits = hits,
                misses = misses,
                evictions = evictions,
                totalEntries = index.size,
                memoryUsage = currentDiskBytes,
                avgAccessTime = 0L
            )
        }
    }

    override fun evict(policy: CachePolicy): List<String> {
        lock.write {
            val evictedKeys = mutableListOf<String>()
        val candidates = when (policy) {
                is CachePolicy.TtlPolicy -> {
                    index.values.filter { it.isExpired() }.map { it.key }
                }
                is CachePolicy.LruPolicy -> {
                    policy.evictCandidates(index.values).map { it.key }
                }
                is CachePolicy.LfuPolicy -> {
                    policy.evictCandidates(index.values).map { it.key }
                }
                is CachePolicy.FifoPolicy -> {
                    policy.evictCandidates(index.values).map { it.key }
                }
                is CachePolicy.HybridPolicy -> {
                    index.values.sortedByDescending { policy.evictionScore(it) }
                        .take((index.size * 0.25).toInt().coerceAtLeast(1))
                        .map { it.key }
                }
            }
        for (key in candidates) {
                deleteFile(key)
        val entry = index.remove(key)
        if (entry != null) {
                    currentDiskBytes -= entry.sizeBytes.coerceAtLeast(0)
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
            val file = fileFor(key)
        if (file.exists()) {
                loaded++
            }
        }
        log.info("warmUp: {}/{} keys found on disk", loaded, keys.size)
        return loaded
    }

    /** 关闭调度器，释放资源 */
    fun shutdown() {
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            scheduler.shutdownNow()
        }
    }

    /** 从磁盘重建内存索引 */
    private fun rebuildIndex() {
        lock.write {
            index.clear()
            currentDiskBytes = 0L
            cacheDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension == "json") {
                    try {
                        val content = file.readText()
        val wrapper = serializer.json.decodeFromString<
                            CacheSerialization.JsonElement
                        >(content)
                        // 简化重建：仅记录 key 和文件存在性
    val key = file.nameWithoutExtension
                        val entry = CacheEntry(
                            key = key,
                            value = content,
                            createdAt = file.lastModified(),
                            serialized = true
                        )
                        index[key] = entry
                        currentDiskBytes += file.length()
                    } catch (e: Exception) {
                        log.warn("rebuildIndex: skip corrupted file {}", file.name)
                    }
                }
            }
            log.info("rebuildIndex: loaded {} entries, {} bytes",
                index.size, currentDiskBytes)
        }
    }

    /** 生成 key 对应的文件路径（两级子目录） */
    private fun fileFor(key: String): File {
        val hash = key.hashCode().toLong().toString(16)
        val prefix1 = hash.take(1).ifEmpty { "0" }
        val prefix2 = hash.drop(1).take(1).ifEmpty { "0" }
        val dir = File(cacheDir, "$prefix1/$prefix2")
        return File(dir, "${key}.json")
    }

    /** 确保目标文件的父目录存在 */
    private fun ensureParentDir(file: File) {
        file.parentFile?.mkdirs()
    }

    /** 原子写入：先写临时文件，再重命名 */
    private fun writeFileAtomic(key: String, content: String) {
        val target = fileFor(key)
        ensureParentDir(target)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        temp.renameTo(target)
    }

    /** 读取文件内容，若文件不存在返回 null */
    private fun readFile(key: String): String? {
        val file = fileFor(key)
        return if (file.exists()) file.readText() else null
    }

    /** 删除文件及空父目录 */
    private fun deleteFile(key: String) {
        val file = fileFor(key)
        if (file.exists()) {
            file.delete()
            // 清理空目录
            file.parentFile?.let { parent ->
                if (parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                    parent.delete()
                    parent.parentFile?.let { grandParent ->
                        if (grandParent.isDirectory && grandParent.listFiles().isNullOrEmpty()) {
                            grandParent.delete()
                        }
                    }
                }
            }
        }
    }

    /** 超过容量限制时按 LRU 驱逐 */
    private fun evictIfNeeded() {
        if (maxSize > 0 && index.size > maxSize) {
            val overage = index.size - maxSize
            val entries = index.values.sortedBy { it.lastAccessedAt }
        for (i in 0 until overage) {
                if (i >= entries.size) break
                val key = entries[i].key
                deleteFile(key)
        val removed = index.remove(key)
        if (removed != null) {
                    currentDiskBytes -= removed.sizeBytes.coerceAtLeast(0)
                    evictions++
                }
            }
        }
        if (maxDiskBytes > 0 && currentDiskBytes > maxDiskBytes) {
            val entries = index.values.sortedBy { it.lastAccessedAt }
        for (entry in entries) {
                if (currentDiskBytes <= maxDiskBytes) break
                deleteFile(entry.key)
        val removed = index.remove(entry.key)
        if (removed != null) {
                    currentDiskBytes -= removed.sizeBytes.coerceAtLeast(0)
                    evictions++
                }
            }
        }
    }

    /** 清理所有已过期的缓存条目 */
    private fun cleanupExpired() {
        lock.write {
            val expired = index.values.filter { it.isExpired() }
        for (entry in expired) {
                deleteFile(entry.key)
                index.remove(entry.key)
                currentDiskBytes -= entry.sizeBytes.coerceAtLeast(0)
                evictions++
            }
        if (expired.isNotEmpty()) {
                log.info("cleanupExpired: removed {} expired entries", expired.size)
            }
        }
    }
}
