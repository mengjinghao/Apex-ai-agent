package com.ai.assistance.aiterminal.terminal.agent.task

import android.util.Log
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "TaskExecutor"

/**
 * 任务执行引擎 - 顺序执行、结果校验、失败处理
 */
class TaskExecutor(
    private val rootTerminalManager: RootTerminalManager,
    private val errorAnalyzer: ErrorAnalyzer,
    private val taskPersistence: TaskPersistence
) {
    
    // 执行状态
    private val _executionState = MutableStateFlow<TaskExecutionState?>(null)
    val executionState: StateFlow<TaskExecutionState?> = _executionState.asStateFlow()
    
    // 当前任务
    private val _currentTask = MutableStateFlow<TaskExecutionContext?>(null)
    val currentTask: StateFlow<TaskExecutionContext?> = _currentTask.asStateFlow()
    
    /**
     * 执行任务
     */
    suspend fun executeTask(
        taskPlan: TaskPlan,
        startFromStep: Int = 1,
        resumeFromSnapshot: TaskSnapshot? = null
    ): TaskExecutionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting task execution: ${taskPlan.name}")
        
        val startTime = System.currentTimeMillis()
        val stepResults = mutableMapOf<String, StepExecutionResult>()
        val context = TaskExecutionContext(
            taskPlan = taskPlan,
            currentStep = startFromStep - 1,
            stepResults = stepResults
        )
        
        _currentTask.value = context
        _executionState.value = TaskExecutionState.RUNNING
        
        // 如果有快照，恢复状态
        resumeFromSnapshot?.let { snapshot ->
            Log.i(TAG, "Resuming from snapshot")
            stepResults.putAll(snapshot.stepResults)
            context.currentStep = snapshot.currentStep
        }
        
        try {
            while (context.currentStep < taskPlan.steps.size) {
                val currentStepIndex = context.currentStep
                val step = taskPlan.steps[currentStepIndex]
                
                Log.i(TAG, "Executing step ${step.order}: ${step.name}")
                
                // 更新执行状态
                _executionState.value = TaskExecutionState.StepRunning(step)
                
                // 执行步骤
                val result = executeStep(step, context)
                
                stepResults[step.id] = result
                context.lastCompletedStep = step.order
                
                // 保存快照（断点）
                saveSnapshot(context)
                
                // 处理步骤结果
                if (result.status == StepStatus.COMPLETED) {
                    Log.i(TAG, "Step completed successfully: ${step.name}")
                    context.currentStep++
                } else {
                    Log.e(TAG, "Step failed: ${step.name}")
                    
                    val handled = handleStepFailure(step, result, context)
                    if (!handled) {
                        // 失败且无法处理，终止任务
                        _executionState.value = TaskExecutionState.FAILED(result.errorAnalysis)
                        return@withContext TaskExecutionResult(
                            taskId = taskPlan.id,
                            status = TaskStatus.FAILED,
                            stepResults = stepResults.toMap(),
                            startTime = startTime,
                            endTime = System.currentTimeMillis(),
                            lastCompletedStep = context.lastCompletedStep,
                            errorMessage = result.errorAnalysis
                        )
                    }
                }
                
                // 检查是否需要暂停
                if (_executionState.value is TaskExecutionState.PAUSED) {
                    Log.i(TAG, "Task paused")
                    return@withContext TaskExecutionResult(
                        taskId = taskPlan.id,
                        status = TaskStatus.PAUSED,
                        stepResults = stepResults.toMap(),
                        startTime = startTime,
                        lastCompletedStep = context.lastCompletedStep
                    )
                }
            }
            
            // 所有步骤完成
            _executionState.value = TaskExecutionState.COMPLETED
            Log.i(TAG, "Task completed successfully: ${taskPlan.name}")
            
            return@withContext TaskExecutionResult(
                taskId = taskPlan.id,
                status = TaskStatus.COMPLETED,
                stepResults = stepResults.toMap(),
                startTime = startTime,
                endTime = System.currentTimeMillis(),
                lastCompletedStep = context.lastCompletedStep
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            _executionState.value = TaskExecutionState.FAILED(e.message)
            
            return@withContext TaskExecutionResult(
                taskId = taskPlan.id,
                status = TaskStatus.FAILED,
                stepResults = stepResults.toMap(),
                startTime = startTime,
                endTime = System.currentTimeMillis(),
                lastCompletedStep = context.lastCompletedStep,
                errorMessage = e.message
            )
        } finally {
            _currentTask.value = null
            // 清理快照
            taskPersistence.clearSnapshot(taskPlan.id)
        }
    }
    
    /**
     * 执行单个步骤
     */
    suspend fun executeStep(
        step: TaskStep,
        context: TaskExecutionContext
    ): StepExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var retryCount = 0
        var currentCommand = step.command
        var lastErrorAnalysis: String? = null
        
        while (retryCount <= (step.failureHandler?.maxRetries ?: 0)) {
            val stepStartTime = System.currentTimeMillis()
            Log.d(TAG, "Executing command (attempt ${retryCount + 1}): $currentCommand")
            
            // 执行命令
            val (exitCode, stdout, stderr) = executeShellCommand(
                command = currentCommand,
                useRoot = step.requiresRoot
            )
            
            val duration = System.currentTimeMillis() - stepStartTime
            Log.d(TAG, "Command completed in $duration ms, exitCode: $exitCode")
            
            // 校验结果
            val validationPassed = validateResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                rules = step.validationRules
            )
            
            // 构建结果
            val result = StepExecutionResult(
                stepId = step.id,
                status = if (validationPassed && exitCode == 0) StepStatus.COMPLETED else StepStatus.FAILED,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                startTime = stepStartTime,
                endTime = System.currentTimeMillis(),
                retryCount = retryCount,
                validationPassed = validationPassed,
                errorAnalysis = lastErrorAnalysis,
                fixedCommand = if (currentCommand != step.command) currentCommand else null
            )
            
            if (result.status == StepStatus.COMPLETED) {
                return@withContext result
            }
            
            // 失败，尝试修复
            retryCount++
            if (retryCount <= (step.failureHandler?.maxRetries ?: 0)) {
                val errorAnalysis = errorAnalyzer.analyzeError(
                    originalCommand = currentCommand,
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr
                )
                
                lastErrorAnalysis = errorAnalysis.rootCause
                
                // 尝试修复命令
                if (errorAnalysis.fixedCommand != null) {
                    Log.i(TAG, "Trying fixed command: ${errorAnalysis.fixedCommand}")
                    currentCommand = errorAnalysis.fixedCommand
                } else if (step.failureHandler?.alternativeCommands?.isNotEmpty() == true) {
                    val altCmdIndex = (retryCount - 1) % step.failureHandler.alternativeCommands.size
                    currentCommand = step.failureHandler.alternativeCommands[altCmdIndex]
                    Log.i(TAG, "Trying alternative command: $currentCommand")
                } else {
                    // 没有修复方案，直接返回失败
                    return@withContext result
                }
            } else {
                // 达到最大重试次数
                return@withContext result
            }
        }
        
        // 不应该到达这里
        StepExecutionResult(
            stepId = step.id,
            status = StepStatus.FAILED,
            exitCode = -1,
            stdout = "",
            stderr = "Max retries exceeded",
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            retryCount = retryCount
        )
    }
    
    /**
     * 执行 Shell 命令
     */
    private suspend fun executeShellCommand(
        command: String,
        useRoot: Boolean
    ): Triple<Int, String, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(command)
            }
            
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            
            Triple(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution exception", e)
            Triple(-1, "", e.message ?: "Unknown error")
        }
    }
    
    /**
     * 校验执行结果
     */
    private fun validateResult(
        exitCode: Int,
        stdout: String,
        stderr: String,
        rules: List<ValidationRule>
    ): Boolean {
        if (rules.isEmpty()) {
            return exitCode == 0
        }
        
        for (rule in rules) {
            val passed = when (rule.type) {
                ValidationType.EXIT_CODE_ZERO -> {
                    exitCode == 0
                }
                ValidationType.OUTPUT_CONTAINS -> {
                    stdout.contains(rule.value)
                }
                ValidationType.OUTPUT_NOT_CONTAINS -> {
                    !stdout.contains(rule.value) && !stderr.contains(rule.value)
                }
                ValidationType.FILE_EXISTS -> {
                    val file = java.io.File(rule.value)
                    file.exists()
                }
                ValidationType.CUSTOM -> {
                    // 自定义校验，暂时通过
                    true
                }
            }
            
            if (!passed) {
                Log.d(TAG, "Validation failed for rule: ${rule.type} - ${rule.value}")
                return false
            }
        }
        
        return true
    }
    
    /**
     * 处理步骤失败
     */
    private suspend fun handleStepFailure(
        step: TaskStep,
        result: StepExecutionResult,
        context: TaskExecutionContext
    ): Boolean = withContext(Dispatchers.IO) {
        val failureHandler = step.failureHandler ?: return@withContext false
        
        when (failureHandler.strategy) {
            FailureStrategy.RETRY -> {
                // 已经在 executeStep 中重试了
                false
            }
            FailureStrategy.ROLLBACK -> {
                Log.i(TAG, "Performing rollback for step: ${step.name}")
                val rollbackResult = executeRollback(failureHandler.rollbackCommand, step.requiresRoot)
                if (rollbackResult) {
                    context.rollbackPerformed = true
                    _executionState.value = TaskExecutionState.ROLLED_BACK
                }
                false
            }
            FailureStrategy.SKIP -> {
                Log.i(TAG, "Skipping failed step: ${step.name}")
                context.currentStep++
                true
            }
            FailureStrategy.ABORT -> {
                Log.e(TAG, "Aborting task due to step failure: ${step.name}")
                false
            }
            FailureStrategy.ASK_USER -> {
                Log.i(TAG, "Need user confirmation")
                _executionState.value = TaskExecutionState.AWAITING_CONFIRMATION(step, result)
                pauseTask()
                true
            }
        }
    }
    
    /**
     * 执行回滚
     */
    private suspend fun executeRollback(
        rollbackCommand: String?,
        requiresRoot: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (rollbackCommand == null) return@withContext false
        
        Log.i(TAG, "Executing rollback command: $rollbackCommand")
        
        val (exitCode, stdout, stderr) = executeShellCommand(rollbackCommand, requiresRoot)
        
        if (exitCode == 0) {
            Log.i(TAG, "Rollback completed successfully")
            true
        } else {
            Log.e(TAG, "Rollback failed: $stderr")
            false
        }
    }
    
    /**
     * 保存快照
     */
    private suspend fun saveSnapshot(context: TaskExecutionContext) {
        val snapshot = TaskSnapshot(
            taskId = context.taskPlan.id,
            taskPlan = context.taskPlan,
            currentStep = context.currentStep,
            stepResults = context.stepResults.toMap(),
            status = TaskStatus.RUNNING
        )
        taskPersistence.saveSnapshot(snapshot)
    }
    
    /**
     * 暂停任务
     */
    fun pauseTask() {
        val currentContext = _currentTask.value
        if (currentContext != null) {
            _executionState.value = TaskExecutionState.PAUSED
            Log.i(TAG, "Task paused")
        }
    }
    
    /**
     * 继续任务
     */
    suspend fun resumeTask(): TaskExecutionResult? {
        val context = _currentTask.value ?: return null
        val snapshot = taskPersistence.loadSnapshot(context.taskPlan.id) ?: return null
        
        _executionState.value = TaskExecutionState.RUNNING
        return executeTask(
            taskPlan = snapshot.taskPlan,
            startFromStep = snapshot.currentStep + 1,
            resumeFromSnapshot = snapshot
        )
    }
    
    /**
     * 取消任务
     */
    fun cancelTask() {
        _executionState.value = TaskExecutionState.CANCELLED
        _currentTask.value = null
    }
    
    /**
     * 确认继续
     */
    fun confirmContinue() {
        if (_executionState.value is TaskExecutionState.AWAITING_CONFIRMATION) {
            _executionState.value = TaskExecutionState.RUNNING
        }
    }
}

// ==================== 辅助类 ====================

/**
 * 任务执行上下文
 */
data class TaskExecutionContext(
    val taskPlan: TaskPlan,
    var currentStep: Int = 0,
    val stepResults: MutableMap<String, StepExecutionResult>,
    var lastCompletedStep: Int? = null,
    var rollbackPerformed: Boolean = false
)

/**
 * 任务执行状态
 */
sealed class TaskExecutionState {
    object IDLE : TaskExecutionState()
    object RUNNING : TaskExecutionState()
    object PAUSED : TaskExecutionState()
    object COMPLETED : TaskExecutionState()
    object ROLLED_BACK : TaskExecutionState()
    object CANCELLED : TaskExecutionState()
    
    data class StepRunning(
        val step: TaskStep
    ) : TaskExecutionState()
    
    data class FAILED(
        val errorMessage: String?
    ) : TaskExecutionState()
    
    data class AWAITING_CONFIRMATION(
        val step: TaskStep,
        val result: StepExecutionResult
    ) : TaskExecutionState()
}
