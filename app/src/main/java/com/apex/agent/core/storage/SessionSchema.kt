package com.apex.agent.core.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话实体 - 表示一个独立的对话会话
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["parentSessionId"]),
        Index(value = ["isActive"])
    ]
)
data class SessionEntity(
    @PrimaryKey
    val id: String,
    
    /** 会话标题 */
    val title: String,
    
    /** 父会话ID - 用于会话分裂�?*/
    val parentSessionId: String?,
    
    /** 分裂点消息ID - 记录从哪个消息开始分�?*/
    val splitFromMessageId: String?,
    
    /** 创建时间�?*/
    val createdAt: Long,
    
    /** 最后更新时间戳 */
    val updatedAt: Long,
    
    /** 会话是否活跃 */
    val isActive: Boolean = true,
    
    /** 会话元数�?(JSON格式�?*/
    val metadata: String? = null,
    
    /** LLM摘要 */
    val summary: String? = null,
    
    /** 模型名称 */
    val modelName: String? = null
)

/**
 * 消息实体 - 表示会话中的单条消息
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"]),
        Index(value = ["role"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    
    /** 所属会话ID */
    val sessionId: String,
    
    /** 消息角色: user/assistant/system/tool */
    val role: String,
    
    /** 消息内容 */
    val content: String,
    
    /** 工具调用结果 (如果有） */
    val toolCalls: String? = null,
    
    /** 工具名称 (如果有） */
    val toolName: String? = null,
    
    /** 创建时间�?*/
    val createdAt: Long,
    
    /** 消息token数量 */
    val tokenCount: Int? = null,
    
    /** 消息是否被压�摘要 */
    val isCompressed: Boolean = false,
    
    /** 关联的父消息ID (用于追踪�?*/
    val parentMessageId: String? = null
)

/**
 * FTS5搜索结果实体 - 用于全文搜索
 */
data class FTSSearchResult(
    val messageId: String,
    val sessionId: String,
    val content: String,
    val rank: Double,
    val sessionTitle: String?,
    val createdAt: Long
)

/**
 * 会话链节�?- 用于追踪会话分裂
 */
data class SessionChainNode(
    val sessionId: String,
    val parentSessionId: String?,
    val splitFromMessageId: String?,
    val createdAt: Long,
    val isActive: Boolean
)

/**
 * 批量运行实体 - 与主会话存储隔离
 */
@Entity(
    tableName = "batch_runs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["status"]),
        Index(value = ["batchRunId"])
    ]
)
data class BatchRunEntity(
    @PrimaryKey
    val id: String,
    
    /** 批量运行批次ID */
    val batchRunId: String,
    
    /** 任务描述 */
    val taskDescription: String,
    
    /** 状�? pending/running/completed/failed */
    val status: String,
    
    /** 创建时间 */
    val createdAt: Long,
    
    /** 开始时�?*/
    val startedAt: Long? = null,
    
    /** 完成时间 */
    val completedAt: Long? = null,
    
    /** 运行结果 (JSON) */
    val result: String? = null,
    
    /** 错误信息 */
    val errorMessage: String? = null
)

/**
 * RL轨迹实体 - 强化学习轨迹数据
 */
@Entity(
    tableName = "rl_trajectories",
    foreignKeys = [
        ForeignKey(
            entity = BatchRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchRunId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["batchRunId"]),
        Index(value = ["createdAt"])
    ]
)
data class RLTrajectoryEntity(
    @PrimaryKey
    val id: String,
    
    /** 关联的批量运行ID */
    val batchRunId: String,
    
    /** 步骤序号 */
    val stepIndex: Int,
    
    /** 状�?*/
    val state: String,
    
    /** 动作 */
    val action: String,
    
    /** 奖励 */
    val reward: Double,
    
    /** 下一个状�?*/
    val nextState: String,
    
    /** 是否完成 */
    val isDone: Boolean,
    
    /** 创建时间 */
    val createdAt: Long
)
