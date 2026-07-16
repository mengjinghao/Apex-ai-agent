package com.apex.agent

import com.apex.util.AppLogger

/**
 * 文件操作 Agent — 负责文件系统相关的子任务执行。
 *
 * 处理的任务类型：file_read, file_write, file_search, file_move, file_copy,
 * file_delete, file_compress, file_extract 等。
 *
 * 该 Agent 是 [SubAgent] 体系的具体实现，通过 [GepaIntegration] 注册到
 * [TaskScheduler] 中参与多 Agent 协作。同时实现 [AgentLifecycleCallbacks]
 * 以支持生命周期管理（初始化/启动/暂停/停止/健康检查）。
 */
class FileAgent : LifecycleAwareBaseSubAgent(
    agentId = "file_agent",
    agentType = "file",
    displayName = "File Agent",
    description = "Handles file system operations: read, write, search, move, copy, delete, compress, extract"
) {
    companion object {
        private const val TAG = "FileAgent"
        private val FILE_TASK_TYPES = setOf(
            "file_read", "file_write", "file_search", "file_move",
            "file_copy", "file_delete", "file_compress", "file_extract",
            "file_list", "file_rename", "file_permission"
        )
    }

    /** Agent 是否已初始化（用于健康检查）。 */
    @Volatile
    private var initialized = false

    override fun canHandle(taskType: String): Boolean {
        return taskType in FILE_TASK_TYPES || taskType.startsWith("file_")
    }

    override suspend fun onInitialize() {
        AppLogger.d(TAG, "Initializing FileAgent...")
        // 文件 Agent 无需特殊初始化，标记为已就绪
        initialized = true
        AppLogger.i(TAG, "FileAgent initialized successfully")
    }

    override suspend fun onStart() {
        AppLogger.d(TAG, "FileAgent started")
    }

    override suspend fun onStop() {
        AppLogger.d(TAG, "FileAgent stopping, cleaning up resources...")
        initialized = false
    }

    override suspend fun healthCheck(): Boolean {
        // 文件 Agent 健康条件：已初始化且文件系统可访问
        if (!initialized) return false
        return try {
            // 简单检查：能否访问当前目录
            java.io.File(".").exists()
        } catch (e: Exception) {
            AppLogger.w(TAG, "FileAgent health check failed: ${e.message}")
            false
        }
    }

    override suspend fun execute(task: SubTask): SubTaskResult {
        val startTime = System.currentTimeMillis()
        AppLogger.d(TAG, "Executing task: ${task.taskType} (${task.taskId})")

        return try {
            // 文件操作的实际执行由工具系统（AIToolHandler）处理，
            // 此处返回成功占位结果，实际工具调用在 TaskScheduler 层完成。
            val result = SubTaskResult(
                taskId = task.taskId,
                success = true,
                executionTime = System.currentTimeMillis() - startTime,
                outputData = mapOf(
                    "message" to "File operation '${task.taskType}' delegated to tool system",
                    "taskType" to task.taskType,
                    "inputSize" to task.inputData.size
                )
            )
            AppLogger.d(TAG, "Task completed: ${task.taskId} in ${result.executionTime}ms")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task failed: ${task.taskId}", e)
            SubTaskResult(
                taskId = task.taskId,
                success = false,
                executionTime = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: "Unknown file operation error",
                errorStack = e.stackTraceToString()
            )
        }
    }
}

/**
 * 通用 Agent — 兜底处理所有不属于特定 Agent 的任务。
 *
 * 当 [TaskScheduler] 找不到专用 Agent 时，由 GeneralAgent 接管。
 * 支持的任务类型：general, text_processing, analysis, summarization,
 * translation, code_generation, question_answering 等。
 *
 * 实现 [AgentLifecycleCallbacks] 以支持生命周期管理。
 */
class GeneralAgent : LifecycleAwareBaseSubAgent(
    agentId = "general_agent",
    agentType = "general",
    displayName = "General Agent",
    description = "Handles general-purpose tasks: text processing, analysis, summarization, Q&A"
) {
    companion object {
        private const val TAG = "GeneralAgent"
    }

    @Volatile
    private var initialized = false

    // GeneralAgent 可以处理任何任务类型（兜底）
    override fun canHandle(taskType: String): Boolean = true

    override suspend fun onInitialize() {
        AppLogger.d(TAG, "Initializing GeneralAgent...")
        initialized = true
        AppLogger.i(TAG, "GeneralAgent initialized successfully")
    }

    override suspend fun onStart() {
        AppLogger.d(TAG, "GeneralAgent started")
    }

    override suspend fun onStop() {
        AppLogger.d(TAG, "GeneralAgent stopping...")
        initialized = false
    }

    override suspend fun healthCheck(): Boolean = initialized

    override suspend fun execute(task: SubTask): SubTaskResult {
        val startTime = System.currentTimeMillis()
        AppLogger.d(TAG, "Executing general task: ${task.taskType} (${task.taskId})")

        return try {
            // 通用任务通过 LLM + 工具系统协作完成，
            // 此处返回成功占位结果，实际执行在 TaskScheduler 层。
            val result = SubTaskResult(
                taskId = task.taskId,
                success = true,
                executionTime = System.currentTimeMillis() - startTime,
                outputData = mapOf(
                    "message" to "General task '${task.taskType}' delegated to LLM + tool system",
                    "taskType" to task.taskType,
                    "inputSize" to task.inputData.size
                )
            )
            AppLogger.d(TAG, "Task completed: ${task.taskId} in ${result.executionTime}ms")
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Task failed: ${task.taskId}", e)
            SubTaskResult(
                taskId = task.taskId,
                success = false,
                executionTime = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: "Unknown general task error",
                errorStack = e.stackTraceToString()
            )
        }
    }
}
