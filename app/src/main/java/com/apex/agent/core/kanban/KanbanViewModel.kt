package com.apex.agent.core.kanban

import com.apex.agent.core.collaboration.AgentCollaborationFramework
import com.apex.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.apex.agent.core.kanban.DispatchStatistics
import com.apex.agent.core.tools.defaultTool.debugger.name

/**
 * KanbanViewModel - 状态跟踪和可视? *
 * 提供看板状态的完整视图，支?
 * - 实时状态更? * - 状态历史跟? * - 可视化数? */
class KanbanViewModel(
    private val board: KanbanBoard,
    private val workerRegistry: WorkerRegistry,
    private val dispatcher: TaskDispatcher
) {
    private val TAG = "KanbanViewModel"

    /**
     * 看板 UI 状?     */
    data class KanbanUiState(
        val boardId: String,
        val boardName: String,
        val columns: List<ColumnUiState>,
        val totalTasks: Int,
        val taskStatistics: TaskStatistics,
        val workerStatistics: WorkerRegistry.WorkerStatistics,
        val dispatchStatistics: TaskDispatcher.DispatchStatistics,
        val lastUpdated: Long
    )

    /**
     * ?UI 状?     */
    data class ColumnUiState(
        val id: String,
        val name: String,
        val order: Int,
        val tasks: List<TaskUiState>,
        val taskCount: Int,
        val assignedWorker: String?,
        val hasWorker: Boolean
    )

    /**
     * 任务 UI 状?     */
    data class TaskUiState(
        val id: String,
        val title: String,
        val description: String,
        val status: KanbanTaskStatus,
        val priority: Int,
        val priorityLabel: String,
        val assignedWorker: String?,
        val assignedAgent: String?,
        val progress: Int,
        val tags: List<String>,
        val dependenciesCount: Int,
        val isBlocked: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val executionMinutes: Int?
    )

    /**
     * 任务统计
     */
    data class TaskStatistics(
        val total: Int,
        val pending: Int,
        val assigned: Int,
        val inProgress: Int,
        val completed: Int,
        val failed: Int,
        val blocked: Int,
        val averageExecutionMinutes: Int
    )

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    private val _selectedTask = MutableStateFlow<KanbanTask?>(null)
    val selectedTask: StateFlow<KanbanTask?> = _selectedTask.asStateFlow()

    private val _boardEvent = MutableStateFlow<BoardEvent?>(null)
    val boardEvent: StateFlow<BoardEvent?> = _boardEvent.asStateFlow()

    /**
     * 刷新 UI 状?     */
    fun refreshState() {
        _uiState.value = buildUiState()
    }

    /**
     * 选择任务
     */
    fun selectTask(taskId: String) {
        _selectedTask.value = board.getTask(taskId)
    }

    /**
     * 清除任务选择
     */
    fun clearSelection() {
        _selectedTask.value = null
    }

    /**
     * 添加新任?     */
    fun addTask(title: String, description: String, columnId: String, priority: Int = 3): KanbanTask {
        val task = KanbanTask(
            title = title,
            description = description,
            columnId = columnId,
            priority = priority
        )

        board.getColumn(columnId)?.tasks?.add(task)
        refreshState()
        emitEvent(BoardEvent.TaskAdded(task))
        AppLogger.d(TAG, "Added task: ${task.title}")
        return task
    }

    /**
     * 移动任务
     */
    fun moveTask(taskId: String, targetColumnId: String): Boolean {
        val success = board.moveTask(taskId, targetColumnId)
        if (success) {
            refreshState()
            val task = board.getTask(taskId)
            task?.let { emitEvent(BoardEvent.TaskMoved(it, targetColumnId)) }
        }
        return success
    }

    /**
     * 开始任务执?     */
    fun startTask(taskId: String) {
        val task = board.getTask(taskId)
        if (task != null) {
            task.startExecution()
            refreshState()
            emitEvent(BoardEvent.TaskStarted(task))
        }
    }

    /**
     * 完成任务
     */
    fun completeTask(taskId: String, result: String? = null) {
        val task = board.getTask(taskId)
        if (task != null) {
            task.complete(result)
            refreshState()
            emitEvent(BoardEvent.TaskCompleted(task))
        }
    }

    /**
     * 分发任务
     */
    suspend fun dispatchTask(taskId: String) {
        val task = board.getTask(taskId)
        if (task != null) {
            val result = dispatcher.dispatchTask(task)
            refreshState()
            when (result) {
                is TaskDispatcher.DispatchResult.Success -> {
                    emitEvent(BoardEvent.TaskDispatched(task, result.worker.name, result.agentAssigned))
                }
                is TaskDispatcher.DispatchResult.Failure -> {
                    emitEvent(BoardEvent.DispatchFailed(task, result.reason))
                }
                is TaskDispatcher.DispatchResult.Blocked -> {
                    emitEvent(BoardEvent.TaskBlocked(task, result.blockedBy))
                }
            }
        }
    }

    /**
     * 重试分发任务
     */
    suspend fun retryDispatch(taskId: String) {
        val result = dispatcher.retryDispatch(taskId)
        refreshState()
        when (result) {
            is TaskDispatcher.DispatchResult.Success -> {
                emitEvent(BoardEvent.TaskDispatched(result.task, result.worker.name, result.agentAssigned))
            }
            is TaskDispatcher.DispatchResult.Failure -> {
                emitEvent(BoardEvent.DispatchFailed(result.task, result.reason))
            }
            is TaskDispatcher.DispatchResult.Blocked -> {
                emitEvent(BoardEvent.TaskBlocked(result.task, result.blockedBy))
            }
        }
    }

    /**
     * 获取任务历史
     */
    fun getTaskHistory(taskId: String): List<CollaborationEvent> {
        return board.getTask(taskId)?.collaborationHistory ?: emptyList()
    }

    /**
     * 获取看板脉络
     */
    fun getBoardFlow(): List<FlowStep> {
        val steps = mutableListOf<FlowStep>()
        board.columns.sortedBy { it.order }.forEach { column ->
            steps.add(FlowStep.ColumnStart(column.id, column.name, column.order))
            column.tasks.forEach { task ->
                steps.add(FlowStep.TaskInColumn(task.id, task.title, task.status, column.id))
            }
            steps.add(FlowStep.ColumnEnd(column.id))
        }
        return steps
    }

    /**
     * 获取任务时间?     */
    fun getTaskTimeline(taskId: String): List<TimelineEvent> {
        val task = board.getTask(taskId) ?: return emptyList()
        val events = mutableListOf<TimelineEvent>()

        events.add(TimelineEvent(
            timestamp = task.createdAt,
            type = TimelineEvent.Type.CREATED,
            message = "任务已创?
        ))

        task.collaborationHistory.forEach { event ->
            val type = when (event.type) {
                CollaborationEvent.Type.ASSIGNED -> TimelineEvent.Type.ASSIGNED
                CollaborationEvent.Type.STARTED -> TimelineEvent.Type.STARTED
                CollaborationEvent.Type.COMPLETED -> TimelineEvent.Type.COMPLETED
                CollaborationEvent.Type.FAILED -> TimelineEvent.Type.FAILED
                CollaborationEvent.Type.BLOCKED -> TimelineEvent.Type.BLOCKED
                CollaborationEvent.Type.UNBLOCKED -> TimelineEvent.Type.UNBLOCKED
                else -> TimelineEvent.Type.UPDATED
            }
            events.add(TimelineEvent(event.timestamp, type, event.message))
        }

        return events.sortedBy { it.timestamp }
    }

    private fun buildUiState(): KanbanUiState {
        val columns = board.columns.map { column ->
            ColumnUiState(
                id = column.id,
                name = column.name,
                order = column.order,
                tasks = column.tasks.map { task -> buildTaskUiState(task) },
                taskCount = column.tasks.size,
                assignedWorker = column.assignedWorker,
                hasWorker = column.assignedWorker != null || column.requiredAgentRoles.isNotEmpty()
            )
        }

        val allTasks = board.getAllTasks()
        val taskStats = TaskStatistics(
            total = allTasks.size,
            pending = allTasks.count { it.status == KanbanTaskStatus.PENDING },
            assigned = allTasks.count { it.status == KanbanTaskStatus.ASSIGNED },
            inProgress = allTasks.count { it.status == KanbanTaskStatus.IN_PROGRESS },
            completed = allTasks.count { it.status == KanbanTaskStatus.COMPLETED },
            failed = allTasks.count { it.status == KanbanTaskStatus.FAILED },
            blocked = allTasks.count { it.status == KanbanTaskStatus.BLOCKED },
            averageExecutionMinutes = if (allTasks.isNotEmpty()) {
                allTasks.filter { it.actualMinutes > 0 }.map { it.actualMinutes }.average().toInt()
            } else 0
        )

        return KanbanUiState(
            boardId = board.id,
            boardName = board.name,
            columns = columns,
            totalTasks = allTasks.size,
            taskStatistics = taskStats,
            workerStatistics = workerRegistry.getStatistics(),
            dispatchStatistics = dispatcher.getDispatchStatistics(),
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun buildTaskUiState(task: KanbanTask): TaskUiState {
        return TaskUiState(
            id = task.id,
            title = task.title,
            description = task.description,
            status = task.status,
            priority = task.priority,
            priorityLabel = when (task.priority) {
                1 -> "紧?
                2 -> "?
                3 -> "?
                4 -> "?
                else -> "未知"
            },
            assignedWorker = task.assignedWorkerId,
            assignedAgent = task.assignedAgentName,
            progress = task.getProgress(),
            tags = task.tags.toList(),
            dependenciesCount = task.dependencies.size,
            isBlocked = task.status == KanbanTaskStatus.BLOCKED,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
            executionMinutes = if (task.startedAt != null) task.getExecutionMinutes() else null
        )
    }

    private fun emitEvent(event: BoardEvent) {
        _boardEvent.value = event
    }

    fun clearEvent() {
        _boardEvent.value = null
    }

    /**
     * 看板事件
     */
    sealed class BoardEvent {
        data class TaskAdded(val task: KanbanTask) : BoardEvent()
        data class TaskMoved(val task: KanbanTask, val toColumnId: String) : BoardEvent()
        data class TaskStarted(val task: KanbanTask) : BoardEvent()
        data class TaskCompleted(val task: KanbanTask) : BoardEvent()
        data class TaskDispatched(val task: KanbanTask, val workerName: String, val agentAssigned: Boolean) : BoardEvent()
        data class TaskBlocked(val task: KanbanTask, val blockedBy: List<String>) : BoardEvent()
        data class DispatchFailed(val task: KanbanTask, val reason: String) : BoardEvent()
    }

    /**
     * 看板流程步骤
     */
    sealed class FlowStep {
        data class ColumnStart(val columnId: String, val columnName: String, val order: Int) : FlowStep()
        data class TaskInColumn(val taskId: String, val taskTitle: String, val status: KanbanTaskStatus, val columnId: String) : FlowStep()
        data class ColumnEnd(val columnId: String) : FlowStep()
    }

    /**
     * 时间线事?     */
    data class TimelineEvent(
        val timestamp: Long,
        val type: Type,
        val message: String
    ) {
        enum class Type {
            CREATED,
            ASSIGNED,
            STARTED,
            UPDATED,
            COMPLETED,
            FAILED,
            BLOCKED,
            UNBLOCKED,
            MOVED,
            COMMENT
        }
    }
}
