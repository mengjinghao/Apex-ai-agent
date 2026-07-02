package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 断点续传技能
 * 实现任务恢复、快照管理、进度追踪
 */
class RecoverySkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val checkpoints = ConcurrentHashMap<String, Checkpoint>()
    private val taskProgress = ConcurrentHashMap<String, TaskProgress>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "recovery",
            skillName = "断点续传",
            version = "1.0.0",
            description = "任务恢复和断点续传，支持快照管理、进度追踪和故障恢复",
            author = "Apex Agent",
            tags = listOf("recovery", "checkpoint", "resilience"),
            priority = 90,
            capabilities = listOf(
                "checkpoint_creation",
                "task_recovery",
                "progress_tracking",
                "breakpoint_detection"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "checkpoint"
            
            when (operation) {
                "checkpoint" -> {
                    val checkpointId = createCheckpoint(task)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Checkpoint created:
                            |- Checkpoint ID: $checkpointId
                            |- Task ID: ${task.id}
                            |- Progress: ${taskProgress[task.id]?.progress ?: 0}%
                            |- Timestamp: ${System.currentTimeMillis()}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "recover" -> {
                    val checkpointId = task.metadata["checkpointId"] ?: ""
                    val result = recoverTask(checkpointId)
                    
                    val suggestions = if (!result.success && context.utilityProcessor?.isEnabled == true) {
                        runBlocking {
                            context.utilityProcessor!!.suggestRecovery(result.message)
                        }
                    } else emptyList()
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = result.success,
                        output = """
                            |Task recovery:
                            |- Checkpoint ID: $checkpointId
                            |- Success: ${result.success}
                            |- Message: ${result.message}
                            ${result.task?.let { "- Recovered task ID: ${it.taskId}" } ?: ""}
                            ${if (suggestions.isNotEmpty()) "\n|- Recovery suggestions:\n${suggestions.joinToString("\n") { "|  - $it" }}" else ""}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = if (result.success) 1 else 0
                        )
                    )
                }
                "detect" -> {
                    val taskId = task.metadata["taskId"] ?: task.id
                    val breakpoint = detectBreakpoint(taskId)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = breakpoint != null,
                        output = """
                            |Breakpoint detection:
                            |- Task ID: $taskId
                            |- Breakpoint found: ${breakpoint != null}
                            ${if (breakpoint != null) "- Breakpoint ID: ${breakpoint.id}" else ""}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                else -> {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "Unknown operation: $operation"
                    )
                }
            }
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    fun createCheckpoint(task: BurstTask): String {
        val checkpoint = Checkpoint(
            id = UUID.randomUUID().toString(),
            taskId = task.id,
            timestamp = System.currentTimeMillis(),
            taskStatus = task.status.name,
            progress = taskProgress[task.id]?.progress ?: 0,
            currentStep = taskProgress[task.id]?.currentStep ?: "",
            metadata = task.metadata
        )
        
        checkpoints[checkpoint.id] = checkpoint
        
        return checkpoint.id
    }
    
    fun recoverTask(checkpointId: String): RecoveryResult {
        val checkpoint = checkpoints[checkpointId]
        if (checkpoint == null) {
            return RecoveryResult(
                success = false,
                message = "Checkpoint not found: $checkpointId",
                task = null
            )
        }
        
        // 创建恢复后的任务
        val recoveredTask = RecoveredTask(
            taskId = checkpoint.taskId,
            status = checkpoint.taskStatus,
            progress = checkpoint.progress,
            currentStep = checkpoint.currentStep
        )
        
        return RecoveryResult(
            success = true,
            message = "Task recovered successfully",
            task = recoveredTask
        )
    }
    
    fun detectBreakpoint(taskId: String): Checkpoint? {
        // 查找任务的最新断点
        return checkpoints.values
            .filter { it.taskId == taskId }
            .maxByOrNull { it.timestamp }
    }
    
    fun updateProgress(taskId: String, progress: Int, currentStep: String) {
        taskProgress[taskId] = TaskProgress(
            taskId = taskId,
            progress = progress,
            currentStep = currentStep,
            lastUpdate = System.currentTimeMillis()
        )
    }
    
    fun getProgress(taskId: String): TaskProgress? {
        return taskProgress[taskId]
    }
    
    fun cleanupCheckpoint(checkpointId: String) {
        checkpoints.remove(checkpointId)
    }
    
    fun cleanupTask(taskId: String) {
        checkpoints.values
            .filter { it.taskId == taskId }
            .forEach { checkpoints.remove(it.id) }
        taskProgress.remove(taskId)
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        checkpoints.clear()
        taskProgress.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.89f
    
    data class Checkpoint(
        val id: String,
        val taskId: String,
        val timestamp: Long,
        val taskStatus: String,
        val progress: Int,
        val currentStep: String,
        val metadata: Map<String, String>
    )
    
    data class TaskProgress(
        val taskId: String,
        val progress: Int,
        val currentStep: String,
        val lastUpdate: Long
    )
    
    data class RecoveryResult(
        val success: Boolean,
        val message: String,
        val task: RecoveredTask?
    )
    
    data class RecoveredTask(
        val taskId: String,
        val status: String,
        val progress: Int,
        val currentStep: String
    )
}