package com.apex.agent.plugins.burst.base

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.ExecutionLog

/**
 * 状态管理器接口
 * 内核实现此接口，管理任务状态和执行日志
 */
interface IBurstStateManager {
    suspend fun saveTask(task: BurstTask)
    suspend fun loadTask(taskId: String): BurstTask?
    suspend fun saveCheckpoint(taskId: String, checkpoint: Int, state: Map<String, Any>)
    suspend fun loadCheckpoint(taskId: String): Map<String, Any>?
    suspend fun addLog(log: ExecutionLog)
    suspend fun getLogs(taskId: String): List<ExecutionLog>
}