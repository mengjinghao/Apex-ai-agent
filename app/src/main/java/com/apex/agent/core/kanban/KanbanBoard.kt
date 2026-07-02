package com.apex.agent.core.kanban

import com.apex.agent.core.extension.CapabilityRegistry
import com.apex.agent.core.extension.FootprintLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KanbanBoard {

    private val logger = LoggerFactory.getLogger(KanbanBoard::class.java)
    private val columns = mutableListOf<KanbanColumn>()
    private val tasks = mutableListOf<KanbanTask>()
    private val boardState = MutableStateFlow(BoardState(columns, tasks))

    companion object {
        @Volatile
        private var instance: KanbanBoard? = null

        fun initialize() {
            if (instance == null) {
                synchronized(this) {
                    instance = KanbanBoard()
                }
            }
        }

        fun getInstance(): KanbanBoard {
            return instance ?: throw IllegalStateException("KanbanBoard not initialized")
        }
    }

    fun addColumn(column: KanbanColumn) {
        columns.add(column)
        updateState()
    }

    fun removeColumn(columnId: String) {
        columns.removeAll { it.id == columnId }
        tasks.removeAll { it.currentColumnId == columnId }
        updateState()
    }

    fun addTask(task: KanbanTask) {
        tasks.add(task)
        updateState()
    }

    fun moveTask(taskId: String, targetColumnId: String) {
        val task = tasks.find { it.id == taskId }
        task?.let {
            it.currentColumnId = targetColumnId
            it.status = getColumnStatus(targetColumnId)
            updateState()
        }
    }

    fun updateTask(taskId: String, updates: KanbanTask.() -> Unit) {
        val task = tasks.find { it.id == taskId }
        task?.apply(updates)
        updateState()
    }

    fun getTasksByColumn(columnId: String): List<KanbanTask> {
        return tasks.filter { it.currentColumnId == columnId }
    }

    fun getTask(taskId: String): KanbanTask? {
        return tasks.find { it.id == taskId }
    }

    fun getStateFlow() = boardState.asStateFlow()

    fun getStatistics(): BoardStatistics {
        return BoardStatistics(
            totalTasks = tasks.size,
            completedTasks = tasks.count { it.status == KanbanTask.Status.DONE },
            inProgressTasks = tasks.count { it.status == KanbanTask.Status.IN_PROGRESS },
            columnStats = columns.associate { col ->
                col.id to ColumnStats(
                    name = col.name,
                    taskCount = tasks.count { it.currentColumnId == col.id }
                )
            }
        )
    }

    private fun getColumnStatus(columnId: String): KanbanTask.Status {
        return columns.find { it.id == columnId }?.status ?: KanbanTask.Status.TODO
    }

    private fun updateState() {
        boardState.value = BoardState(columns.toList(), tasks.toList())
    }

    data class BoardState(
        val columns: List<KanbanColumn>,
        val tasks: List<KanbanTask>
    )

    data class BoardStatistics(
        val totalTasks: Int,
        val completedTasks: Int,
        val inProgressTasks: Int,
        val columnStats: Map<String, ColumnStats>
    )

    data class ColumnStats(
        val name: String,
        val taskCount: Int
    )
}

class KanbanColumn(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val status: KanbanTask.Status,
    val workerRole: String? = null,
    val entryCondition: (KanbanTask) -> Boolean = { true },
    val exitCondition: (KanbanTask) -> Boolean = { true }
)

class KanbanTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    var currentColumnId: String,
    var status: Status = Status.TODO,
    val assignee: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueDate: Long? = null,
    val tags: List<String> = emptyList(),
    val conversationHistory: List<ConversationEntry> = emptyList()
) {
    enum class Status {
        TODO, IN_PROGRESS, REVIEW, DONE
    }

    enum class Priority {
        LOW, MEDIUM, HIGH, URGENT
    }

    fun addConversationEntry(entry: ConversationEntry) {
        conversationHistory.toMutableList().add(entry)
    }

    data class ConversationEntry(
        val agentName: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
}

class WorkerRegistry {

    private val logger = LoggerFactory.getLogger(WorkerRegistry::class.java)
    private val workers = ConcurrentHashMap<String, Worker>()
    private val capabilityRegistry = CapabilityRegistry.getInstance()

    companion object {
        @Volatile
        private var instance: WorkerRegistry? = null

        fun initialize() {
            if (instance == null) {
                synchronized(this) {
                    instance = WorkerRegistry()
                }
            }
        }

        fun getInstance(): WorkerRegistry {
            return instance ?: throw IllegalStateException("WorkerRegistry not initialized")
        }
    }

    fun registerWorker(worker: Worker) {
        capabilityRegistry.register(com.apex.agent.core.extension.Capability(
            name = "worker.${worker.name}",
            level = FootprintLevel.PLUGIN,
            description = worker.description
        ))
        workers[worker.id] = worker
        logger.info("Registered worker: ${worker.name}")
    }

    fun unregisterWorker(workerId: String) {
        workers.remove(workerId)
    }

    fun getWorker(workerId: String): Worker? {
        return workers[workerId]
    }

    fun getWorkersByRole(role: String): List<Worker> {
        return workers.values.filter { it.role == role }
    }

    fun getAllWorkers(): List<Worker> {
        return workers.values.toList()
    }

    fun getAvailableWorkers(): List<Worker> {
        return workers.values.filter { it.isAvailable }
    }

    data class Worker(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val role: String,
        val description: String,
        val capabilities: List<String>,
        var isAvailable: Boolean = true,
        val maxConcurrentTasks: Int = 1
    )
}

class TaskDispatcher {

    private val logger = LoggerFactory.getLogger(TaskDispatcher::class.java)
    private val workerRegistry = WorkerRegistry.getInstance()
    private val kanbanBoard = KanbanBoard.getInstance()

    companion object {
        @Volatile
        private var instance: TaskDispatcher? = null

        fun initialize() {
            if (instance == null) {
                synchronized(this) {
                    instance = TaskDispatcher()
                }
            }
        }

        fun getInstance(): TaskDispatcher {
            return instance ?: throw IllegalStateException("TaskDispatcher not initialized")
        }
    }

    suspend fun dispatchTask(task: KanbanTask): DispatchResult {
        return withContext(Dispatchers.Default) {
            val targetColumn = kanbanBoard.getStateFlow().value.columns.find { it.id == task.currentColumnId }
            
            targetColumn?.workerRole?.let { role ->
                val workers = workerRegistry.getWorkersByRole(role)
                val availableWorker = selectWorker(workers, task)
                
                availableWorker?.let {
                    logger.info("Dispatching task ${task.id} to worker ${it.name}")
                    return@withContext DispatchResult(
                        success = true,
                        workerId = it.id,
                        workerName = it.name
                    )
                }
                
                logger.warn("No available worker for role: ${role}")
                return@withContext DispatchResult(
                    success = false,
                    error = "No available worker for role: ${role}"
                )
            }
            
            DispatchResult(success = true)
        }
    }

    suspend fun processTasks() {
        withContext(Dispatchers.Default) {
            val state = kanbanBoard.getStateFlow().value
            
            for (column in state.columns) {
                val tasks = state.tasks.filter { 
                    it.currentColumnId == column.id && 
                    it.status == KanbanTask.Status.TODO 
                }
                
                for (task in tasks) {
                    dispatchTask(task)
                }
            }
        }
    }

    private fun selectWorker(workers: List<WorkerRegistry.Worker>, task: KanbanTask): WorkerRegistry.Worker? {
        val availableWorkers = workers.filter { it.isAvailable }
        
        if (availableWorkers.isEmpty()) {
            return null
        }

        return when (task.priority) {
            KanbanTask.Priority.URGENT -> availableWorkers.firstOrNull()
            KanbanTask.Priority.HIGH -> availableWorkers.firstOrNull()
            else -> availableWorkers.minByOrNull { 
                kanbanBoard.getTasksByColumn(task.currentColumnId).count { it.assignee == it.id }
            }
        }
    }

    data class DispatchResult(
        val success: Boolean,
        val workerId: String? = null,
        val workerName: String? = null,
        val error: String? = null
    )
}
