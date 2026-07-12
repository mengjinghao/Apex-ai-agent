package com.apex.agent.kernel.burst

import android.app.Application
import android.util.Log
import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.IBurstStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Burst状态管理器 - 持久化增强版
 *
 * 持久化策略：
 * - 任务（BurstTask）：每个任务一个 JSON 文件，存于 tasks/ 子目录
 * - 检查点（Checkpoint）：每个 taskId 一个 JSON 文件，内含 NavigableMap<checkpoint, state>
 *   修复旧版 key 拼接 "${taskId}_$checkpoint" 在 taskId 含下划线时的解析歧义
 * - 日志（ExecutionLog）：每个 taskId 一个 JSON 文件，按行追加；内存 cache 保留最近 N 条
 *
 * 所有写操作先更新内存 cache 再异步落盘，读操作优先走内存 cache。
 */
class BurstStateManager(app: Application) : IBurstStateManager {
    companion object {
        private const val TAG = "BurstStateManager"
        private const val STORAGE_DIR_NAME = "burst_state"
        private const val TASKS_SUBDIR = "tasks"
        private const val CHECKPOINTS_SUBDIR = "checkpoints"
        private const val LOGS_SUBDIR = "logs"
        private const val MAX_INMEMORY_LOGS_PER_TASK = 200
        private const val RECENT_EXECUTIONS_DEFAULT_LIMIT = 20
        private const val STATE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 天
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val rootDir = File(app.filesDir, STORAGE_DIR_NAME).also { it.mkdirs() }
    private val tasksDir = File(rootDir, TASKS_SUBDIR).also { it.mkdirs() }
    private val checkpointsDir = File(rootDir, CHECKPOINTS_SUBDIR).also { it.mkdirs() }
    private val logsDir = File(rootDir, LOGS_SUBDIR).also { it.mkdirs() }

    // 内存 cache —— 热路径读取直接命中，避免每次都走 IO
    private val taskCache = ConcurrentHashMap<String, BurstTask>()
    private val checkpointCache = ConcurrentHashMap<String, MutableMap<Int, Map<String, Any>>>()
    private val logCache = ConcurrentHashMap<String, ConcurrentLinkedQueue<ExecutionLog>>()

    // 简单的 Any → JSON 序列化适配（checkpoint state 是 Map<String, Any>）
    private val checkpointStateSerializer =
        MapSerializer(String.serializer(), JsonElement.serializer())

    init {
        // 启动时预热：把磁盘上现有 tasks 索引加载到内存（懒加载亦可，但预热可让 getRecentExecutions 立即可用）
        warmUpTaskIndex()
    }

    private fun warmUpTaskIndex() {
        runCatching {
            tasksDir.listFiles { f -> f.isFile && f.extension == "json" }
                ?.forEach { file ->
                    runCatching {
                        val task = json.decodeFromString(BurstTask.serializer(), file.readText())
                        taskCache[task.id] = task
                    }.onFailure {
                        Log.w(TAG, "Failed to warm up task from ${file.name}: ${it.message}")
                    }
                }
        }.onFailure {
            Log.w(TAG, "warmUpTaskIndex failed: ${it.message}")
        }
    }

    override suspend fun saveTask(task: BurstTask) {
        // 先更新内存 cache，再异步落盘
        taskCache[task.id] = task
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(tasksDir, "${task.id}.json")
                file.writeText(json.encodeToString(BurstTask.serializer(), task))
            }.onFailure {
                Log.e(TAG, "saveTask failed for ${task.id}: ${it.message}")
            }
        }
    }

    override suspend fun loadTask(taskId: String): BurstTask? {
        // 内存命中优先
        taskCache[taskId]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(tasksDir, "$taskId.json")
                if (!file.exists()) return@withContext null
                val task = json.decodeFromString(BurstTask.serializer(), file.readText())
                taskCache[taskId] = task
                task
            }.onFailure {
                Log.e(TAG, "loadTask failed for $taskId: ${it.message}")
                null
            }.getOrNull()
        }
    }

    override suspend fun saveCheckpoint(taskId: String, checkpoint: Int, state: Map<String, Any>) {
        // 用 sub-map 结构避免旧版 "${taskId}_$checkpoint" 拼接在 taskId 含下划线时的歧义
        val map = checkpointCache.computeIfAbsent(taskId) { java.util.concurrent.ConcurrentSkipListMap() }
        map[checkpoint] = state
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(checkpointsDir, "$taskId.json")
                // 文件格式：{ "<checkpoint>": { "<key>": <jsonValue>, ... }, ... }
                // 这样多个 checkpoint 共用一个文件，loadCheckpoint 取最大的 key
                val existing = if (file.exists()) {
                    runCatching {
                        json.decodeFromString(checkpointStateSerializer, file.readText())
                    }.getOrDefault(emptyMap())
                } else emptyMap()
                val merged = existing.toMutableMap()
                merged[checkpoint.toString()] = state.toJsonObject()
                file.writeText(json.encodeToString(checkpointStateSerializer, merged))
            }.onFailure {
                Log.e(TAG, "saveCheckpoint failed for $taskId: ${it.message}")
            }
        }
    }

    override suspend fun loadCheckpoint(taskId: String): Map<String, Any>? {
        // 内存命中优先，取最大 checkpoint
        checkpointCache[taskId]?.let { map ->
            map.keys.maxOrNull()?.let { maxKey -> return map[maxKey] }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(checkpointsDir, "$taskId.json")
                if (!file.exists()) return@withContext null
                val raw = json.decodeFromString(checkpointStateSerializer, file.readText())
                // 取 checkpoint 编号最大的那一条
                val maxKey = raw.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return@withContext null
                val element = raw[maxKey.toString()] ?: return@withContext null
                element.toAnyMap()
            }.onFailure {
                Log.e(TAG, "loadCheckpoint failed for $taskId: ${it.message}")
                null
            }.getOrNull()
        }
    }

    override suspend fun addLog(log: ExecutionLog) {
        withContext(Dispatchers.IO) {
            val queue = logCache.computeIfAbsent(log.taskId) { ConcurrentLinkedQueue() }
            queue.add(log)
            // 控制内存占用：超过阈值时丢弃最旧的
            while (queue.size > MAX_INMEMORY_LOGS_PER_TASK) {
                queue.poll()
            }
            runCatching {
                val file = File(logsDir, "${log.taskId}.json")
                file.appendText(json.encodeToString(ExecutionLog.serializer(), log) + "\n")
            }.onFailure {
                Log.e(TAG, "addLog failed for ${log.taskId}: ${it.message}")
            }
        }
    }

    override suspend fun getLogs(taskId: String): List<ExecutionLog> {
        // 内存命中优先
        val cached = logCache[taskId]?.toList()
        if (cached != null && cached.isNotEmpty()) return cached
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(logsDir, "$taskId.json")
                if (!file.exists()) return@withContext emptyList<ExecutionLog>()
                val logs = file.readLines()
                    .filter { it.isNotBlank() }
                    .map { json.decodeFromString(ExecutionLog.serializer(), it) }
                // 回填 cache
                val queue = logCache.computeIfAbsent(taskId) { ConcurrentLinkedQueue() }
                queue.clear()
                logs.take(MAX_INMEMORY_LOGS_PER_TASK).forEach { queue.add(it) }
                logs
            }.getOrElse {
                Log.e(TAG, "getLogs failed for $taskId: ${it.message}")
                emptyList<ExecutionLog>()
            }
        }
    }

    /**
     * 清理超过 STATE_TTL_MS 的旧 task 状态文件。
     * @return 实际清理的文件数
     */
    suspend fun cleanUpOldStates(): Int {
        return withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STATE_TTL_MS
            var removed = 0
            // 清理 tasks
            tasksDir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
                runCatching {
                    val task = json.decodeFromString(BurstTask.serializer(), file.readText())
                    val ts = task.updatedAt.coerceAtLeast(task.createdAt)
                    if (ts < cutoff) {
                        if (file.delete()) {
                            taskCache.remove(task.id)
                            removed++
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "cleanUpOldStates: skip ${file.name}: ${it.message}")
                }
            }
            // 清理 checkpoints（按文件 mtime）
            checkpointsDir.listFiles { f -> f.isFile }?.forEach { file ->
                if (file.lastModified() < cutoff && file.delete()) removed++
            }
            // 清理 logs（按文件 mtime）
            logsDir.listFiles { f -> f.isFile }?.forEach { file ->
                if (file.lastModified() < cutoff && file.delete()) {
                    logCache.remove(file.nameWithoutExtension)
                    removed++
                }
            }
            removed
        }
    }

    /**
     * 返回最近 N 个执行记录（按 updatedAt 倒序）。
     */
    suspend fun getRecentExecutions(limit: Int = RECENT_EXECUTIONS_DEFAULT_LIMIT): List<Map<String, Any?>> {
        return withContext(Dispatchers.IO) {
            // 优先从内存 cache 拿；如果 cache 还没预热，warmUpTaskIndex 已经在 init 中做过
            taskCache.values
                .sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }
                .take(limit.coerceAtLeast(1))
                .map { task ->
                    mapOf(
                        "taskId" to task.id,
                        "name" to task.name,
                        "status" to task.status.name,
                        "skillId" to task.skillId,
                        "createdAt" to task.createdAt,
                        "updatedAt" to task.updatedAt,
                        "startedAt" to task.startedAt,
                        "completedAt" to task.completedAt,
                        "progress" to task.progress
                    )
                }
        }
    }

    /**
     * 返回持久化存储总字节数（tasks + checkpoints + logs 三个子目录）。
     */
    suspend fun getStorageSizeBytes(): Long {
        return withContext(Dispatchers.IO) {
            listOf(tasksDir, checkpointsDir, logsDir).sumOf { dir ->
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }
    }

    // ---------- helpers ----------

    private fun Map<String, Any>.toJsonObject(): JsonObject =
        JsonObject(this.mapValues { (_, v) -> v.toJsonElement() })

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Char -> JsonPrimitive(this.toString())
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            JsonObject(
                (this as Map<String, Any>).mapValues { (_, v) -> v.toJsonElement() }
            )
        }
        is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
        is Array<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

    private fun JsonElement.toAnyMap(): Map<String, Any>? {
        if (this !is JsonObject) return null
        return this.mapValues { (_, v) -> v.toAny() }
    }

    private fun JsonElement.toAny(): Any = when (this) {
        is JsonNull -> ""  // JSON null represented as empty string (preserves non-null Any contract)
        is JsonPrimitive -> {
            when {
                this.isString -> this.content
                booleanOrNull != null -> this.boolean
                longOrNull != null -> this.long
                doubleOrNull != null -> this.double
                else -> this.content
            }
        }
        is JsonObject -> this.mapValues { (_, v) -> v.toAny() }
        is JsonArray -> this.map { it.toAny() }
    }

    @Suppress("unused")
    private fun checkpointFileName(taskId: String): String = "$taskId.json"
}
