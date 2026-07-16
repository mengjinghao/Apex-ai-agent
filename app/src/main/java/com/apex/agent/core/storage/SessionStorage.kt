package com.apex.agent.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 统一会话存储入口 - 封装 Session/Message/BatchRun/RLTrajectory ?CRUD 操作
 */
class SessionStorage private constructor(context: Context) {

    private val database = SessionDatabase.getInstance(context)
    private val sessionDao = database.sessionDao()
    private val messageDao = database.messageDao()
    private val batchRunDao = database.batchRunDao()
    private val rlTrajectoryDao = database.rlTrajectoryDao()

    companion object {
        @Volatile
        private var INSTANCE: SessionStorage? = null

        fun getInstance(context: Context): SessionStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ==================== Session CRUD ====================

    suspend fun insertSession(session: SessionEntity) = withContext(Dispatchers.IO) {
        sessionDao.insertSession(session)
    }

    suspend fun updateSession(session: SessionEntity) = withContext(Dispatchers.IO) {
        sessionDao.updateSession(session)
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDao.deleteSession(sessionId)
    }

    suspend fun getSessionById(sessionId: String): SessionEntity? = withContext(Dispatchers.IO) {
        sessionDao.getSessionById(sessionId)
    }

    fun getSessionByIdFlow(sessionId: String): Flow<SessionEntity?> = sessionDao.getSessionByIdFlow(sessionId)

    fun getAllSessionsFlow(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    fun getActiveSessionsFlow(): Flow<List<SessionEntity>> = sessionDao.getActiveSessions()

    suspend fun getChildSessions(parentId: String): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.getChildSessions(parentId)
    }

    suspend fun deactivateSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDao.deactivateSession(sessionId)
    }

    suspend fun updateSessionSummary(sessionId: String, summary: String) = withContext(Dispatchers.IO) {
        sessionDao.updateSessionSummary(sessionId, summary)
    }

    suspend fun getSessionCount(): Int = withContext(Dispatchers.IO) {
        sessionDao.getSessionCount()
    }

    // ==================== Message CRUD ====================

    suspend fun insertMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        messageDao.insertMessage(message)
    }

    suspend fun insertMessages(messages: List<MessageEntity>) = withContext(Dispatchers.IO) {
        messageDao.insertMessages(messages)
    }

    suspend fun updateMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        messageDao.updateMessage(message)
    }

    suspend fun getMessageById(messageId: String): MessageEntity? = withContext(Dispatchers.IO) {
        messageDao.getMessageById(messageId)
    }

    fun getMessagesBySessionId(sessionId: String): Flow<List<MessageEntity>> = messageDao.getMessagesBySessionId(sessionId)

    suspend fun getMessagesBySessionIdSync(sessionId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        messageDao.getMessagesBySessionIdSync(sessionId)
    }

    suspend fun getRecentMessages(sessionId: String, limit: Int = 50): List<MessageEntity> = withContext(Dispatchers.IO) {
        messageDao.getRecentMessages(sessionId, limit)
    }

    suspend fun getChildMessages(parentId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        messageDao.getChildMessages(parentId)
    }

    suspend fun markMessageCompressed(messageId: String) = withContext(Dispatchers.IO) {
        messageDao.markMessageCompressed(messageId)
    }

    suspend fun deleteMessagesBySessionId(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteMessagesBySessionId(sessionId)
    }

    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteMessage(messageId)
    }

    suspend fun getMessageCount(sessionId: String): Int = withContext(Dispatchers.IO) {
        messageDao.getMessageCount(sessionId)
    }

    suspend fun getMessagesInChain(ancestorSessionId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        messageDao.getMessagesInChain(ancestorSessionId)
    }

    // ==================== BatchRun CRUD ====================

    suspend fun insertBatchRun(batchRun: BatchRunEntity) = withContext(Dispatchers.IO) {
        batchRunDao.insertBatchRun(batchRun)
    }

    suspend fun updateBatchRun(batchRun: BatchRunEntity) = withContext(Dispatchers.IO) {
        batchRunDao.updateBatchRun(batchRun)
    }

    suspend fun deleteBatchRun(id: String) = withContext(Dispatchers.IO) {
        batchRunDao.deleteBatchRun(id)
    }

    suspend fun getBatchRunById(id: String): BatchRunEntity? = withContext(Dispatchers.IO) {
        batchRunDao.getBatchRunById(id)
    }

    fun getBatchRunsByBatchId(batchRunId: String): Flow<List<BatchRunEntity>> = batchRunDao.getBatchRunsByBatchId(batchRunId)

    fun getAllBatchRunsFlow(): Flow<List<BatchRunEntity>> = batchRunDao.getAllBatchRuns()

    fun getBatchRunsByStatus(status: String): Flow<List<BatchRunEntity>> = batchRunDao.getBatchRunsByStatus(status)

    suspend fun updateBatchRunStatus(id: String, status: String, startedAt: Long? = null) = withContext(Dispatchers.IO) {
        batchRunDao.updateBatchRunStatus(id, status, startedAt)
    }

    suspend fun completeBatchRun(id: String, status: String, completedAt: Long, result: String) = withContext(Dispatchers.IO) {
        batchRunDao.completeBatchRun(id, status, completedAt, result)
    }

    // ==================== RL Trajectory CRUD ====================

    suspend fun insertRLTrajectory(trajectory: RLTrajectoryEntity) = withContext(Dispatchers.IO) {
        rlTrajectoryDao.insertTrajectory(trajectory)
    }

    suspend fun insertRLTrajectories(trajectories: List<RLTrajectoryEntity>) = withContext(Dispatchers.IO) {
        rlTrajectoryDao.insertTrajectories(trajectories)
    }

    fun getRLTrajectoriesByBatchRunId(batchRunId: String): Flow<List<RLTrajectoryEntity>> = rlTrajectoryDao.getTrajectoriesByBatchRunId(batchRunId)

    suspend fun getRLTrajectoriesByBatchRunIdSync(batchRunId: String): List<RLTrajectoryEntity> = withContext(Dispatchers.IO) {
        rlTrajectoryDao.getTrajectoriesByBatchRunIdSync(batchRunId)
    }

    suspend fun deleteRLTrajectoriesByBatchRunId(batchRunId: String) = withContext(Dispatchers.IO) {
        rlTrajectoryDao.deleteTrajectoriesByBatchRunId(batchRunId)
    }

    suspend fun getRLTrajectoryCount(batchRunId: String): Int = withContext(Dispatchers.IO) {
        rlTrajectoryDao.getTrajectoryCount(batchRunId)
    }
}
