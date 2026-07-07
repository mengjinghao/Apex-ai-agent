package com.apex.agent.core.kanban

import com.apex.agent.core.collaboration.AgentCollaborationFramework.AgentRole
import java.util.UUID

/**
 * KanbanTask - 任务模型
 *
 * 看板中的任务单元，支�?
 * - 多阶段状态流�? * - Agent 分配和跟�? * - 优先级和依赖管理
 */
class KanbanTask(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var description: String = "",
    var columnId: String,
    var order: Int = 0,
    // 任务状�?    var status: KanbanTaskStatus = KanbanTaskStatus.PENDING,
    // 分配信息
    var assignedWorkerId: String? = null,
    var assignedAgentId: String? = null,
    var assignedAgentName: String? = null,
    var assignedRole: AgentRole? = null,
    // 任务属�?    var priority: Int = 3,  // 1-5, 1 最�?    var taskType: String = "general",
    val tags: MutableList<String> = mutableListOf(),
    // 依赖关系
    val dependencies: MutableList<String> = mutableListOf(),  // 依赖的任�?ID
    val blockingTasks: MutableList<String> = mutableListOf(),  // 阻塞此任务的任务 ID
    // 结果和输�?    var result: TaskResult? = null,
    var outputArtifacts: MutableList<TaskArtifact> = mutableListOf(),
    // 时间追踪
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var estimatedMinutes: Int = 60,
    var actualMinutes: Int = 0,
    // 协作跟踪
    var collaborationHistory: MutableList<CollaborationEvent> = mutableListOf()
) {
    /**
     * 分配�?Worker/Agent
     */
    fun assignTo(workerId: String, agentId: String? = null, agentName: String? = null, role: AgentRole? = null) {
        assignedWorkerId = workerId
        assignedAgentId = agentId
        assignedAgentName = agentName
        assignedRole = role
        status = KanbanTaskStatus.ASSIGNED
        updatedAt = System.currentTimeMillis()
        addCollaborationEvent(CollaborationEvent.Type.ASSIGNED, "Assigned to worker ${workerId}")
    }

    /**
     * 开始执�?     */
    fun startExecution() {
        if (status != KanbanTaskStatus.ASSIGNED && status != KanbanTaskStatus.PENDING) {
            return
        }
        status = KanbanTaskStatus.IN_PROGRESS
        startedAt = System.currentTimeMillis()
        updatedAt = System.currentTimeMillis()
        addCollaborationEvent(CollaborationEvent.Type.STARTED, "Task execution started")
    }

    /**
     * 完成任务
     */
    fun complete(resultData: String? = null, artifacts: List<TaskArtifact> = emptyList()) {
        status = KanbanTaskStatus.COMPLETED
        completedAt = System.currentTimeMillis()
        updatedAt = System.currentTimeMillis()
        actualMinutes = ((completedAt!! - (startedAt ?: createdAt)) / 60000).toInt()

        result = resultData?.let { TaskResult(success = true, data = it) }
        outputArtifacts.addAll(artifacts)

        addCollaborationEvent(CollaborationEvent.Type.COMPLETED, "Task completed")
    }

    /**
     * 标记失败
     */
    fun fail(reason: String) {
        status = KanbanTaskStatus.FAILED
        completedAt = System.currentTimeMillis()
        updatedAt = System.currentTimeMillis()
        result = TaskResult(success = false, error = reason)
        addCollaborationEvent(CollaborationEvent.Type.FAILED, "Task failed: ${reason}")
    }

    /**
     * 阻塞任务
     */
    fun block(blockedBy: String) {
        status = KanbanTaskStatus.BLOCKED
        blockingTasks.add(blockedBy)
        updatedAt = System.currentTimeMillis()
        addCollaborationEvent(CollaborationEvent.Type.BLOCKED, "Blocked by task ${blockedBy}")
    }

    /**
     * 解除阻塞
     */
    fun unblock() {
        status = KanbanTaskStatus.PENDING
        blockingTasks.clear()
        updatedAt = System.currentTimeMillis()
        addCollaborationEvent(CollaborationEvent.Type.UNBLOCKED, "Task unblocked")
    }

    /**
     * 添加协作文本事件
     */
    fun addCollaborationEvent(type: CollaborationEvent.Type, message: String, agentId: String? = null) {
        collaborationHistory.add(
            CollaborationEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                message = message,
                agentId = agentId,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * 获取执行时长（分钟）
     */
    fun getExecutionMinutes(): Int {
        val start = startedAt ?: return 0
        val end = completedAt ?: System.currentTimeMillis()
        return ((end - start) / 60000).toInt()
    }

    /**
     * 检查是否有未完成的依赖
     */
    fun hasPendingDependencies(): Boolean {
        return dependencies.any { depId ->
            // 这里依赖外部的看板状态来判断
            // 实际使用时需要传入相关任务的状�?            blockingTasks.contains(depId)
        }
    }

    /**
     * 获取进度百分�?     */
    fun getProgress(): Int {
        return when (status) {
            KanbanTaskStatus.PENDING -> 0
            KanbanTaskStatus.ASSIGNED -> 10
            KanbanTaskStatus.IN_PROGRESS -> 50
            KanbanTaskStatus.COMPLETED -> 100
            KanbanTaskStatus.FAILED -> 0
            KanbanTaskStatus.BLOCKED -> 25
            KanbanTaskStatus.CANCELLED -> 0
        }
    }

    companion object {
        /**
         * 创建简单任�?         */
        fun createSimple(title: String, columnId: String, description: String = ""): KanbanTask {
            return KanbanTask(
                title = title,
                description = description,
                columnId = columnId
            )
        }
    }
}

/**
 * 任务状态枚�? */
enum class KanbanTaskStatus {
    PENDING,       // 待处�?    ASSIGNED,      // 已分�?    IN_PROGRESS,   // 进行�?    COMPLETED,     // 已完�?    FAILED,        // 失败
    BLOCKED,       // 阻塞
    CANCELLED      // 取消
}

/**
 * 任务结果
 */
data class TaskResult(
    val success: Boolean,
    val data: String? = null,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 任务产物
 */
data class TaskArtifact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,  // "file", "data", "link", etc.
    val path: String? = null,
    val content: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 协作文本事件
 */
data class CollaborationEvent(
    val id: String,
    val type: Type,
    val message: String,
    val agentId: String?,
    val timestamp: Long
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
