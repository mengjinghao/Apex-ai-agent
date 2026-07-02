package com.ai.assistance.aiterminal.terminal.agent

import android.content.Context
import android.util.Log
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import com.ai.assistance.aiterminal.terminal.ai.LLMAPI
import com.ai.assistance.aiterminal.terminal.agent.task.ErrorAnalyzer
import com.ai.assistance.aiterminal.terminal.agent.task.ScheduledTaskConfig
import com.ai.assistance.aiterminal.terminal.agent.task.ScheduledTaskManager
import com.ai.assistance.aiterminal.terminal.agent.task.TaskExecutionResult
import com.ai.assistance.aiterminal.terminal.agent.task.TaskExecutor
import com.ai.assistance.aiterminal.terminal.agent.task.TaskPersistence
import com.ai.assistance.aiterminal.terminal.agent.task.TaskPlan
import com.ai.assistance.aiterminal.terminal.agent.task.TaskPlanner
import com.ai.assistance.aiterminal.terminal.agent.task.TaskSnapshot
import com.ai.assistance.aiterminal.terminal.agent.task.TriggerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext


private const val TAG = "AdvancedTerminalAgent"

/**
 * 高级终端 Agent - 整合所有功能
 *
 * 功能：
 * 1. 系统探测 - 获取完整系统状态
 * 2. 多步任务规划 - 将自然语言拆解为结构化任务
 * 3. 顺序执行引擎 - 严格校验每一步
 * 4. 自动错误修复 - 分析错误并尝试修复
 * 5. 断点续执行 - 任务中断后恢复
 * 6. 后台定时任务 - WorkManager/AlarmManager 集成
 * 7. 系统通知 - 任务状态推送
 */
class AdvancedTerminalAgent(
    private val context: Context,
    private val rootTerminalManager: RootTerminalManager,
    private val llmApi: LLMAPI
) {
    
    // 核心组件
    private val systemProbe: SystemProbe by lazy {
        SystemProbe(rootTerminalManager)
    }
    
    private val taskPlanner: TaskPlanner by lazy {
        TaskPlanner(llmApi)
    }
    
    private val errorAnalyzer: ErrorAnalyzer by lazy {
        ErrorAnalyzer()
    }
    
    private val taskPersistence: TaskPersistence by lazy {
        TaskPersistence(context)
    }
    
    private val taskExecutor: TaskExecutor by lazy {
        TaskExecutor(rootTerminalManager, errorAnalyzer, taskPersistence)
    }
    
    private val scheduledTaskManager: ScheduledTaskManager by lazy {
        ScheduledTaskManager(context, taskExecutor, taskPersistence)
    }
    
    // 状态流
    private val _agentState = MutableStateFlow<AdvancedAgentState>(AdvancedAgentState.IDLE)
    val agentState: StateFlow<AdvancedAgentState> = _agentState.asStateFlow()
    
    // 最后一次系统探测数据
    private var lastProbeData: SystemProbeData? = null
    
    // ==================== 主要功能 ====================
    
    /**
     * 执行完整的任务流程
     */
    suspend fun executeFullTask(
        userRequest: String,
        useRoot: Boolean = rootTerminalManager.checkRootAccess()
    ): TaskExecutionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting full task execution: $userRequest")
        
        _agentState.value = AdvancedAgentState.PROBING
        
        // 1. 系统探测
        val probeData = when (val result = systemProbe.probeSystem(forceRefresh = true)) {
            is ProbeResult.Success -> {
                lastProbeData = result.data
                result.data
            }
            is ProbeResult.Error -> {
                Log.w(TAG, "System probe failed, continuing without probe data: ${result.message}")
                null
            }
            ProbeResult.Loading -> null
        }
        
        // 2. 任务规划
        _agentState.value = AdvancedAgentState.PLANNING
        
        val taskPlan = try {
            taskPlanner.planTask(userRequest, probeData, useRoot)
        } catch (e: Exception) {
            Log.e(TAG, "Task planning failed", e)
            _agentState.value = AdvancedAgentState.ERROR(e.message)
            
            return@withContext TaskExecutionResult(
                taskId = "",
                status = com.ai.assistance.aiterminal.terminal.agent.task.TaskStatus.FAILED,
                stepResults = emptyMap(),
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                errorMessage = "任务规划失败: ${e.message}"
            )
        }
        
        // 3. 执行任务
        _agentState.value = AdvancedAgentState.EXECUTING
        
        val executionResult = taskExecutor.executeTask(taskPlan)
        
        // 4. 更新最终状态
        _agentState.value = if (executionResult.isSuccess) {
            AdvancedAgentState.COMPLETED
        } else {
            AdvancedAgentState.ERROR(executionResult.errorMessage)
        }
        
        return@withContext executionResult
    }
    
    /**
     * 规划任务（不执行）
     */
    suspend fun planTask(
        userRequest: String,
        useRoot: Boolean = rootTerminalManager.checkRootAccess()
    ): TaskPlan = withContext(Dispatchers.IO) {
        val probeData = systemProbe.probeSystem()
        val data = when (probeData) {
            is ProbeResult.Success -> probeData.data
            else -> null
        }
        
        taskPlanner.planTask(userRequest, data, useRoot)
    }
    
    /**
     * 执行已规划的任务
     */
    suspend fun executePlannedTask(taskPlan: TaskPlan): TaskExecutionResult {
        _agentState.value = AdvancedAgentState.EXECUTING
        val result = taskExecutor.executeTask(taskPlan)
        _agentState.value = if (result.isSuccess) AdvancedAgentState.COMPLETED else AdvancedAgentState.ERROR(result.errorMessage)
        return result
    }
    
    /**
     * 调度定时任务
     */
    suspend fun scheduleTask(
        userRequest: String,
        triggerTime: Long,
        triggerType: TriggerType = TriggerType.ONE_TIME,
        repeatInterval: Long? = null,
        useRoot: Boolean = rootTerminalManager.checkRootAccess()
    ): ScheduledTaskConfig = withContext(Dispatchers.IO) {
        Log.i(TAG, "Scheduling task: $userRequest")
        
        // 先规划任务
        val taskPlan = planTask(userRequest, useRoot)
        
        val config = ScheduledTaskConfig(
            taskPlan = taskPlan,
            triggerType = triggerType,
            triggerTime = triggerTime,
            repeatInterval = repeatInterval,
            taskName = taskPlan.name,
            taskDescription = taskPlan.description
        )
        
        scheduledTaskManager.scheduleTask(config)
        
        _agentState.value = AdvancedAgentState.IDLE
        
        return@withContext config
    }
    
    /**
     * 恢复中断的任务
     */
    suspend fun resumeTask(taskId: String): TaskExecutionResult? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Resuming task: $taskId")
        
        val snapshot = taskPersistence.loadSnapshot(taskId) ?: return@withContext null
        
        _agentState.value = AdvancedAgentState.EXECUTING
        
        val result = taskExecutor.executeTask(
            taskPlan = snapshot.taskPlan,
            startFromStep = snapshot.currentStep + 1,
            resumeFromSnapshot = snapshot
        )
        
        _agentState.value = if (result.isSuccess) AdvancedAgentState.COMPLETED else AdvancedAgentState.ERROR(result.errorMessage)
        
        return@withContext result
    }
    
    /**
     * 暂停当前任务
     */
    fun pauseCurrentTask() {
        taskExecutor.pauseTask()
        _agentState.value = AdvancedAgentState.PAUSED
    }
    
    /**
     * 继续暂停的任务
     */
    suspend fun resumePausedTask(): TaskExecutionResult? {
        _agentState.value = AdvancedAgentState.EXECUTING
        val result = taskExecutor.resumeTask()
        _agentState.value = if (result?.isSuccess == true) AdvancedAgentState.COMPLETED else AdvancedAgentState.ERROR(result?.errorMessage)
        return result
    }
    
    /**
     * 取消当前任务
     */
    fun cancelCurrentTask() {
        taskExecutor.cancelTask()
        _agentState.value = AdvancedAgentState.CANCELLED
    }
    
    /**
     * 获取所有未完成的任务快照
     */
    suspend fun getPendingTasks(): List<TaskSnapshot> {
        return taskPersistence.getAllPendingSnapshots()
    }
    
    /**
     * 获取所有已调度的任务
     */
    suspend fun getScheduledTasks(): List<ScheduledTaskConfig> {
        return scheduledTaskManager.getAllScheduledTasks()
    }
    
    /**
     * 取消已调度的任务
     */
    suspend fun cancelScheduledTask(taskId: String) {
        scheduledTaskManager.cancelTask(taskId)
    }
    
    /**
     * 手动执行系统探测
     */
    suspend fun probeSystem(forceRefresh: Boolean = false): SystemProbeData? {
        return when (val result = systemProbe.probeSystem(forceRefresh)) {
            is ProbeResult.Success -> {
                lastProbeData = result.data
                result.data
            }
            else -> null
        }
    }
    
    /**
     * 获取缓存的系统探测数据
     */
    fun getCachedProbeData(): SystemProbeData? = lastProbeData
    
    // ==================== 辅助方法 ====================
    
    /**
     * 重置 Agent 状态
     */
    fun reset() {
        _agentState.value = AdvancedAgentState.IDLE
        taskExecutor.cancelTask()
    }
    
    /**
     * 清理过期数据
     */
    suspend fun cleanup() {
        taskPersistence.cleanExpiredSnapshots()
    }
}

// ==================== Agent 状态 ====================

sealed class AdvancedAgentState {
    object IDLE : AdvancedAgentState()
    object PROBING : AdvancedAgentState()
    object PLANNING : AdvancedAgentState()
    object EXECUTING : AdvancedAgentState()
    object PAUSED : AdvancedAgentState()
    object COMPLETED : AdvancedAgentState()
    object CANCELLED : AdvancedAgentState()
    
    data class ERROR(
        val message: String?
    ) : AdvancedAgentState()
}
