package com.apex.lib.workingfiles.snapshot

import com.apex.sdk.common.ApexLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 文件快照存储 — 基于 JSON 文件持久化。
 *
 * **存储结构**：
 *   ```
 *   <storageDir>/
 *   ├── index.json                              # 路径 → 快照 ID 列表 的索引
 *   └── snapshots/
 *       ├── snap-xxx-xxxxxxxx.json              # 单个快照全文
 *       ├── snap-yyy-yyyyyyyy.json
 *       └── ...
 *   ```
 *
 * **为什么不用 Room / SQLite**：
 *   - 避免引入 kapt 增加构建复杂度
 *   - 文件数通常 < 1000，JSON 完全够用
 *   - 跨 APK 共享简单（所有 APK 同 UID，文件可读写）
 *
 * **并发安全**：
 *   - 用 [ReentrantReadWriteLock] 保护 index
 *   - 每个快照文件写入用原子写（写临时文件后 rename）
 *
 * **保留策略**：
 *   - 默认每个文件保留最近 100 个快照
 *   - 超过限制时自动删除最旧的（可配置）
 */
class SnapshotStorage(
    private val storageDir: File,
    private val maxSnapshotsPerFile: Int = 100
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val indexFile = File(storageDir, "index.json")
    private val snapshotsDir = File(storageDir, "snapshots").apply { mkdirs() }

    private val lock = ReentrantReadWriteLock()
    private val pathToSnapshotIds = ConcurrentHashMap<String, MutableList<String>>()

    init {
        loadIndex()
    }

    /**
     * 保存一个快照。
     * @return 保存成功返回快照 ID，失败返回 null
     */
    fun save(snapshot: FileSnapshot): Boolean {
        return try {
            // 1. 写快照文件（原子写）
            val snapshotFile = File(snapshotsDir, "${snapshot.id}.json")
            val tmpFile = File(snapshotsDir, "${snapshot.id}.json.tmp")
            tmpFile.writeText(json.encodeToString(snapshot))
            tmpFile.renameTo(snapshotFile)

            // 2. 更新索引
            lock.write {
                val list = pathToSnapshotIds.getOrPut(snapshot.filePath) { mutableListOf() }
                list.add(snapshot.id)
                // 应用保留策略
                while (list.size > maxSnapshotsPerFile) {
                    val oldestId = list.removeAt(0)
                    File(snapshotsDir, "$oldestId.json").delete()
                }
            }
            persistIndex()
            true
        } catch (t: Throwable) {
            ApexLog.e("working-files", "[SnapshotStorage] save failed: ${t.message}")
            false
        }
    }

    /**
     * 加载一个快照。
     */
    fun load(snapshotId: String): FileSnapshot? {
        return try {
            val file = File(snapshotsDir, "$snapshotId.json")
            if (!file.exists()) return null
            json.decodeFromString(FileSnapshot.serializer(), file.readText())
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[SnapshotStorage] load failed: $snapshotId, ${t.message}")
            null
        }
    }

    /**
     * 列出某文件的所有快照（按时间升序）。
     */
    fun listSnapshots(filePath: String): List<FileSnapshot> {
        return lock.read {
            val ids = pathToSnapshotIds[filePath] ?: return emptyList()
            ids.mapNotNull { load(it) }.sortedBy { it.timestamp }
        }
    }

    /**
     * 列出某文件的所有快照摘要（不含全文，节省内存）。
     */
    fun listSnapshotSummaries(filePath: String): List<SnapshotSummary> {
        return lock.read {
            val ids = pathToSnapshotIds[filePath] ?: return emptyList()
            ids.mapNotNull { id ->
                load(id)?.toSummary()
            }.sortedBy { it.timestamp }
        }
    }

    /**
     * 获取某文件的最新快照。
     */
    fun getLatestSnapshot(filePath: String): FileSnapshot? {
        return listSnapshots(filePath).lastOrNull()
    }

    /**
     * 删除某快照。
     */
    fun delete(snapshotId: String): Boolean {
        return try {
            lock.write {
                pathToSnapshotIds.values.forEach { it.remove(snapshotId) }
            }
            File(snapshotsDir, "$snapshotId.json").delete()
            persistIndex()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 删除某文件的所有快照。
     */
    fun deleteAllForFile(filePath: String): Int {
        return lock.write {
            val ids = pathToSnapshotIds.remove(filePath) ?: return@write 0
            ids.forEach { id ->
                File(snapshotsDir, "$id.json").delete()
            }
            persistIndex()
            ids.size
        }
    }

    /**
     * 清空所有快照。
     */
    fun clear() {
        lock.write {
            pathToSnapshotIds.clear()
            snapshotsDir.listFiles()?.forEach { it.delete() }
            indexFile.delete()
        }
    }

    /**
     * 列出所有有快照的文件路径。
     */
    fun listAllFilePaths(): List<String> = lock.read { pathToSnapshotIds.keys.toList() }

    /**
     * 获取所有文件的快照统计。
     */
    fun getStats(): SnapshotStorageStats {
        return lock.read {
            val totalSnapshots = pathToSnapshotIds.values.sumOf { it.size }
            val totalSizeBytes = snapshotsDir.listFiles()?.sumOf { it.length() } ?: 0L
            SnapshotStorageStats(
                fileCount = pathToSnapshotIds.size,
                totalSnapshots = totalSnapshots,
                totalSizeBytes = totalSizeBytes
            )
        }
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val data = json.decodeFromString<Map<String, List<String>>>(
                indexFile.readText()
            )
            data.forEach { (path, ids) ->
                pathToSnapshotIds[path] = ids.toMutableList()
            }
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[SnapshotStorage] loadIndex failed: ${t.message}")
        }
    }

    private fun persistIndex() {
        try {
            val data = lock.read {
                pathToSnapshotIds.mapValues { it.value.toList() }
            }
            val tmpFile = File(storageDir, "index.json.tmp")
            tmpFile.writeText(json.encodeToString(data))
            tmpFile.renameTo(indexFile)
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[SnapshotStorage] persistIndex failed: ${t.message}")
        }
    }
}

/** 快照摘要 — 不含全文，节省内存。 */
data class SnapshotSummary(
    val id: String,
    val filePath: String,
    val relativePath: String,
    val timestamp: Long,
    val contentHash: String,
    val changeType: ChangeType,
    val source: ChangeSource,
    val agentId: String?,
    val sessionId: String?,
    val stepId: String?,
    val description: String,
    val lineCount: Int,
    val charCount: Int
)

/** 转 summary */
fun FileSnapshot.toSummary() = SnapshotSummary(
    id = id,
    filePath = filePath,
    relativePath = relativePath,
    timestamp = timestamp,
    contentHash = contentHash,
    changeType = changeType,
    source = source,
    agentId = agentId,
    sessionId = sessionId,
    stepId = stepId,
    description = description,
    lineCount = lineCount,
    charCount = charCount
)

/** 存储统计。 */
data class SnapshotStorageStats(
    val fileCount: Int,
    val totalSnapshots: Int,
    val totalSizeBytes: Long
) {
    val totalSizeMb: Double get() = totalSizeBytes / 1024.0 / 1024.0
}
