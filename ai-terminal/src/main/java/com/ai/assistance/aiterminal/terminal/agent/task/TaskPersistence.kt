package com.ai.assistance.aiterminal.terminal.agent.task

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "TaskPersistence"
private const val SNAPSHOT_DIR = "task_snapshots"
private const val SCHEDULED_TASKS_DIR = "scheduled_tasks"

/**
 * 任务持久化 - 断点续执行
 */
class TaskPersistence(
    private val context: Context
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val snapshotDir: File by lazy {
        File(context.filesDir, SNAPSHOT_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val scheduledTasksDir: File by lazy {
        File(context.filesDir, SCHEDULED_TASKS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    // ==================== 快照管理 ====================
    
    /**
     * 保存任务快照
     */
    suspend fun saveSnapshot(snapshot: TaskSnapshot) = withContext(Dispatchers.IO) {
        try {
            val snapshotFile = getSnapshotFile(snapshot.taskId)
            val jsonString = json.encodeToString(snapshot)
            snapshotFile.writeText(jsonString)
            
            Log.i(TAG, "Snapshot saved for task: ${snapshot.taskId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
        }
    }
    
    /**
     * 加载任务快照
     */
    suspend fun loadSnapshot(taskId: String): TaskSnapshot? = withContext(Dispatchers.IO) {
        try {
            val snapshotFile = getSnapshotFile(taskId)
            if (!snapshotFile.exists()) {
                return@withContext null
            }
            
            val jsonString = snapshotFile.readText()
            val snapshot = json.decodeFromString<TaskSnapshot>(jsonString)
            
            Log.i(TAG, "Snapshot loaded for task: $taskId")
            return@withContext snapshot
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot", e)
            return@withContext null
        }
    }
    
    /**
     * 清理任务快照
     */
    suspend fun clearSnapshot(taskId: String) = withContext(Dispatchers.IO) {
        try {
            val snapshotFile = getSnapshotFile(taskId)
            if (snapshotFile.exists()) {
                snapshotFile.delete()
                Log.i(TAG, "Snapshot cleared for task: $taskId")
            } else {
                Log.d(TAG, "No snapshot to clear for task: $taskId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear snapshot", e)
        }
    }
    
    /**
     * 获取所有未完成的快照
     */
    suspend fun getAllPendingSnapshots(): List<TaskSnapshot> = withContext(Dispatchers.IO) {
        val snapshots = mutableListOf<TaskSnapshot>()
        
        try {
            snapshotDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val jsonString = file.readText()
                        val snapshot = json.decodeFromString<TaskSnapshot>(jsonString)
                        
                        if (snapshot.status == TaskStatus.RUNNING || 
                            snapshot.status == TaskStatus.PAUSED) {
                            snapshots.add(snapshot)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse snapshot: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending snapshots", e)
        }
        
        return@withContext snapshots
    }
    
    private fun getSnapshotFile(taskId: String): File {
        return File(snapshotDir, "${taskId}.json")
    }
    
    // ==================== 定时任务管理 ====================
    
    /**
     * 保存定时任务
     */
    suspend fun saveScheduledTask(config: ScheduledTaskConfig) = withContext(Dispatchers.IO) {
        try {
            val taskFile = getScheduledTaskFile(config.taskId)
            val jsonString = json.encodeToString(config)
            taskFile.writeText(jsonString)
            
            Log.i(TAG, "Scheduled task saved: ${config.taskId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scheduled task", e)
        }
    }
    
    /**
     * 加载定时任务
     */
    suspend fun loadScheduledTask(taskId: String): ScheduledTaskConfig? = withContext(Dispatchers.IO) {
        try {
            val taskFile = getScheduledTaskFile(taskId)
            if (!taskFile.exists()) {
                return@withContext null
            }
            
            val jsonString = taskFile.readText()
            return@withContext json.decodeFromString<ScheduledTaskConfig>(jsonString)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scheduled task", e)
            return@withContext null
        }
    }
    
    /**
     * 删除定时任务
     */
    suspend fun deleteScheduledTask(taskId: String) = withContext(Dispatchers.IO) {
        try {
            val taskFile = getScheduledTaskFile(taskId)
            if (taskFile.exists()) {
                taskFile.delete()
                Log.i(TAG, "Scheduled task deleted: $taskId")
            } else {
                Log.d(TAG, "No scheduled task to delete: $taskId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete scheduled task", e)
        }
    }
    
    /**
     * 获取所有定时任务
     */
    suspend fun getAllScheduledTasks(): List<ScheduledTaskConfig> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<ScheduledTaskConfig>()
        
        try {
            scheduledTasksDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val jsonString = file.readText()
                        val task = json.decodeFromString<ScheduledTaskConfig>(jsonString)
                        tasks.add(task)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse scheduled task: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get scheduled tasks", e)
        }
        
        return@withContext tasks
    }
    
    /**
     * 按组删除定时任务
     */
    suspend fun deleteScheduledTasksByGroup(groupId: String) = withContext(Dispatchers.IO) {
        try {
            scheduledTasksDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val jsonString = file.readText()
                        val task = json.decodeFromString<ScheduledTaskConfig>(jsonString)
                        if (task.groupId == groupId) {
                            file.delete()
                            Log.i(TAG, "Scheduled task deleted by group: ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse scheduled task for group delete: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete scheduled tasks by group", e)
        }
    }

    /**
     * 删除所有定时任务
     */
    suspend fun deleteAllScheduledTasks() = withContext(Dispatchers.IO) {
        try {
            scheduledTasksDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    file.delete()
                }
            }
            Log.i(TAG, "All scheduled tasks deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all scheduled tasks", e)
        }
    }

    /**
     * 按组获取定时任务
     */
    suspend fun getScheduledTasksByGroup(groupId: String): List<ScheduledTaskConfig> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<ScheduledTaskConfig>()
        try {
            scheduledTasksDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val jsonString = file.readText()
                        val task = json.decodeFromString<ScheduledTaskConfig>(jsonString)
                        if (task.groupId == groupId) {
                            tasks.add(task)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse scheduled task for group: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get scheduled tasks by group", e)
        }
        return@withContext tasks
    }

    /**
     * 获取单个定时任务
     */
    suspend fun getScheduledTask(taskId: String): ScheduledTaskConfig? = withContext(Dispatchers.IO) {
        return@withContext loadScheduledTask(taskId)
    }

    private fun getScheduledTaskFile(taskId: String): File {
        return File(scheduledTasksDir, "${taskId}.json")
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清理过期的快照（超过 7 天）
     */
    suspend fun cleanExpiredSnapshots() = withContext(Dispatchers.IO) {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        
        try {
            snapshotDir.listFiles()?.forEach { file ->
                if (file.lastModified() < sevenDaysAgo) {
                    file.delete()
                    Log.i(TAG, "Expired snapshot deleted: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean expired snapshots", e)
        }
    }
}
