package com.apex.agent.infrastructure.memory

import com.apex.agent.domain.interfaces.IVectorStore
import com.apex.agent.domain.interfaces.VectorResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.sqrt

/**
 * 向量记忆存储 - 带JSON文件持久化的向量检索
 *
 * 存储浮点向量并支持余弦相似度搜索。
 * 使用平面索引（适合中小规模），写入时持久化到JSON文件。
 * 支持 TTL 过期淘汰和 LRU 搜索结果缓存。
 *
 * @param storageDir 存储目录
 * @param dimension 向量维度，默认 768
 * @param maxVectors 最大向量数，超过时按添加顺序淘汰最旧条目
 * @param searchCacheSize LRU 搜索缓存大小
 */
class VectorMemory(
    private val storageDir: File,
    private val dimension: Int = 768,
    private val maxVectors: Int = 10000,
    private val searchCacheSize: Int = 100
) : IVectorStore {

    @Serializable
    data class VectorEntry(
        val id: String,
        val vector: List<Float>,
        val metadata: Map<String, String> = emptyMap(),
        val timestamp: Long,
        val ttl: Long? = null
    )

    @Serializable
    private data class PersistenceData(
        val entries: List<VectorEntry>
    )

    @Serializable
    private data class SearchCacheEntry(
        val results: List<SerializableVectorResult>
    )

    @Serializable
    private data class SerializableVectorResult(
        val id: String,
        val score: Float,
        val metadata: Map<String, String> = emptyMap()
    )

    companion object {
        private const val TAG = "VectorMemory"
        private const val FILE_NAME = "vector_memory.json"
        private const val TMP_SUFFIX = ".tmp"
    }

    private val lock = ReentrantReadWriteLock()
    private val store = ConcurrentHashMap<String, VectorEntry>()
    private val insertionOrder = linkedMapOf<String, Long>()

    private val searchCache = object : LinkedHashMap<String, List<VectorResult>>(searchCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<VectorResult>>): Boolean {
            return size > searchCacheSize
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val persistenceFile: File
        get() = File(storageDir, FILE_NAME)

    init {
        storageDir.mkdirs()
        loadFromDisk()
    }

    override fun add(id: String, vector: FloatArray, metadata: Map<String, String>) {
        require(vector.size == dimension) { "向量维度 $dimension 与实际 ${vector.size} 不匹配" }
        val entry = VectorEntry(
            id = id,
            vector = vector.toList(),
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        )
        lock.write {
            store[id] = entry
            insertionOrder.remove(id)
            insertionOrder[id] = System.currentTimeMillis()
            evictIfNeeded()
            searchCache.clear()
            syncToDisk()
        }
    }

    override fun remove(id: String): Boolean {
        lock.write {
            val removed = store.remove(id) != null
            insertionOrder.remove(id)
            if (removed) {
                searchCache.clear()
                syncToDisk()
            }
            return removed
        }
    }

    override fun search(query: FloatArray, topK: Int): List<VectorResult> {
        val cacheKey = buildCacheKey(query, topK)
        lock.read {
            searchCache[cacheKey]?.let { return it }
        }
        lock.write {
            searchCache[cacheKey]?.let { return it }
            val now = System.currentTimeMillis()
            val results = store.entries
                .mapNotNull { (id, entry) ->
                    if (isExpired(entry, now)) {
                        store.remove(id)
                        insertionOrder.remove(id)
                        return@mapNotNull null
                    }
                    val score = cosineSimilarity(query, entry.vector.toFloatArray())
                    VectorResult(
                        id = id,
                        score = score,
                        metadata = entry.metadata
                    )
                }
                .sortedByDescending { it.score }
                .take(topK)
            searchCache[cacheKey] = results
            return results
        }
    }

    override fun clear() {
        lock.write {
            store.clear()
            insertionOrder.clear()
            searchCache.clear()
            syncToDisk()
        }
    }

    override fun size(): Int {
        lock.read { return store.size }
    }

    /** 判断条目是否过期 */
    private fun isExpired(entry: VectorEntry, now: Long = System.currentTimeMillis()): Boolean {
        val ttl = entry.ttl ?: return false
        return (now - entry.timestamp) > ttl
    }

    /** 超出容量时按插入顺序淘汰最旧条目 */
    private fun evictIfNeeded() {
        while (store.size > maxVectors) {
            val eldest = insertionOrder.entries.firstOrNull() ?: break
            store.remove(eldest.key)
            insertionOrder.remove(eldest.key)
        }
    }

    /** 计算余弦相似度 */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    /** 构建搜索缓存键 */
    private fun buildCacheKey(query: FloatArray, topK: Int): String {
        val hash = query.contentHashCode()
        return "${hash}_${topK}_${dimension}"
    }

    /** 持久化到磁盘 */
    private fun syncToDisk() {
        try {
            val data = PersistenceData(store.values.toList())
            val content = json.encodeToString(data)
            val tmpFile = File(storageDir, "$FILE_NAME$TMP_SUFFIX")
            tmpFile.writeText(content, Charsets.UTF_8)
            tmpFile.renameTo(persistenceFile)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "向量持久化写入失败", e)
        }
    }

    /** 从磁盘加载 */
    private fun loadFromDisk() {
        try {
            val file = persistenceFile
            if (!file.exists()) return
            val content = file.readText(Charsets.UTF_8)
            val data = json.decodeFromString<PersistenceData>(content)
            val now = System.currentTimeMillis()
            data.entries.forEach { entry ->
                if (!isExpired(entry, now)) {
                    store[entry.id] = entry
                    insertionOrder[entry.id] = entry.timestamp
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "加载向量持久化数据失败，使用空存储", e)
            store.clear()
            insertionOrder.clear()
            persistenceFile.delete()
        }
    }

    /** 强制立即持久化 */
    fun flush() {
        lock.read { syncToDisk() }
    }
}
