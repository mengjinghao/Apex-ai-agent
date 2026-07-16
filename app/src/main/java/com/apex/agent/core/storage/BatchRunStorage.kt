package com.apex.agent.core.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import com.apex.core.tools.javascript.map
import kotlinx.coroutines.withContext

/**
 * 批量运行存储 - 与主会话存储完全隔离的数据存? * 
 * 用途：
 * - 批量运行器的任务数据不写入主会话存储
 * - RL轨迹数据（强化学习）隔离存储
 */
class BatchRunStorage(context: Context) {
    
    private val database = SessionDatabase.getInstance(context)
    private val batchRunDao = database.batchRunDao()
    private val rlTrajectoryDao = database.rlTrajectoryDao()
    
    companion object {
        @Volatile
        private var INSTANCE: BatchRunStorage? = null
        
        fun getInstance(context: Context): BatchRunStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatchRunStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 创建批量运行
     */
    suspend fun createBatchRun(
        batchRunId: String,
        taskDescription: String
    ): BatchRunEntity = withContext(Dispatchers.IO) {
        val entity = BatchRunEntity(
            id = "batch_${System.currentTimeMillis()}_${(0..9999).random()}",
            batchRunId = batchRunId,
            taskDescription = taskDescription,
            status = BatchStatus.PENDING.name,
            createdAt = System.currentTimeMillis()
        )
        batchRunDao.insertBatchRun(entity)
        entity
    }
    
    /**
     * 开始批量运?     */
    suspend fun startBatchRun(batchRunId: String) {
        withContext(Dispatchers.IO) {
            batchRunDao.updateBatchRunStatus(
                id = batchRunId,
                status = BatchStatus.RUNNING.name,
                startedAt = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 完成批量运行
     */
    suspend fun completeBatchRun(
        batchRunId: String,
        result: String
    ) {
        withContext(Dispatchers.IO) {
            batchRunDao.completeBatchRun(
                id = batchRunId,
                status = BatchStatus.COMPLETED.name,
                completedAt = System.currentTimeMillis(),
                result = result
            )
        }
    }
    
    /**
     * 标记批量运行失败
     */
    suspend fun failBatchRun(batchRunId: String, errorMessage: String) {
        withContext(Dispatchers.IO) {
            batchRunDao.completeBatchRun(
                id = batchRunId,
                status = BatchStatus.FAILED.name,
                completedAt = System.currentTimeMillis(),
                result = null
            )
            // 获取实体并更新错误信?            val entity = batchRunDao.getBatchRunById(batchRunId)
            entity?.let {
                batchRunDao.updateBatchRun(it.copy(errorMessage = errorMessage))
            }
        }
    }
    
    /**
     * 获取批量运行状?     */
    suspend fun getBatchRun(batchRunId: String): BatchRunEntity? {
        return batchRunDao.getBatchRunById(batchRunId)
    }
    
    /**
     * 获取所有批量运行（Flow?     */
    fun getAllBatchRunsFlow(): Flow<List<BatchRunEntity>> {
        return batchRunDao.getAllBatchRuns()
    }
    
    /**
     * 获取特定批次的批量运?     */
    fun getBatchRunsByBatchIdFlow(batchRunId: String): Flow<List<BatchRunEntity>> {
        return batchRunDao.getBatchRunsByBatchId(batchRunId)
    }
    
    /**
     * 获取特定状态的批量运行
     */
    fun getBatchRunsByStatusFlow(status: BatchStatus): Flow<List<BatchRunEntity>> {
        return batchRunDao.getBatchRunsByStatus(status.name)
    }
    
    /**
     * 删除批量运行
     */
    suspend fun deleteBatchRun(batchRunId: String) {
        withContext(Dispatchers.IO) {
            // 先删除关联的RL轨迹
            rlTrajectoryDao.deleteTrajectoriesByBatchRunId(batchRunId)
            // 再删除批量运行本?            batchRunDao.deleteBatchRun(batchRunId)
        }
    }
    
    /**
     * 添加RL轨迹
     */
    suspend fun addRLTrajectory(
        batchRunId: String,
        stepIndex: Int,
        state: String,
        action: String,
        reward: Double,
        nextState: String,
        isDone: Boolean
    ): RLTrajectoryEntity = withContext(Dispatchers.IO) {
        val entity = RLTrajectoryEntity(
            id = "traj_${System.currentTimeMillis()}_${(0..9999).random()}",
            batchRunId = batchRunId,
            stepIndex = stepIndex,
            state = state,
            action = action,
            reward = reward,
            nextState = nextState,
            isDone = isDone,
            createdAt = System.currentTimeMillis()
        )
        rlTrajectoryDao.insertTrajectory(entity)
        entity
    }
    
    /**
     * 批量添加RL轨迹
     */
    suspend fun addRLTrajectories(trajectories: List<RLTrajectoryEntity>) {
        withContext(Dispatchers.IO) {
            rlTrajectoryDao.insertTrajectories(trajectories)
        }
    }
    
    /**
     * 获取批量运行的RL轨迹
     */
    fun getRLTrajectoriesFlow(batchRunId: String): Flow<List<RLTrajectoryEntity>> {
        return rlTrajectoryDao.getTrajectoriesByBatchRunId(batchRunId)
    }
    
    /**
     * 获取批量运行的RL轨迹（同步）
     */
    suspend fun getRLTrajectoriesSync(batchRunId: String): List<RLTrajectoryEntity> {
        return rlTrajectoryDao.getTrajectoriesByBatchRunIdSync(batchRunId)
    }
    
    /**
     * 计算批量运行的平均奖?     */
    suspend fun calculateAverageReward(batchRunId: String): Double = withContext(Dispatchers.IO) {
        val trajectories = rlTrajectoryDao.getTrajectoriesByBatchRunIdSync(batchRunId)
        if (trajectories.isEmpty()) return@withContext 0.0
        
        val totalReward = trajectories.sumOf { it.reward }
        totalReward / trajectories.size
    }
    
    /**
     * 删除批量运行的所有RL轨迹
     */
    suspend fun clearRLTrajectories(batchRunId: String) {
        withContext(Dispatchers.IO) {
            rlTrajectoryDao.deleteTrajectoriesByBatchRunId(batchRunId)
        }
    }
    
    /**
     * 获取RL轨迹统计
     */
    suspend fun getRLStats(batchRunId: String): RLStats = withContext(Dispatchers.IO) {
        val trajectories = rlTrajectoryDao.getTrajectoriesByBatchRunIdSync(batchRunId)
        
        if (trajectories.isEmpty()) {
            return@withContext RLStats(0, 0.0, 0.0, 0.0)
        }
        
        val avgReward = trajectories.sumOf { it.reward } / trajectories.size
        val maxReward = trajectories.maxOf { it.reward }
        val minReward = trajectories.minOf { it.reward }
        val totalSteps = trajectories.size
        
        RLStats(totalSteps, avgReward, maxReward, minReward)
    }
    
    /**
     * 清理过期数据
     */
    suspend fun cleanupOldData(olderThanTimestamp: Long) {
        withContext(Dispatchers.IO) {
            // 清理旧的批量运行（级联删除RL轨迹?            val allBatchRuns = batchRunDao.getBatchRunsByBatchId("")
            // Note: This is simplified; in production you'd want a proper cleanup query
        }
    }
}

/**
 * 批量运行状态枚? */
enum class BatchStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * RL统计数据
 */
data class RLStats(
    val totalSteps: Int,
    val averageReward: Double,
    val maxReward: Double,
    val minReward: Double
)
