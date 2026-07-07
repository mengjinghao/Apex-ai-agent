package com.apex.agent.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 会话链管理器 - 维护parent_session_id分裂链，支持压缩恢复
 */
class SessionChainManager(private val context: Context) {
    
    private val database = SessionDatabase.getInstance(context)
    private val sessionDao = database.sessionDao()
    private val messageDao = database.messageDao()
    
    companion object {
        private const val MAX_CHAIN_DEPTH = 10
        
        @Volatile
        private var INSTANCE: SessionChainManager? = null
        
        fun getInstance(context: Context): SessionChainManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionChainManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 创建会话分裂
     * @param parentSessionId 父会话ID
     * @param splitFromMessageId 分裂点消息ID
     * @param newSession 新会话实�?     * @return 新会话ID
     */
    suspend fun createSessionSplit(
        parentSessionId: String,
        splitFromMessageId: String,
        newSession: SessionEntity
    ): String = withContext(Dispatchers.IO) {
        // 验证父会话存�?        val parentSession = sessionDao.getSessionById(parentSessionId)
            ?: throw IllegalArgumentException("Parent session not found: ${parentSessionId}")
        
        // 验证分裂点消息存�?        val splitMessage = messageDao.getMessageById(splitFromMessageId)
            ?: throw IllegalArgumentException("Split message not found: ${splitFromMessageId}")
        
        if (splitMessage.sessionId != parentSessionId) {
            throw IllegalArgumentException("Split message does not belong to parent session")
        }
        
        // 更新父会话状�?        sessionDao.deactivateSession(parentSessionId)
        
        // 创建新会�?        sessionDao.insertSession(newSession)
        
        // 复制分裂点之前的消息到新会话
        val messagesToCopy = messageDao.getMessagesBySessionIdSync(parentSessionId)
            .filter { it.createdAt <= splitMessage.createdAt }
        
        val newMessages = messagesToCopy.map { message ->
            message.copy(
                id = "${newSession.id}_${message.id}",
                sessionId = newSession.id
            )
        }
        
        messageDao.insertMessages(newMessages)
        
        newSession.id
    }
    
    /**
     * 获取会话�?     * @param sessionId 会话ID
     * @return 从根会话到当前会话的完整�?     */
    suspend fun getSessionChain(sessionId: String): List<SessionChainNode> = withContext(Dispatchers.IO) {
        val chain = mutableListOf<SessionChainNode>()
        var currentSessionId: String? = sessionId
        
        while (currentSessionId != null && chain.size < MAX_CHAIN_DEPTH) {
            val session = sessionDao.getSessionById(currentSessionId)
                ?: break
            
            chain.add(0, SessionChainNode(
                sessionId = session.id,
                parentSessionId = session.parentSessionId,
                splitFromMessageId = session.splitFromMessageId,
                createdAt = session.createdAt,
                isActive = session.isActive
            ))
            
            currentSessionId = session.parentSessionId
        }
        
        chain
    }
    
    /**
     * 获取会话的所有子会话（分裂）
     */
    suspend fun getChildSessions(sessionId: String): List<SessionEntity> {
        return sessionDao.getChildSessions(sessionId)
    }
    
    /**
     * 从分裂点恢复会话
     * @param sessionId 会话ID
     * @param splitMessageId 分裂点消息ID
     * @return 恢复后会话的消息列表
     */
    suspend fun recoverFromSplit(
        sessionId: String,
        splitMessageId: String
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        val session = sessionDao.getSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: ${sessionId}")
        
        // 获取当前会话的完整链
        val chain = getSessionChain(sessionId)
        
        // 收集所有相关消�?        val allMessages = mutableListOf<MessageEntity>()
        
        chain.forEachIndexed { index, node ->
            val isCurrentSession = index == chain.size - 1
            val messages = messageDao.getMessagesBySessionIdSync(node.sessionId)
            
            val filteredMessages = if (isCurrentSession) {
                // 当前会话：只取分裂点及之后的消息
                messages.filter { msg ->
                    val msgIndex = messages.indexOf(msg)
                    val splitIndex = messages.indexOfFirst { it.id == splitMessageId }
                    splitIndex >= 0 && msgIndex >= splitIndex
                }
            } else {
                // 祖先会话：只取分裂点之前的消�?                messages.filter { msg ->
                    val splitMessage = if (node.splitFromMessageId != null) {
                        messageDao.getMessageById(node.splitFromMessageId)
                    } else null
                    
                    splitMessage?.let { msg.createdAt <= it.createdAt } ?: true
                }
            }
            
            allMessages.addAll(filteredMessages)
        }
        
        // 按时间排�?        allMessages.sortedBy { it.createdAt }
    }
    
    /**
     * 检查会话是否可以被压缩
     * @param sessionId 会话ID
     * @return true 如果会话有足够的子会话可以压�?     */
    suspend fun canCompress(sessionId: String): Boolean {
        val childSessions = getChildSessions(sessionId)
        // 如果有多个子会话，说明可以进行压�?        return childSessions.size >= 2
    }
    
    /**
     * 获取压缩建议
     * 返回需要保留的关键消息ID列表
     */
    suspend fun getCompressionSuggestions(sessionId: String): List<String> = withContext(Dispatchers.IO) {
        val messages = messageDao.getMessagesBySessionIdSync(sessionId)
        
        // 保留策略：保留首尾消息和关键决策�?        val importantMessageIds = mutableListOf<String>()
        
        // 保留第一条系统消�?        messages.find { it.role == "system" }?.let { importantMessageIds.add(it.id) }
        
        // 保留最后一条消�?        messages.lastOrNull()?.let { importantMessageIds.add(it.id) }
        
        // 保留有工具调用的用户消息
        messages.filter { it.role == "user" && it.toolCalls != null }
            .takeLast(3)
            .forEach { importantMessageIds.add(it.id) }
        
        importantMessageIds.distinct()
    }
    
    /**
     * 执行会话压缩
     * 创建摘要并分裂为新会�?     */
    suspend fun compressSession(
        sessionId: String,
        summary: String
    ): String = withContext(Dispatchers.IO) {
        val session = sessionDao.getSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: ${sessionId}")
        
        val messages = messageDao.getMessagesBySessionIdSync(sessionId)
        val keepMessageIds = getCompressionSuggestions(sessionId)
        
        // 保留的消�?        val keptMessages = messages.filter { it.id in keepMessageIds }
        
        // 创建压缩后的新会�?        val compressedSession = session.copy(
            id = "${sessionId}_compressed_${System.currentTimeMillis()}",
            parentSessionId = sessionId,
            splitFromMessageId = keepMessageIds.lastOrNull(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isActive = true,
            summary = summary
        )
        
        // 保存压缩会话
        sessionDao.insertSession(compressedSession)
        
        // 保存保留的消息到新会�?        val newMessages = keptMessages.map { msg ->
            msg.copy(
                id = "${compressedSession.id}_${msg.id}",
                sessionId = compressedSession.id,
                isCompressed = true
            )
        }
        messageDao.insertMessages(newMessages)
        
        // 标记原会话为非活�?        sessionDao.deactivateSession(sessionId)
        
        compressedSession.id
    }
    
    /**
     * 验证会话链完整�?     */
    suspend fun validateChain(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val chain = getSessionChain(sessionId)
            
            // 验证链中每个会话都存�?            chain.forEach { node ->
                sessionDao.getSessionById(node.sessionId)
                    ?: return@withContext false
            }
            
            // 验证父子关系正确
            chain.zipWithNext().forEach { (child, parent) ->
                if (child.parentSessionId != parent.sessionId) {
                    return@withContext false
                }
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取会话链的Flow
     */
    fun getSessionChainFlow(sessionId: String): Flow<List<SessionChainNode>> = flow {
        emit(getSessionChain(sessionId))
    }.flowOn(Dispatchers.IO)
    
    /**
     * 查找最近的共同祖先会话
     */
    suspend fun findCommonAncestor(sessionId1: String, sessionId2: String): String? = withContext(Dispatchers.IO) {
        val chain1 = getSessionChain(sessionId1).map { it.sessionId }.toSet()
        val chain2 = getSessionChain(sessionId2)
        
        chain2.find { it.sessionId in chain1 }
    }
    
    /**
     * 合并两个会话链的消息
     */
    suspend fun mergeSessionChains(
        targetSessionId: String,
        sourceSessionId: String,
        splitMessageId: String
    ): Unit = withContext(Dispatchers.IO) {
        val sourceMessages = messageDao.getMessagesBySessionIdSync(sourceSessionId)
        
        // 只复制分裂点之前的消�?        val splitMessage = messageDao.getMessageById(splitMessageId)
        val messagesToMerge = sourceMessages.filter { msg ->
            splitMessage?.let { msg.createdAt <= it.createdAt } ?: true
        }
        
        // 为每条消息创建新的ID以避免冲�?        val mergedMessages = messagesToMerge.map { msg ->
            msg.copy(
                id = "${targetSessionId}_merged_${msg.id}",
                sessionId = targetSessionId
            )
        }
        
        messageDao.insertMessages(mergedMessages)
    }
}
