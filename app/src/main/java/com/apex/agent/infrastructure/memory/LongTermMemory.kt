package com.apex.agent.infrastructure.memory

import com.apex.agent.domain.interfaces.IMemoryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 长期记忆存储 - 带JSON文件持久化
 *
 * 记忆以JSON格式存储在应用内部存储中，
 * 进程重启后自动恢复。
 * 支持 TTL 过期淘汰和 LRU 容量淘汰。
 *
 * @param storageDir 存储目录，用于存放持久化JSON文件
 * @param maxEntries 最大条目数，超过时按 LRU 淘汰
 */

    @Serializable

    @Serializable
    private data class PersistenceData(
        val entries: List<MemoryEntry>
    )

    companion object {
        private const val TAG = "LongTermMemory"
        private const val FILE_NAME = "long_term_memory.json"
        private const val TMP_SUFFIX = ".tmp"
    }

    private val lock = ReentrantReadWriteLock()
    private val store = ConcurrentHashMap<String, MemoryEntry>()
    private val accessOrder = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = false
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

    override fun put(key: String, value: String, ttl: Long?) {
        val now = System.currentTimeMillis()
        val entry = MemoryEntry(
            key = key,
            value = value,
            timestamp = now,
            ttl = ttl
        )
        lock.write {
            store[key] = entry
            accessOrder[key] = now
            evictIfNeeded()
            syncToDisk()
        }
    }

    override fun get(key: String): String? {
        lock.read {
            val entry = store[key] ?: return null
            if (isExpired(entry)) {
                lock.write {
                    store.remove(key)
                    accessOrder.remove(key)
                    syncToDisk()
                }
                return null
            }
            accessOrder[key] = System.currentTimeMillis()
            return entry.value
        }
    }

    override fun remove(key: String): Boolean {
        lock.write {
            val removed = store.remove(key) != null
            accessOrder.remove(key)
            if (removed) syncToDisk()
            return removed
        }
    }

    override fun clear() {
        lock.write {
            store.clear()
            accessOrder.clear()
            syncToDisk()
        }
    }

    override fun keys(): Set<String> {
        lock.read {
            val now = System.currentTimeMillis()
            val expired = store.filterValues { isExpired(it, now) }.keys
            if (expired.isNotEmpty()) {
                lock.write {
                    expired.forEach { key ->
                        store.remove(key)
                        accessOrder.remove(key)
                    }
                    if (expired.isNotEmpty()) syncToDisk()
                }
            }
            return store.keys.toSet()
        }
    }

    override fun size(): Int {
        lock.read { return store.size }
    }

    override fun contains(key: String): Boolean {
        lock.read {
            val entry = store[key] ?: return false
            if (isExpired(entry)) {
                lock.write {
                    store.remove(key)
                    accessOrder.remove(key)
                    syncToDisk()
                }
                return false
            }
            return true
        }
    }

    /** 判断条目是否过期 */
    private fun isExpired(entry: MemoryEntry, now: Long = System.currentTimeMillis()): Boolean {
        val ttl = entry.ttl ?: return false
        return (now - entry.timestamp) > ttl
    }

    /** 容量超标时按访问顺序淘汰最旧条目 */
    private fun evictIfNeeded() {
        while (store.size > maxEntries) {
            val eldest = accessOrder.entries.firstOrNull() ?: break
            store.remove(eldest.key)
            accessOrder.remove(eldest.key)
        }
    }

    /** 将数据原子写入磁盘：先写临时文件，再重命名 */
    private fun syncToDisk() {
        try {
            val data = PersistenceData(store.values.toList())
            val content = json.encodeToString(data)
            val tmpFile = File(storageDir, "$FILE_NAME$TMP_SUFFIX")
            tmpFile.writeText(content, Charsets.UTF_8)
            tmpFile.renameTo(persistenceFile)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "持久化写入失败", e)
        }
    }

    /** 从磁盘加载数据，JSON损坏时自动恢复为空 */
    private fun loadFromDisk() {
        try {
            val file = persistenceFile
            if (!file.exists()) return
            val content = file.readText(Charsets.UTF_8)
            val data = json.decodeFromString<PersistenceData>(content)
            val now = System.currentTimeMillis()
            data.entries.forEach { entry ->
                if (!isExpired(entry, now)) {
                    store[entry.key] = entry
                    accessOrder[entry.key] = entry.timestamp
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "加载持久化数据失败，使用空存储", e)
            store.clear()
            accessOrder.clear()
            persistenceFile.delete()
        }
    }

    /** 强制立即持久化 */
    fun flush() {
        lock.read { syncToDisk() }
    }
}
