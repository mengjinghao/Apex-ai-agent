package com.apex.agent.burstmode.checkpoint

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.BurstInput
import com.apex.agent.plugins.burst.base.BurstSkillResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务检查点。
 *
 * 记录任务执行到某一时刻的完整状态，用于断点续传。
 *
 * @param taskId 任务 ID
 * @param task 任务实例（执行时的快照）
 * @param completedSteps 已完成的步骤
 * @param totalSteps 总步骤数
 * @param intermediateResult 中间结果（如果有）
 * @param timestamp 检查点创建时间戳
 * @param metadata 附加元数据
 */
data class TaskCheckpoint(
    val taskId: String,
    val task: BurstTask,
    val completedSteps: List<String>,
    val totalSteps: Int,
    val intermediateResult: BurstSkillResult?,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 完成进度（0..1）。
     */
    val progress: Float
        get() = if (totalSteps > 0) completedSteps.size.toFloat() / totalSteps else 0f

    /**
     * 是否已完成。
     */
    val isComplete: Boolean
        get() = completedSteps.size >= totalSteps

    /**
     * 下一个待执行的步骤（如果有）。
     */
    val nextStep: String?
        get() = if (isComplete) null else "step_${completedSteps.size + 1}"
}

/**
 * 检查点存储接口。
 *
 * 业务侧可注入自定义实现（如 Room / 文件 / Redis）。
 */
interface CheckpointStore {
    suspend fun save(checkpoint: TaskCheckpoint): Boolean
    suspend fun load(taskId: String): TaskCheckpoint?
    suspend fun delete(taskId: String): Boolean
    suspend fun listAll(): List<TaskCheckpoint>
    suspend fun listIncomplete(): List<TaskCheckpoint>
    suspend fun clear()
}

/**
 * 内存检查点存储。
 *
 * 默认实现，数据存储在内存中，进程退出后丢失。
 * 适用于测试和短时运行场景。
 */
class InMemoryCheckpointStore : CheckpointStore {

    private val store = ConcurrentHashMap<String, TaskCheckpoint>()

    override suspend fun save(checkpoint: TaskCheckpoint): Boolean {
        store[checkpoint.taskId] = checkpoint
        return true
    }

    override suspend fun load(taskId: String): TaskCheckpoint? = store[taskId]

    override suspend fun delete(taskId: String): Boolean {
        return store.remove(taskId) != null
    }

    override suspend fun listAll(): List<TaskCheckpoint> = store.values.toList()

    override suspend fun listIncomplete(): List<TaskCheckpoint> =
        store.values.filter { !it.isComplete }.toList()

    override suspend fun clear() {
        store.clear()
    }
}

/**
 * 文件检查点存储。
 *
 * 数据持久化到文件系统，进程重启后可恢复。
 * 每个检查点保存为 `<dir>/<taskId>.checkpoint.json`。
 *
 * @param directory 存储目录
 */
class FileCheckpointStore(private val directory: File) : CheckpointStore {

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    private fun fileOf(taskId: String): File {
        val safe = taskId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(directory, "$safe.checkpoint.json")
    }

    override suspend fun save(checkpoint: TaskCheckpoint): Boolean {
        return try {
            val json = checkpointToJson(checkpoint)
            fileOf(checkpoint.taskId).writeText(json)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun load(taskId: String): TaskCheckpoint? {
        val file = fileOf(taskId)
        if (!file.exists()) return null
        return try {
            jsonToCheckpoint(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun delete(taskId: String): Boolean {
        return fileOf(taskId).delete()
    }

    override suspend fun listAll(): List<TaskCheckpoint> {
        return directory.listFiles { f -> f.name.endsWith(".checkpoint.json") }
            ?.mapNotNull { f ->
                try { jsonToCheckpoint(f.readText()) } catch (_: Exception) { null }
            }
            ?: emptyList()
    }

    override suspend fun listIncomplete(): List<TaskCheckpoint> =
        listAll().filter { !it.isComplete }

    override suspend fun clear() {
        directory.listFiles { f -> f.name.endsWith(".checkpoint.json") }
            ?.forEach { it.delete() }
    }

    private fun checkpointToJson(cp: TaskCheckpoint): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"taskId\":\"${escapeJson(cp.taskId)}\",")
        sb.append("\"completedSteps\":[${cp.completedSteps.joinToString(",") { "\"${escapeJson(it)}\"" }}],")
        sb.append("\"totalSteps\":${cp.totalSteps},")
        sb.append("\"timestamp\":${cp.timestamp}")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonToCheckpoint(json: String): TaskCheckpoint {
        // 简化解析：实际应使用 kotlinx.serialization
        val taskId = Regex("\"taskId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        val totalSteps = Regex("\"totalSteps\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val timestamp = Regex("\"timestamp\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
            ?: System.currentTimeMillis()
        val stepsMatch = Regex("\"completedSteps\":\\[([^]]*)]").find(json)?.groupValues?.get(1) ?: ""
        val completedSteps = Regex("\"([^\"]+)\"").findAll(stepsMatch).map { it.groupValues[1] }.toList()

        return TaskCheckpoint(
            taskId = taskId,
            task = BurstTask(id = taskId, name = taskId, description = "", input = BurstInput()),  // 简化
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            intermediateResult = null,
            timestamp = timestamp
        )
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * 检查点管理器。
 *
 * 负责检查点的创建、恢复、清理。
 * 与 [com.apex.agent.burstmode.api.BurstMode] 集成，支持任务断点续传。
 *
 * # 使用示例
 *
 * ```
 * val manager = CheckpointManager(FileCheckpointStore(File(context.filesDir, "checkpoints")))
 *
 * // 创建检查点
 * manager.saveCheckpoint(task, completedSteps = listOf("step1", "step2"), totalSteps = 5)
 *
 * // 恢复检查点
 * val checkpoint = manager.loadCheckpoint(taskId)
 * if (checkpoint != null && !checkpoint.isComplete) {
 *     // 从 checkpoint.nextStep 继续执行
 * }
 *
 * // 列出所有未完成的任务
 * val incomplete = manager.listIncompleteTasks()
 * ```
 */
class CheckpointManager(private val store: CheckpointStore) {

    /**
     * 保存检查点。
     *
     * @param task 当前任务
     * @param completedSteps 已完成的步骤列表
     * @param totalSteps 总步骤数
     * @param intermediateResult 中间结果（可选）
     * @param metadata 附加元数据
     * @return true 保存成功
     */
    suspend fun saveCheckpoint(
        task: BurstTask,
        completedSteps: List<String>,
        totalSteps: Int,
        intermediateResult: BurstSkillResult? = null,
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        val checkpoint = TaskCheckpoint(
            taskId = task.id,
            task = task,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            intermediateResult = intermediateResult,
            metadata = metadata
        )
        return store.save(checkpoint)
    }

    /**
     * 加载检查点。
     *
     * @param taskId 任务 ID
     * @return 检查点，如果不存在返回 null
     */
    suspend fun loadCheckpoint(taskId: String): TaskCheckpoint? = store.load(taskId)

    /**
     * 删除检查点。
     */
    suspend fun deleteCheckpoint(taskId: String): Boolean = store.delete(taskId)

    /**
     * 列出所有检查点。
     */
    suspend fun listAllCheckpoints(): List<TaskCheckpoint> = store.listAll()

    /**
     * 列出所有未完成的检查点（可恢复的任务）。
     */
    suspend fun listIncompleteTasks(): List<TaskCheckpoint> = store.listIncomplete()

    /**
     * 清空所有检查点。
     */
    suspend fun clearAll() = store.clear()

    /**
     * 检查任务是否有可恢复的检查点。
     *
     * @return true 如果存在未完成的检查点
     */
    suspend fun canResume(taskId: String): Boolean {
        val cp = store.load(taskId) ?: return false
        return !cp.isComplete
    }

    /**
     * 获取恢复位置（下一个待执行的步骤名）。
     *
     * @return 步骤名，如果已完成或不存在返回 null
     */
    suspend fun getResumePoint(taskId: String): String? {
        return store.load(taskId)?.takeIf { !it.isComplete }?.nextStep
    }
}
