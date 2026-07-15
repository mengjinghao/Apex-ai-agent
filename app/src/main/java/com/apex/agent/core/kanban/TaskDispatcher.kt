package com.apex.agent.core.kanban

import com.apex.agent.core.collaboration.AgentCollaborationFramework
import com.apex.agent.core.collaboration.AgentCollaborationFramework.AgentRole
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.agent.core.tools.defaultTool.standard.name

/**
 * TaskDispatcher - 任务分发逻辑
 *
 * 负责将任务智能分发到合适的 Worker/Agent:
 * - 基于角色和能力匹�? * - 负载均衡
 * - 依赖处理
 */
class TaskDispatcher(
    private val board: KanbanBoard,
    private val workerRegistry: WorkerRegistry,
    private val collaborationFramework: AgentCollaborationFramework? = null
) {
    private val TAG = "TaskDispatcher"

    /**
     * 分配结果
     */
    sealed class DispatchResult {
        data class Success(
            val task: KanbanTask,
            val worker: WorkerRegistry.Worker,
            val agentAssigned: Boolean
        ) : DispatchResult()

        data class Failure(
            val task: KanbanTask,
            val reason: String
        ) : DispatchResult()

        data class Blocked(
            val task: KanbanTask,
            val blockedBy: List<String>
        ) : DispatchResult()
    }

    /**
     * 尝试将任务分发到合适的 Worker
     */
    suspend fun dispatchTask(task: KanbanTask): DispatchResult = withContext(Dispatchers.IO) {
        // 检查是否有未完成的依赖
    val blockedBy = task.dependencies.filter { depId ->
            val depTask = board.getTask(depId)
            depTask?.status != KanbanTaskStatus.COMPLETED
        }
        if (blockedBy.isNotEmpty()) {
            AppLogger.d(TAG, "Task ${task.id} is blocked by ${blockedBy}")
            return@withContext DispatchResult.Blocked(task, blockedBy)
        }

        // 检查列条件
    val column = board.getColumn(task.columnId)
        if (column != null && !column.canEnter(task)) {
            return@withContext DispatchResult.Failure(task, "Task does not meet column entry conditions")
        }

        // 找到最�?Worker
    val worker = workerRegistry.findBestWorker(task, task.assignedRole)
            ?: return@withContext DispatchResult.Failure(task, "No suitable worker found")

        // 尝试分配�?Worker
    if (!worker.canHandle(task)) {
            return@withContext DispatchResult.Failure(task, "Worker ${worker.id} cannot handle task")
        }

        // 执行分配
    try {
            // 分配任务
            task.assignTo(
                workerId = worker.id,
                agentId = null,
                agentName = null,
                role = task.assignedRole
            )

            // 如果协作框架存在，尝试分�?Agent
    var agentAssigned = false
            if (collaborationFramework != null) {
                agentAssigned = tryAssignAgent(task, worker)
            }

            // 移动任务�进行�列（如果配置了自动流转）
    if (column?.autoProcessEnabled == true) {
                val nextColumn = findNextColumn(column)
        if (nextColumn != null) {
                    board.moveTask(task.id, nextColumn.id)
                }
            }

            AppLogger.d(TAG, "Dispatched task ${task.id} to worker ${worker.name}")
            DispatchResult.Success(task, worker, agentAssigned)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to dispatch task ${task.id}", e)
            DispatchResult.Failure(task, "Dispatch error: ${e.message}")
        }
    }

    /**
     * 批量分发待处理任�?     */
    suspend fun dispatchPendingTasks(): List<DispatchResult> = withContext(Dispatchers.IO) {
        val pendingTasks = board.getTasksByStatus(KanbanTaskStatus.PENDING)
        pendingTasks.map { task -> dispatchTask(task) }
    }

    /**
     * 将任务重试分发（当任务失败或被阻塞解除时�?     */
    suspend fun retryDispatch(taskId: String): DispatchResult {
        val task = board.getTask(taskId)
            ?: return DispatchResult.Failure(
                KanbanTask().apply { id = taskId },
                "Task not found"
            )

        // 如果任务被阻塞，先解�?
    if (task.status == KanbanTaskStatus.BLOCKED) {
            task.unblock()
        }

        // 重置为待处理
        task.status = KanbanTaskStatus.PENDING
        task.result = null

        return dispatchTask(task)
    }

    /**
     * 自动分发新任务到合适列
     */
    suspend fun autoRouteTask(task: KanbanTask): Boolean = withContext(Dispatchers.IO) {
        // 根据任务属性自动选择�?
    val targetColumn = selectColumnForTask(task)
        if (targetColumn != null && targetColumn.id != task.columnId) {
            board.moveTask(task.id, targetColumn.id)
            AppLogger.d(TAG, "Auto-routed task ${task.id} to column ${targetColumn.name}")
            true
        } else {
            false
        }
    }

    /**
     * 基于任务属性选择合适的�?     */
    private fun selectColumnForTask(task: KanbanTask): KanbanColumn? {
        // 简单实现：基于标签或类型选择
    val typeColumnMap = mapOf(
            "design" to "设计",
            "development" to "开�?,
            "testing" to "测试",
            "deployment" to "部署",
            "analysis" to "需�?,
            "research" to "需�?
        )
        val typeKey = typeColumnMap.entries.find { (key, _) ->
            task.taskType.contains(key, ignoreCase = true) ||
                    task.tags.any { it.contains(key, ignoreCase = true) }
        }?.value

        return board.columns.find { it.name == typeKey }
            ?: board.columns.minByOrNull { it.order }
    }

    /**
     * 尝试在协作框架中分配 Agent
     */
    private suspend fun tryAssignAgent(task: KanbanTask, worker: WorkerRegistry.Worker): Boolean {
        if (collaborationFramework == null) return false

        val preferredRole = task.assignedRole ?: AgentRole.DEVELOPER
        val capabilities = worker.capabilities + task.tags

        val agent = collaborationFramework.findBestAgentForTask(capabilities)
        if (agent != null) {
            task.assignedAgentId = agent.id
            task.assignedAgentName = agent.name
            collaborationFramework.assignTask(task.id, agent.id)
        return true
        }
        return false
    }

    /**
     * 找到下一�?     */
    private fun findNextColumn(currentColumn: KanbanColumn): KanbanColumn? {
        return board.columns
            .filter { it.order > currentColumn.order }
            .minByOrNull { it.order }
    }

    /**
     * 检查并更新阻塞任务
     */
    suspend fun checkAndUnblockTasks() = withContext(Dispatchers.IO) {
        val blockedTasks = board.getTasksByStatus(KanbanTaskStatus.BLOCKED)

        blockedTasks.forEach { task ->
            val stillBlocked = task.dependencies.filter { depId ->
                val depTask = board.getTask(depId)
                depTask?.status != KanbanTaskStatus.COMPLETED
            }
        if (stillBlocked.isEmpty()) {
                task.unblock()
                AppLogger.d(TAG, "Task ${task.id} is now unblocked")
            }
        }
    }

    /**
     * 获取分发统计
     */
    fun getDispatchStatistics(): DispatchStatistics {
        val allTasks = board.getAllTasks()
        val pending = allTasks.count { it.status == KanbanTaskStatus.PENDING }
        val inProgress = allTasks.count { it.status == KanbanTaskStatus.IN_PROGRESS }
        val completed = allTasks.count { it.status == KanbanTaskStatus.COMPLETED }
        val failed = allTasks.count { it.status == KanbanTaskStatus.FAILED }
        val blocked = allTasks.count { it.status == KanbanTaskStatus.BLOCKED }
        return DispatchStatistics(
            totalTasks = allTasks.size,
            pending = pending,
            inProgress = inProgress,
            completed = completed,
            failed = failed,
            blocked = blocked,
            dispatchRate = if (pending + inProgress > 0) {
                completed.toFloat() / (pending + inProgress + completed)
            } else 0f
        )
    }

    data class DispatchStatistics(
        val totalTasks: Int,
        val pending: Int,
        val inProgress: Int,
        val completed: Int,
        val failed: Int,
        val blocked: Int,
        val dispatchRate: Float
    )
}
