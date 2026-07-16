package com.apex.agent.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 会话数据�?- SQLite + Room实现，支持WAL模式
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        BatchRunEntity::class,
        RLTrajectoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SessionDatabase : RoomDatabase() {
    
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun batchRunDao(): BatchRunDao
    abstract fun rlTrajectoryDao(): RLTrajectoryDao
    
    companion object {
        private const val DATABASE_NAME = "session_storage.db"
        
        @Volatile
        private var INSTANCE: SessionDatabase? = null
        
        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        fun isInitialized(): Boolean {
            return INSTANCE != null
        }

        private fun buildDatabase(context: Context): SessionDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SessionDatabase::class.java,
                DATABASE_NAME
            )
                .enableMultiInstanceInvalidation()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
        }
        
        /**
         * 执行WAL检查点 - 将WAL日志刷新到主数据�?         */
        suspend fun checkpoint() {
            withContext(Dispatchers.IO) {
                INSTANCE?.apply {
                    execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            }
        }
    }
}

/**
 * 会话DAO接口
 */
@Dao
interface SessionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)
    
    @Update
    suspend fun updateSession(session: SessionEntity)
    
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?
    
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: String): Flow<SessionEntity?>
    
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>
    
    @Query("SELECT * FROM sessions WHERE isActive = 1 ORDER BY updatedAt DESC")
    fun getActiveSessions(): Flow<List<SessionEntity>>
    
    @Query("SELECT * FROM sessions WHERE parentSessionId = :parentId ORDER BY createdAt ASC")
    suspend fun getChildSessions(parentId: String): List<SessionEntity>
    
    @Query("SELECT * FROM sessions WHERE parentSessionId IS NULL ORDER BY updatedAt DESC")
    fun getRootSessions(): Flow<List<SessionEntity>>
    
    @Query("UPDATE sessions SET isActive = 0, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun deactivateSession(sessionId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE sessions SET summary = :summary, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateSessionSummary(sessionId: String, summary: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
    
    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun getSessionCount(): Int
}

/**
 * 消息DAO接口
 */
@Dao
interface MessageDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)
    
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getMessagesBySessionId(sessionId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessagesBySessionIdSync(sessionId: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int = 50): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE parentMessageId = :parentId ORDER BY createdAt ASC")
    suspend fun getChildMessages(parentId: String): List<MessageEntity>
    
    @Query("UPDATE messages SET isCompressed = 1 WHERE id = :messageId")
    suspend fun markMessageCompressed(messageId: String)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySessionId(sessionId: String)
    
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
    
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int
    
    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN sessions s ON m.sessionId = s.id
        WHERE s.parentSessionId = :ancestorSessionId
        OR s.id = :ancestorSessionId
        ORDER BY m.createdAt ASC
    """)
    suspend fun getMessagesInChain(ancestorSessionId: String): List<MessageEntity>
}

/**
 * 批量运行DAO接口
 */
@Dao
interface BatchRunDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatchRun(batchRun: BatchRunEntity)
    
    @Update
    suspend fun updateBatchRun(batchRun: BatchRunEntity)
    
    @Query("SELECT * FROM batch_runs WHERE id = :id")
    suspend fun getBatchRunById(id: String): BatchRunEntity?
    
    @Query("SELECT * FROM batch_runs WHERE batchRunId = :batchRunId ORDER BY createdAt DESC")
    fun getBatchRunsByBatchId(batchRunId: String): Flow<List<BatchRunEntity>>
    
    @Query("SELECT * FROM batch_runs ORDER BY createdAt DESC")
    fun getAllBatchRuns(): Flow<List<BatchRunEntity>>
    
    @Query("SELECT * FROM batch_runs WHERE status = :status ORDER BY createdAt DESC")
    fun getBatchRunsByStatus(status: String): Flow<List<BatchRunEntity>>
    
    @Query("UPDATE batch_runs SET status = :status, startedAt = :startedAt WHERE id = :id")
    suspend fun updateBatchRunStatus(id: String, status: String, startedAt: Long? = null)
    
    @Query("""
        UPDATE batch_runs 
        SET status = :status, completedAt = :completedAt, result = :result 
        WHERE id = :id
    """)
    suspend fun completeBatchRun(
        id: String, 
        status: String, 
        completedAt: Long, 
        result: String?
    )
    
    @Query("DELETE FROM batch_runs WHERE id = :id")
    suspend fun deleteBatchRun(id: String)
}

/**
 * RL轨迹DAO接口
 */
@Dao
interface RLTrajectoryDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrajectory(trajectory: RLTrajectoryEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrajectories(trajectories: List<RLTrajectoryEntity>)
    
    @Query("SELECT * FROM rl_trajectories WHERE batchRunId = :batchRunId ORDER BY stepIndex ASC")
    fun getTrajectoriesByBatchRunId(batchRunId: String): Flow<List<RLTrajectoryEntity>>
    
    @Query("SELECT * FROM rl_trajectories WHERE batchRunId = :batchRunId ORDER BY stepIndex ASC")
    suspend fun getTrajectoriesByBatchRunIdSync(batchRunId: String): List<RLTrajectoryEntity>

    @Query("SELECT * FROM rl_trajectories ORDER BY createdAt DESC")
    suspend fun getAllTrajectories(): List<RLTrajectoryEntity>

    @Query("DELETE FROM rl_trajectories WHERE batchRunId = :batchRunId")
    suspend fun deleteTrajectoriesByBatchRunId(batchRunId: String)
    
    @Query("SELECT COUNT(*) FROM rl_trajectories WHERE batchRunId = :batchRunId")
    suspend fun getTrajectoryCount(batchRunId: String): Int
}
