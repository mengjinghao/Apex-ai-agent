package com.apex.agent.core.kanban

import com.apex.agent.core.multiagent.AgentRole
import com.apex.core.extension.CapabilityDeclaration
import com.apex.agent.core.extension.FootprintLevel
import com.apex.util.AppLogger
import java.util.UUID

/**
 * WorkerRegistry - Worker 插件发现系统
 *
 * 从插件系统发现可用的 Worker，支?
 * - 动?Worker 注册
 * - 基于能力和角色的匹配
 * - 负载均衡
 */
class WorkerRegistry private constructor() {

    companion object {
        private const val TAG = "WorkerRegistry"

        @Volatile
        private var INSTANCE: WorkerRegistry? = null

        fun getInstance(): WorkerRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkerRegistry().also { INSTANCE = it }
            }
        }
    }

    /**
     * Worker 配置接口
     */
    interface Worker {
        val id: String
        val name: String
        val description: String
        val supportedRoles: List<AgentRole>
        val supportedTaskTypes: List<String>
        val capabilities: List<String>
        val isActive: Boolean
        val maxConcurrentTasks: Int
        val currentLoad: Int

        suspend fun execute(task: KanbanTask, context: TaskExecutionContext): TaskResult
        suspend fun canHandle(task: KanbanTask): Boolean
        fun getWorkload(): Float = currentLoad.toFloat() / maxConcurrentTasks
    }

    /**
     * 任务执行上下?     */
    data class TaskExecutionContext(
        val board: KanbanBoard,
        val column: KanbanColumn,
        val collaborationFramework: com.apex.agent.core.collaboration.AgentCollaborationFramework? = null,
        val additionalData: Map<String, Any> = emptyMap()
    )

    private val registeredWorkers = mutableMapOf<String, Worker>()
    private val workerListeners = mutableListOf<WorkerChangeListener>()

    /**
     * 注册 Worker (使用 PLUGIN 级别?     */
    fun registerWorker(worker: Worker): RegistrationResult {
        if (registeredWorkers.containsKey(worker.id)) {
            AppLogger.w(TAG, "Worker ${worker.id} already registered")
            return RegistrationResult.ALREADY_EXISTS
        }

        // 使用 PLUGIN 级别进行能力声明
        val capability = CapabilityDeclaration(
            name = "kanban_worker_${worker.id}",
            level = FootprintLevel.PLUGIN,
            description = "Kanban Worker: ${worker.name}",
            dependencies = worker.capabilities,
            isOptional = true
        )

        registeredWorkers[worker.id] = worker
        notifyListeners(worker, ChangeType.REGISTERED)
        AppLogger.d(TAG, "Registered worker: ${worker.name} (${worker.id})")

        return RegistrationResult.SUCCESS
    }

    /**
     * 注销 Worker
     */
    fun unregisterWorker(workerId: String): Boolean {
        val worker = registeredWorkers.remove(workerId)
        if (worker != null) {
            notifyListeners(worker, ChangeType.UNREGISTERED)
            AppLogger.d(TAG, "Unregistered worker: ${workerId}")
            return true
        }
        return false
    }

    /**
     * 获取 Worker
     */
    fun getWorker(workerId: String): Worker? {
        return registeredWorkers[workerId]
    }

    /**
     * 获取所有活跃的 Worker
     */
    fun getActiveWorkers(): List<Worker> {
        return registeredWorkers.values.filter { it.isActive }
    }

    /**
     * 根据角色查找合适的 Worker
     */
    fun findWorkersByRole(role: AgentRole): List<Worker> {
        return getActiveWorkers().filter { worker ->
            worker.supportedRoles.contains(role)
        }.sortedBy { it.getWorkload() }
    }

    /**
     * 根据任务类型查找合适的 Worker
     */
    fun findWorkersByTaskType(taskType: String): List<Worker> {
        return getActiveWorkers().filter { worker ->
            worker.supportedTaskTypes.any { it.equals(taskType, ignoreCase = true) }
        }.sortedBy { it.getWorkload() }
    }

    /**
     * 根据能力查找 Worker
     */
    fun findWorkersByCapabilities(requiredCapabilities: List<String>): List<Worker> {
        return getActiveWorkers().filter { worker ->
            requiredCapabilities.all { required ->
                worker.capabilities.any { cap ->
                    cap.equals(required, ignoreCase = true)
                }
            }
        }.sortedBy { it.getWorkload() }
    }

    /**
     * 为任务找到最?Worker (负载均衡?     */
    fun findBestWorker(task: KanbanTask, preferredRole: AgentRole? = null): Worker? {
        val candidates = when {
            preferredRole != null -> findWorkersByRole(preferredRole)
            task.assignedRole != null -> findWorkersByRole(task.assignedRole!!)
            else -> {
                // 尝试基于任务类型
                val byType = findWorkersByTaskType(task.taskType)
                if (byType.isNotEmpty()) byType
                else getActiveWorkers()
            }
        }

        // 过滤有能力的 Worker
        val capable = candidates.filter { worker ->
            worker.capabilities.any { cap ->
                task.tags.any { tag -> cap.contains(tag, ignoreCase = true) }
            } || task.tags.isEmpty()
        }

        // 选择负载最低的
        return capable.minByOrNull { it.getWorkload() }
    }

    /**
     * 为列找到合适的 Worker
     */
    fun findWorkerForColumn(column: KanbanColumn): Worker? {
        // 首先尝试列指定的 Worker
        column.assignedWorker?.let { workerId ->
            return registeredWorkers[workerId]?.takeIf { it.isActive }
        }

        // 根据列要求的角色查找
        if (column.requiredAgentRoles.isNotEmpty()) {
            for (role in column.requiredAgentRoles) {
                val workers = findWorkersByRole(role)
                if (workers.isNotEmpty()) {
                    return workers.first()
                }
            }
        }

        // 根据列要求的能力查找
        if (column.requiredCapabilities.isNotEmpty()) {
            val workers = findWorkersByCapabilities(column.requiredCapabilities)
            if (workers.isNotEmpty()) {
                return workers.first()
            }
        }

        return getActiveWorkers().minByOrNull { it.getWorkload() }
    }

    /**
     * 更新 Worker 负载
     */
    fun updateWorkerLoad(workerId: String, newLoad: Int) {
        registeredWorkers[workerId]?.let { worker ->
            AppLogger.d(TAG, "Updated load for worker ${workerId}: ${newLoad}")
        }
    }

    /**
     * 获取所?Worker 的统计信?     */
    fun getStatistics(): WorkerStatistics {
        val workers = registeredWorkers.values.toList()
        return WorkerStatistics(
            totalWorkers = workers.size,
            activeWorkers = workers.count { it.isActive },
            averageLoad = if (workers.isEmpty()) 0f else workers.map { it.getWorkload() }.average().toFloat(),
            roleDistribution = AgentRole.entries.associateWith { role ->
                workers.count { it.supportedRoles.contains(role) }
            }
        )
    }

    /**
     * 添加 Worker 变化监听?     */
    fun addListener(listener: WorkerChangeListener) {
        workerListeners.add(listener)
    }

    /**
     * 移除 Worker 变化监听?     */
    fun removeListener(listener: WorkerChangeListener) {
        workerListeners.remove(listener)
    }

    private fun notifyListeners(worker: Worker, changeType: ChangeType) {
        workerListeners.forEach { listener ->
            try {
                when (changeType) {
                    ChangeType.REGISTERED -> listener.onWorkerRegistered(worker)
                    ChangeType.UNREGISTERED -> listener.onWorkerUnregistered(worker)
                    ChangeType.LOAD_CHANGED -> listener.onWorkerLoadChanged(worker.id, worker.currentLoad)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying listener", e)
            }
        }
    }

    enum class RegistrationResult {
        SUCCESS,
        ALREADY_EXISTS,
        INVALID_CONFIGURATION,
        DEPENDENCY_MISSING
    }

    enum class ChangeType {
        REGISTERED,
        UNREGISTERED,
        LOAD_CHANGED
    }

    interface WorkerChangeListener {
        fun onWorkerRegistered(worker: Worker)
        fun onWorkerUnregistered(worker: Worker)
        fun onWorkerLoadChanged(workerId: String, newLoad: Int)
    }

    data class WorkerStatistics(
        val totalWorkers: Int,
        val activeWorkers: Int,
        val averageLoad: Float,
        val roleDistribution: Map<AgentRole, Int>
    )
}

/**
 * 简?Worker 实现模板
 */
abstract class BaseWorker(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String,
    override val description: String,
    override val supportedRoles: List<AgentRole>,
    override val supportedTaskTypes: List<String>,
    override val capabilities: List<String>,
    override val maxConcurrentTasks: Int = 3
) : WorkerRegistry.Worker {

    override var currentLoad: Int = 0
        protected set

    override val isActive: Boolean = true

    override suspend fun canHandle(task: KanbanTask): Boolean {
        return currentLoad < maxConcurrentTasks &&
                (supportedRoles.contains(task.assignedRole) ||
                        supportedTaskTypes.contains(task.taskType) ||
                        task.tags.any { tag -> capabilities.any { cap -> cap.contains(tag, ignoreCase = true) } })
    }
}
