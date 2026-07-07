package com.apex.agent.domain.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Burst模式状态持久化存储
 *
 * 将Burst任务状态持久化到本地JSON文件，
 * 进程重启后可恢复。
 *
 * @param storageDir 持久化存储目录
 */
class BurstStateStore(private val storageDir: File) {

    companion object {
        private const val STATE_FILE_PREFIX = "burst_state_"
        private const val CHECKPOINT_FILE_PREFIX = "burst_checkpoint_"
        private const val STATE_FILE_SUFFIX = ".json"
        private const val INDEX_FILE = "burst_state_index.json"
        private const val MAX_RECENT_EXECUTIONS = 100
        private const val MAX_STATE_AGE_DAYS = 7
    }

    /**
     * 持久化状态条目
     */
    data class StateEntry(
        val taskId: String,
        val state: String,
        val progress: Float,
        val timestamp: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * 持久化检查点
     */
    data class CheckpointEntry(
        val taskId: String,
        val checkpointId: String,
        val state: Map<String, Any>,
        val timestamp: Long
    )

    private val indexCache = ConcurrentHashMap<String, StateEntry>()
    private val checkpointCache = ConcurrentHashMap<String, CheckpointEntry>()

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        ensureIndexFile()
    }

    /**
     * 保存Burst任务状态
     */
    suspend fun saveState(entry: StateEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            val stateFile = getStateFile(entry.taskId)
            val json = buildStateJson(entry)
            stateFile.writeText(json)
            indexCache[entry.taskId] = entry
            updateIndex(entry)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 恢复Burst任务状态
     */
    suspend fun restoreState(taskId: String): StateEntry? = withContext(Dispatchers.IO) {
        indexCache[taskId] ?: run {
            val stateFile = getStateFile(taskId)
            if (stateFile.exists()) {
                try {
                    val json = stateFile.readText()
                    parseStateJson(json)
                } catch (_: Exception) {
                    null
                }
            } else null
        }
    }

    /**
     * 保存检查点
     */
    suspend fun saveCheckpoint(entry: CheckpointEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            val cpFile = getCheckpointFile(entry.taskId, entry.checkpointId)
            val json = buildCheckpointJson(entry)
            cpFile.writeText(json)
            checkpointCache["${entry.taskId}_${entry.checkpointId}"] = entry
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 恢复最新的检查点
     */
    suspend fun restoreCheckpoint(taskId: String): CheckpointEntry? = withContext(Dispatchers.IO) {
        val cpDir = File(storageDir, CHECKPOINT_FILE_PREFIX + taskId)
        if (!cpDir.exists()) return@withContext null

        val latestFile = cpDir.listFiles()
            ?.filter { it.name.endsWith(STATE_FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?: return@withContext null

        try {
            val json = latestFile.readText()
            parseCheckpointJson(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 列出最近执行记录
     */
    suspend fun listRecentExecutions(limit: Int = 20): List<StateEntry> = withContext(Dispatchers.IO) {
        val indexFile = getIndexFile()
        if (!indexFile.exists()) return@withContext emptyList()

        try {
            val json = indexFile.readText()
            parseIndexJson(json)
                .sortedByDescending { it.timestamp }
                .take(limit.coerceAtMost(MAX_RECENT_EXECUTIONS))
        } catch (_: Exception) {
            indexCache.values.toList()
                .sortedByDescending { it.timestamp }
                .take(limit.coerceAtMost(MAX_RECENT_EXECUTIONS))
        }
    }

    /**
     * 清理过期状态（超过MAX_STATE_AGE_DAYS的自动删除）
     */
    suspend fun cleanUpOldStates(): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (MAX_STATE_AGE_DAYS * 24 * 60 * 60 * 1000L)
        var deleted = 0

        storageDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(STATE_FILE_PREFIX) && file.name.endsWith(STATE_FILE_SUFFIX)) {
                if (file.lastModified() < cutoff) {
                    file.delete()
                    deleted++
                }
            }
        }

        // 清理过期的检查点目录
        storageDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith(CHECKPOINT_FILE_PREFIX)) {
                if (file.lastModified() < cutoff) {
                    file.deleteRecursively()
                    deleted++
                }
            }
        }

        // 清理缓存
        indexCache.entries.removeAll { it.value.timestamp < cutoff }
        checkpointCache.entries.removeAll { it.value.timestamp < cutoff }

        deleted
    }

    /**
     * 获取存储目录大小（字节）
     */
    suspend fun getStorageSizeBytes(): Long = withContext(Dispatchers.IO) {
        storageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 获取状态文件
     */
    private fun getStateFile(taskId: String): File {
        return File(storageDir, "$STATE_FILE_PREFIX${sanitizeFileName(taskId)}$STATE_FILE_SUFFIX")
    }

    /**
     * 获取检查点文件
     */
    private fun getCheckpointFile(taskId: String, checkpointId: String): File {
        val cpDir = File(storageDir, "${CHECKPOINT_FILE_PREFIX}$taskId")
        if (!cpDir.exists()) cpDir.mkdirs()
        return File(cpDir, "${sanitizeFileName(checkpointId)}$STATE_FILE_SUFFIX")
    }

    /**
     * 获取索引文件
     */
    private fun getIndexFile(): File = File(storageDir, INDEX_FILE)

    /**
     * 确保索引文件存在
     */
    private fun ensureIndexFile() {
        val indexFile = getIndexFile()
        if (!indexFile.exists()) {
            try {
                indexFile.writeText("[]")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 更新索引文件
     */
    private fun updateIndex(entry: StateEntry) {
        try {
            val indexFile = getIndexFile()
            val existing = if (indexFile.exists()) {
                try {
                    parseIndexJson(indexFile.readText())
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            val updated = (existing + entry)
                .groupBy { it.taskId }
                .mapValues { it.value.last() }
                .values
                .takeLast(MAX_RECENT_EXECUTIONS)

            indexFile.writeText(buildIndexJson(updated))
        } catch (_: Exception) {
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    // ========== JSON序列化/反序列化（简单实现，无三方依赖） ==========

    private fun buildStateJson(entry: StateEntry): String {
        val metaJson = entry.metadata.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return """{
  "taskId": "${escapeJson(entry.taskId)}",
  "state": "${escapeJson(entry.state)}",
  "progress": ${entry.progress},
  "timestamp": ${entry.timestamp},
  "metadata": {$metaJson}
}"""
    }

    private fun parseStateJson(json: String): StateEntry? {
        return try {
            val taskId = extractJsonValue(json, "taskId") ?: return null
            val state = extractJsonValue(json, "state") ?: ""
            val progress = extractJsonDouble(json, "progress") ?: 0.0
            val timestamp = extractJsonLong(json, "timestamp") ?: 0L
            val metadata = extractJsonMap(json, "metadata")
            StateEntry(taskId, state, progress.toFloat(), timestamp, metadata)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCheckpointJson(entry: CheckpointEntry): String {
        val stateJson = entry.state.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v.toString())}\""
        }
        return """{
  "taskId": "${escapeJson(entry.taskId)}",
  "checkpointId": "${escapeJson(entry.checkpointId)}",
  "state": {$stateJson},
  "timestamp": ${entry.timestamp}
}"""
    }

    private fun parseCheckpointJson(json: String): CheckpointEntry? {
        return try {
            val taskId = extractJsonValue(json, "taskId") ?: return null
            val checkpointId = extractJsonValue(json, "checkpointId") ?: return null
            val state = extractJsonMap(json, "state")
            val timestamp = extractJsonLong(json, "timestamp") ?: 0L
            CheckpointEntry(taskId, checkpointId, state, timestamp)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIndexJson(entries: List<StateEntry>): String {
        val entriesJson = entries.joinToString(",\n") { buildStateJson(it) }
        return "[\n$entriesJson\n]"
    }

    private fun parseIndexJson(json: String): List<StateEntry> {
        val entries = mutableListOf<StateEntry>()
        // 简单解析JSON数组
        val arrayContent = json.trimStart().trimEnd()
            .removePrefix("[").removeSuffix("]")
            .trim()
        if (arrayContent.isEmpty()) return emptyList()

        var depth = 0
        var start = 0
        for (i in arrayContent.indices) {
            when (arrayContent[i]) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    val objStr = arrayContent.substring(start, i).trim()
                    parseStateJson(objStr)?.let { entries.add(it) }
                    start = i + 1
                }
            }
        }
        val lastObj = arrayContent.substring(start).trim()
        if (lastObj.isNotBlank()) {
            parseStateJson(lastObj)?.let { entries.add(it) }
        }
        return entries
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = "\"${escapeRegex(key)}\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonDouble(json: String, key: String): Double? {
        val regex = "\"${escapeRegex(key)}\"\\s*:\\s*([\\d.]+)".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val regex = "\"${escapeRegex(key)}\"\\s*:\\s*(\\d+)".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun extractJsonMap(json: String, key: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = "\"${escapeRegex(key)}\"\\s*:\\s*\\{([^}]*)\\}".toRegex()
        val match = regex.find(json) ?: return map
        val content = match.groupValues.getOrNull(1) ?: return map
        val entryRegex = "\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        entryRegex.findAll(content).forEach { m ->
            val k = m.groupValues.getOrNull(1) ?: return@forEach
            val v = m.groupValues.getOrNull(2) ?: ""
            map[k] = v
        }
        return map
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun escapeRegex(s: String): String {
        return Regex.escape(s)
    }
}
