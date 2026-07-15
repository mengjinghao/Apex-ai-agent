package com.apex.agent.core.workflow.enhanced.checkpoint

import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 检查点 - 工作流执行的持久化快照
 *
 * 参照 LangGraph 的 Checkpointer（InMemorySaver / SqliteSaver / PostgresSaver）
 * 与 Temporal 的 Event Sourcing 持久化机制
 *
 * 用途：
 * - Android 进程被杀后恢复未完成的工作流
 * - 支持断点续传（暂停 / 恢复）
 * - 调试时回放历史执行
 */
@Serializable
data class Checkpoint(
    val threadId: String,                 // 一次工作流执行的唯一标识
    val checkpointId: String,             // 检查点唯一 ID
    val parentCheckpointId: String?,      // 父检查点（链式）
    val workflowId: String,
    val workflowVersion: Int,
    val nodeId: String,                   // 当前执行到的节点
    val nodeState: CheckpointNodeState,   // 节点状态
    val variables: Map<String, String>,   // 序列化后的上下文变量
    val pendingInterrupts: List<String>,  // 待处理的 interrupt ID（HITL）
    val executionPath: List<String>,      // 已执行的节点路径
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

enum class CheckpointNodeState {
    PENDING, RUNNING, WAITING_HUMAN, WAITING_EVENT, COMPLETED, FAILED, CANCELLED
}

/**
 * 检查点存储接口
 */
interface Checkpointer {
    /** 保存检查点 */
    suspend fun save(checkpoint: Checkpoint)

    /** 加载最新检查点 */
    suspend fun latest(threadId: String): Checkpoint?

    /** 加载指定检查点 */
    suspend fun load(threadId: String, checkpointId: String): Checkpoint?

    /** 列出某线程的所有检查点（时间顺序） */
    suspend fun list(threadId: String): List<Checkpoint>

    /** 删除某线程的所有检查点 */
    suspend fun delete(threadId: String)

    /** 列出所有活跃线程（有未完成执行） */
    suspend fun activeThreads(): List<String>

    /** 清理超过 maxAge 的检查点 */
    suspend fun cleanup(maxAgeMs: Long)
}

/**
 * 内存检查点实现 - 适合测试与短期运行
 *
 * 生产环境建议替换为 RoomCheckpointer（基于 Room/SQLite 持久化）
 */
class InMemoryCheckpointer(
    private val maxCheckpointsPerThread: Int = 100
) : Checkpointer {

    private val storage = ConcurrentHashMap<String, MutableList<Checkpoint>>()

    override suspend fun save(checkpoint: Checkpoint) {
        val list = storage.computeIfAbsent(checkpoint.threadId) { mutableListOf() }
        synchronized(list) {
            list.add(checkpoint)
            while (list.size > maxCheckpointsPerThread) list.removeAt(0)
        }
    }

    override suspend fun latest(threadId: String): Checkpoint? {
        val list = storage[threadId] ?: return null
        return synchronized(list) { list.maxByOrNull { it.createdAt } }
    }

    override suspend fun load(threadId: String, checkpointId: String): Checkpoint? {
        val list = storage[threadId] ?: return null
        return synchronized(list) { list.find { it.checkpointId == checkpointId } }
    }

    override suspend fun list(threadId: String): List<Checkpoint> {
        val list = storage[threadId] ?: return emptyList()
        return synchronized(list) { list.toList().sortedBy { it.createdAt } }
    }

    override suspend fun delete(threadId: String) {
        storage.remove(threadId)
    }

    override suspend fun activeThreads(): List<String> {
        return storage.filter { (_, list) ->
            list.any { it.nodeState in setOf(
                CheckpointNodeState.RUNNING,
                CheckpointNodeState.WAITING_HUMAN,
                CheckpointNodeState.WAITING_EVENT,
                CheckpointNodeState.PENDING
            )}
        }.keys.toList()
    }

    override suspend fun cleanup(maxAgeMs: Long) {
        val threshold = System.currentTimeMillis() - maxAgeMs
        storage.forEach { (tid, list) ->
            synchronized(list) {
                list.removeAll { it.createdAt < threshold }
            }
        if (list.isEmpty()) storage.remove(tid)
        }
    }
}

/**
 * 检查点管理器 - 协调保存 / 恢复 / 重放
 */
class CheckpointManager(
    private val checkpointer: Checkpointer
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 从检查点恢复工作流执行所需的信息
     */
    suspend fun prepareResume(checkpoint: Checkpoint): ResumePlan {
        return ResumePlan(
            threadId = checkpoint.threadId,
            resumeFromNodeId = checkpoint.nodeId,
            variables = checkpoint.variables,
            executionPath = checkpoint.executionPath,
            pendingInterrupts = checkpoint.pendingInterrupts
        )
    }

    /**
     * 序列化工作流上下文为可存储的字符串 Map
     */
    fun serializeContext(context: Map<String, Any>): Map<String, String> {
        return context.mapValues { (_, v) ->
            when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> json.encodeToString(JsonElementSerializer, v)
            }
        }
    }

    suspend fun saveCheckpoint(
        threadId: String,
        parentCheckpointId: String?,
        workflow: EnhancedWorkflow,
        nodeId: String,
        nodeState: CheckpointNodeState,
        variables: Map<String, Any>,
        pendingInterrupts: List<String> = emptyList(),
        executionPath: List<String> = emptyList()
    ): Checkpoint {
        val checkpoint = Checkpoint(
            threadId = threadId,
            checkpointId = "cp_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            parentCheckpointId = parentCheckpointId,
            workflowId = workflow.id,
            workflowVersion = workflow.version,
            nodeId = nodeId,
            nodeState = nodeState,
            variables = serializeContext(variables),
            pendingInterrupts = pendingInterrupts,
            executionPath = executionPath
        )
        checkpointer.save(checkpoint)
        return checkpoint
    }
}

data class ResumePlan(
    val threadId: String,
    val resumeFromNodeId: String,
    val variables: Map<String, String>,
    val executionPath: List<String>,
    val pendingInterrupts: List<String>
)

/**
 * 简易 JSON 元素序列化占位（生产环境用 kotlinx.serialization JsonElement）
 */
private object JsonElementSerializer
