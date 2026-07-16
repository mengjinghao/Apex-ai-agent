package com.apex.agent.domain.interfaces

/**
 * 协作结果密封类，封装操作成功/失败状态
 */
sealed class CollaborationResult<out T> {
    data class Success<T>(val data: T) : CollaborationResult<T>()
    data class Failure(val error: CollaborationError) : CollaborationResult<Nothing>()
}

/**
 * 协作错误信息
 * @param code 错误码
 * @param message 错误消息
 * @param agentId 关联的Agent ID（可选）
 */
data class CollaborationError(val code: String, val message: String, val agentId: String? = null)

/**
 * 协作任务定义
 * @param id 任务唯一标识
 * @param type 任务类型
 * @param payload 任务载荷数据
 * @param priority 优先级（数值越大优先级越高）
 * @param assignedAgentIds 已分配的Agent ID列表
 * @param createdAt 创建时间戳
 */
data class CollaborationTask(
    val id: String,
    val type: String,
    val payload: Map<String, String>,
    val priority: Int = 0,
    val assignedAgentIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Agent能力描述
 * @param agentId Agent唯一标识
 * @param skills 技能列表
 * @param load 当前负载 (0.0 - 1.0)
 * @param status 当前状态
 */
data class AgentCapability(
    val agentId: String,
    val skills: List<String>,
    val load: Float = 0f,
    val status: AgentStatus = AgentStatus.IDLE
)

enum class AgentStatus { IDLE, BUSY, ERROR, OFFLINE }

/**
 * 协作引擎核心接口
 * 定义任务提交、取消、状态查询以及Agent注册等核心操作
 */
interface ICollaborationEngine {
    suspend fun submitTask(task: CollaborationTask): String
    suspend fun cancelTask(taskId: String): Boolean
    suspend fun getTaskStatus(taskId: String): TaskStatus?
    suspend fun registerAgent(capability: AgentCapability): Boolean
    suspend fun unregisterAgent(agentId: String): Boolean
    suspend fun getAvailableAgents(): List<AgentCapability>
    fun addListener(listener: CollaborationListener)
    fun removeListener(listener: CollaborationListener)
}

/**
 * 协作事件监听器
 */
interface CollaborationListener {
    fun onTaskStarted(taskId: String, agentId: String)
    fun onTaskProgress(taskId: String, progress: Float, message: String)
    fun onTaskCompleted(taskId: String, result: String)
    fun onTaskFailed(taskId: String, error: String)
    fun onAgentStatusChanged(agentId: String, status: AgentStatus)
}

/**
 * 任务状态信息
 */
data class TaskStatus(
    val taskId: String,
    val state: TaskState,
    val assignedAgentId: String? = null,
    val progress: Float = 0f,
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null
)

/**
 * 任务状态枚举
 */
enum class TaskState { PENDING, ASSIGNED, RUNNING, COMPLETED, FAILED, CANCELLED }
