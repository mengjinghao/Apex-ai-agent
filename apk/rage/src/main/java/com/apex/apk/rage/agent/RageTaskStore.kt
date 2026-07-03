package com.apex.apk.rage.agent

import android.content.Context
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 狂暴模式任务历史持久化。
 *
 * 存储结构：
 *   <app_data>/apex-rage-tasks/index.json     — 任务索引
 *   <app_data>/apex-rage-tasks/<taskId>.json  — 任务全流程详情
 */
class RageTaskStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "apex-rage-tasks").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    /** 保存任务执行结果（含全流程步骤）。 */
    fun saveTask(result: TaskExecutionResult, description: String) {
        try {
            // 保存详情
            val detailFile = File(dir, "${result.taskId}.json")
            detailFile.writeText(json.encodeToString(result))
            // 更新索引
            val index = loadIndex().toMutableList()
            index.removeAll { it.taskId == result.taskId }
            index.add(0, TaskIndexEntry(
                taskId = result.taskId,
                description = description,
                success = result.success,
                startTime = result.steps.firstOrNull()?.timestamp ?: System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                stepCount = result.steps.size,
                durationMs = result.durationMs,
                retryCount = result.retryCount
            ))
            indexFile.writeText(json.encodeToString(index))
        } catch (t: Throwable) {
            ApexLog.w("rage", "[TaskStore] save failed: ${t.message}")
        }
    }

    /** 加载任务详情。 */
    fun loadTask(taskId: String): TaskExecutionResult? {
        return try {
            val file = File(dir, "$taskId.json")
            if (!file.exists()) return null
            json.decodeFromString(file.readText())
        } catch (_: Throwable) { null }
    }

    /** 加载索引列表。 */
    fun loadIndex(): List<TaskIndexEntry> {
        return try {
            if (!indexFile.exists()) return emptyList()
            json.decodeFromString(indexFile.readText())
        } catch (_: Throwable) { emptyList() }
    }

    /** 删除任务。 */
    fun deleteTask(taskId: String): Boolean {
        val ok = File(dir, "$taskId.json").delete()
        if (ok) {
            val index = loadIndex().filterNot { it.taskId == taskId }
            indexFile.writeText(json.encodeToString(index))
        }
        return ok
    }

    /** 清空所有。 */
    fun clearAll(): Int {
        val count = loadIndex().size
        dir.listFiles()?.forEach { it.delete() }
        return count
    }
}

@Serializable
data class TaskIndexEntry(
    val taskId: String,
    val description: String,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val stepCount: Int,
    val durationMs: Long,
    val retryCount: Int
)
