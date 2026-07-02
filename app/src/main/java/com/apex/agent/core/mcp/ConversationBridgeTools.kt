package com.apex.agent.core.mcp

import android.content.Context
import com.apex.agent.core.permissions.rbac.RbacManager
import com.apex.agent.core.storage.SessionDatabase
import com.apex.agent.core.storage.SessionEntity
import com.apex.agent.core.storage.MessageEntity
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 对话桥接工具 - 提供 MCP 协议对话接口
 * 
 * 实现以下工具�? * - conversations_list: 列出对话
 * - conversation_get: 获取对话
 * - messages_read: 读取消息
 * - messages_send: 发送消�? * - events_poll: 轮询事件
 * - events_wait: 等待事件
 * - permissions_list_open: 列出待批准权�? * - permissions_respond: 响应权限
 */
class ConversationBridgeTools(private val context: Context) {
    
    companion object {
        private const val TAG = "ConversationBridgeTools"
        
        @Volatile
        private var INSTANCE: ConversationBridgeTools? = null
        
        fun getInstance(context: Context): ConversationBridgeTools {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConversationBridgeTools(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sessionDatabase: SessionDatabase by lazy {
        SessionDatabase.getInstance(context)
    }
    
    /** 待处理事件队�?*/
    private val eventQueue = mutableListOf<MCPCEvent>()
    
    /** 待批准权限队�?*/
    private val pendingPermissions = mutableListOf<MCPCPermission>()

    private val rbacManager: RbacManager? by lazy {
        try { RbacManager.getInstance(context) } catch (e: Exception) { null }
    }
    
    /**
     * 列出所有对�?     * 
     * @param limit 返回数量限制
     * @param offset 偏移�?     * @return 对话列表结果
     */
    suspend fun conversationsList(limit: Int = 50, offset: Int = 0): ConversationListResult {
        return withContext(Dispatchers.IO) {
            try {
                val sessionsFlow = sessionDatabase.sessionDao().getAllSessions()
                val sessions = sessionsFlow.first()
                
                val conversations = sessions
                    .drop(offset)
                    .take(limit)
                    .map { session ->
                        ConversationInfo(
                            id = session.id,
                            title = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            isActive = session.isActive,
                            messageCount = sessionDatabase.messageDao().getMessageCount(session.id)
                        )
                    }
                
                ConversationListResult(
                    success = true,
                    conversations = conversations,
                    total = sessions.size
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "列出对话失败: ${e.message}", e)
                ConversationListResult(
                    success = false,
                    error = "Failed to list conversations: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 获取指定对话
     * 
     * @param conversationId 对话ID
     * @return 对话详情
     */
    suspend fun conversationGet(conversationId: String): ConversationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (conversationId.isEmpty()) {
                    return@withContext ConversationResult(
                        success = false,
                        error = "conversation_id is required"
                    )
                }
                
                val session = sessionDatabase.sessionDao().getSessionById(conversationId)
                
                if (session == null) {
                    return@withContext ConversationResult(
                        success = false,
                        error = "Conversation not found"
                    )
                }
                
                ConversationResult(
                    success = true,
                    conversation = ConversationDetail(
                        id = session.id,
                        title = session.title,
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt,
                        isActive = session.isActive,
                        parentSessionId = session.parentSessionId,
                        summary = session.summary,
                        modelName = session.modelName,
                        metadata = session.metadata?.let { parseMetadata(it) }
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取对话失败: ${e.message}", e)
                ConversationResult(
                    success = false,
                    error = "Failed to get conversation: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 读取对话中的消息
     * 
     * @param conversationId 对话ID
     * @param limit 消息数量限制
     * @param beforeId 只获取此消息之前的消�?     * @return 消息列表
     */
    suspend fun messagesRead(conversationId: String, limit: Int = 50, beforeId: String? = null): MessagesReadResult {
        return withContext(Dispatchers.IO) {
            try {
                if (conversationId.isEmpty()) {
                    return@withContext MessagesReadResult(
                        success = false,
                        error = "conversation_id is required"
                    )
                }
                
                val messages = if (beforeId != null) {
                    // 获取指定消息之前的消�?                    val allMessages = sessionDatabase.messageDao().getMessagesBySessionIdSync(conversationId)
                    val beforeIndex = allMessages.indexOfFirst { it.id == beforeId }
                    if (beforeIndex > 0) {
                        allMessages.subList(0, minOf(beforeIndex, limit))
                    } else {
                        allMessages.take(limit)
                    }
                } else {
                    sessionDatabase.messageDao().getRecentMessages(conversationId, limit).reversed()
                }
                
                val messageList = messages.map { message ->
                    MessageInfo(
                        id = message.id,
                        role = message.role,
                        content = message.content,
                        createdAt = message.createdAt,
                        parentMessageId = message.parentMessageId,
                        toolCalls = message.toolCalls?.let { parseToolCalls(it) }
                    )
                }
                
                MessagesReadResult(
                    success = true,
                    messages = messageList,
                    total = sessionDatabase.messageDao().getMessageCount(conversationId)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "读取消息失败: ${e.message}", e)
                MessagesReadResult(
                    success = false,
                    error = "Failed to read messages: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 发送消息到对话
     * 
     * @param conversationId 对话ID
     * @param content 消息内容
     * @param role 消息角色 (user/assistant/system)
     * @return 发送结�?     */
    suspend fun messagesSend(conversationId: String, content: String, role: String = "user"): MessageSendResult {
        return withContext(Dispatchers.IO) {
            try {
                if (conversationId.isEmpty() || content.isEmpty()) {
                    return@withContext MessageSendResult(
                        success = false,
                        error = "conversation_id and content are required"
                    )
                }
                
                // 检查对话是否存�?                val session = sessionDatabase.sessionDao().getSessionById(conversationId)
                if (session == null) {
                    return@withContext MessageSendResult(
                        success = false,
                        error = "Conversation not found"
                    )
                }
                
                val messageId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                val message = MessageEntity(
                    id = messageId,
                    sessionId = conversationId,
                    role = role,
                    content = content,
                    createdAt = timestamp
                )
                
                sessionDatabase.messageDao().insertMessage(message)
                
                // 更新会话时间
                sessionDatabase.sessionDao().updateSession(
                    session.copy(updatedAt = timestamp)
                )
                
                // 添加事件
                addEvent(MCPCEvent(
                    type = "message_created",
                    conversationId = conversationId,
                    messageId = messageId,
                    timestamp = timestamp
                ))
                
                MessageSendResult(
                    success = true,
                    messageId = messageId,
                    createdAt = timestamp
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送消息失�? ${e.message}", e)
                MessageSendResult(
                    success = false,
                    error = "Failed to send message: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 轮询事件
     * 
     * @param timeout 超时时间（毫秒）
     * @param since 从指定时间戳之后的事�?     * @return 事件列表
     */
    suspend fun eventsPoll(timeout: Int = 1000, since: Long? = null): EventsResult {
        return withContext(Dispatchers.IO) {
            try {
                val events = synchronized(eventQueue) {
                    val filtered = if (since != null) {
                        eventQueue.filter { it.timestamp > since }
                    } else {
                        eventQueue.toList()
                    }
                    eventQueue.removeAll(filtered.toSet())
                    filtered
                }
                
                EventsResult(
                    success = true,
                    events = events
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "轮询事件失败: ${e.message}", e)
                EventsResult(
                    success = false,
                    error = "Failed to poll events: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 等待事件
     * 
     * @param timeout 超时时间（毫秒）
     * @return 事件列表
     */
    suspend fun eventsWait(timeout: Int = 5000): EventsResult {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val deadline = startTime + timeout
                
                while (System.currentTimeMillis() < deadline) {
                    val events = synchronized(eventQueue) {
                        if (eventQueue.isNotEmpty()) {
                            val result = eventQueue.toList()
                            eventQueue.clear()
                            return@synchronized result
                        }
                        null
                    }
                    
                    if (events != null) {
                        return@withContext EventsResult(
                            success = true,
                            events = events
                        )
                    }
                    
                    // 短暂等待后重�?                    kotlinx.coroutines.delay(100)
                }
                
                // 超时，返回空列表
                EventsResult(
                    success = true,
                    events = emptyList()
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "等待事件失败: ${e.message}", e)
                EventsResult(
                    success = false,
                    error = "Failed to wait for events: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 列出待批准的权限
     * 
     * @return 权限列表
     */
    suspend fun permissionsListOpen(): PermissionsListResult {
        return withContext(Dispatchers.IO) {
            try {
                PermissionsListResult(
                    success = true,
                    permissions = synchronized(pendingPermissions) {
                        pendingPermissions.toList()
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "列出权限失败: ${e.message}", e)
                PermissionsListResult(
                    success = false,
                    error = "Failed to list permissions: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 响应权限请求
     * 
     * @param permissionId 权限ID
     * @param approve 是否批准
     * @return 响应结果
     */
    suspend fun permissionsRespond(permissionId: String, approve: Boolean): PermissionRespondResult {
        return withContext(Dispatchers.IO) {
            try {
                if (permissionId.isEmpty()) {
                    return@withContext PermissionRespondResult(
                        success = false,
                        error = "permission_id is required"
                    )
                }
                
                val permission = synchronized(pendingPermissions) {
                    val index = pendingPermissions.indexOfFirst { it.id == permissionId }
                    if (index >= 0) {
                        pendingPermissions.removeAt(index)
                    } else {
                        null
                    }
                }
                
                if (permission == null) {
                    return@withContext PermissionRespondResult(
                        success = false,
                        error = "Permission not found"
                    )
                }
                
                // 批准时持久化�?RBAC
                if (approve) {
                    rbacManager?.let { rbac ->
                        try {
                            val rbacPermName = "mcp:${permission.type}"
                            val existingPerm = rbac.getRepository()
                                .getPermissionByName(rbacPermName)
                            if (existingPerm == null) {
                                rbac.getRepository().insertPermission(
                                    com.apex.agent.database.entity.Permission(
                                        name = rbacPermName,
                                        description = permission.description,
                                        category = "mcp"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "持久化MCP权限到RBAC失败: ${e.message}", e)
                        }
                    }
                }

                // 添加事件
                addEvent(MCPCEvent(
                    type = if (approve) "permission_approved" else "permission_denied",
                    conversationId = permission.conversationId,
                    timestamp = System.currentTimeMillis(),
                    data = mapOf("permission_id" to permissionId)
                ))
                
                PermissionRespondResult(
                    success = true,
                    permissionId = permissionId,
                    approved = approve
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "响应权限失败: ${e.message}", e)
                PermissionRespondResult(
                    success = false,
                    error = "Failed to respond to permission: ${e.message}"
                )
            }
        }
    }
    
    /**
     * 添加事件到队�?     */
    private fun addEvent(event: MCPCEvent) {
        synchronized(eventQueue) {
            eventQueue.add(event)
            // 限制队列大小
            if (eventQueue.size > 1000) {
                eventQueue.removeAt(0)
            }
        }
    }
    
    /**
     * 添加待批准权�?     */
    fun addPendingPermission(permission: MCPCPermission) {
        synchronized(pendingPermissions) {
            pendingPermissions.add(permission)
        }
    }
    
    /**
     * 解析元数�?JSON
     */
    private fun parseMetadata(metadata: String): Map<String, Any>? {
        return try {
            val json = JSONObject(metadata)
            json.toMap()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 解析工具调用 JSON
     */
    private fun parseToolCalls(toolCalls: String): List<Map<String, Any>>? {
        return try {
            val jsonArray = JSONArray(toolCalls)
            (0 until jsonArray.length()).map { jsonArray.getJSONObject(it).toMap() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * JSONObject 转换�?Map
     */
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = when (val value = get(key)) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toMapList()
                JSONObject.NULL -> ""
                else -> value
            }
        }
        return map
    }
    
    /**
     * JSONArray 转换�?List
     */
    private fun JSONArray.toMapList(): List<Any> {
        return (0 until length()).map { get(it) }
    }
}

// ==================== 数据�?====================

/**
 * MCP 事件
 */
data class MCPCEvent(
    val type: String,
    val conversationId: String,
    val messageId: String? = null,
    val timestamp: Long,
    val data: Map<String, Any>? = null
)

/**
 * MCP 权限
 */
data class MCPCPermission(
    val id: String,
    val type: String,
    val conversationId: String,
    val description: String,
    val createdAt: Long
)

// ==================== 结果数据�?====================

/**
 * 对话信息
 */
data class ConversationInfo(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean,
    val messageCount: Int
)

/**
 * 对话详情
 */
data class ConversationDetail(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean,
    val parentSessionId: String?,
    val summary: String?,
    val modelName: String?,
    val metadata: Map<String, Any>?
)

/**
 * 消息信息
 */
data class MessageInfo(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val parentMessageId: String?,
    val toolCalls: List<Map<String, Any>>?
)

// ==================== 结果�?====================

/**
 * 对话列表结果
 */
data class ConversationListResult(
    val success: Boolean,
    val conversations: List<ConversationInfo> = emptyList(),
    val total: Int = 0,
    val error: String? = null
)

/**
 * 对话结果
 */
data class ConversationResult(
    val success: Boolean,
    val conversation: ConversationDetail? = null,
    val error: String? = null
)

/**
 * 消息读取结果
 */
data class MessagesReadResult(
    val success: Boolean,
    val messages: List<MessageInfo> = emptyList(),
    val total: Int = 0,
    val error: String? = null
)

/**
 * 消息发送结�? */
data class MessageSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val createdAt: Long? = null,
    val error: String? = null
)

/**
 * 事件结果
 */
data class EventsResult(
    val success: Boolean,
    val events: List<MCPCEvent> = emptyList(),
    val error: String? = null
)

/**
 * 权限列表结果
 */
data class PermissionsListResult(
    val success: Boolean,
    val permissions: List<MCPCPermission> = emptyList(),
    val error: String? = null
)

/**
 * 权限响应结果
 */
data class PermissionRespondResult(
    val success: Boolean,
    val permissionId: String? = null,
    val approved: Boolean = false,
    val error: String? = null
)
