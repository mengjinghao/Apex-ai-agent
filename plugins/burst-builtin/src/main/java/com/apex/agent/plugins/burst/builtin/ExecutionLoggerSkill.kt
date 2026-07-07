package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 执行日志技能
 * 实现事件记录、报告生成、可观测性管理
 */
class ExecutionLoggerSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val eventMap = ConcurrentHashMap<String, MutableList<ExecutionEvent>>()
    private val taskMetrics = ConcurrentHashMap<String, TaskMetrics>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "execution_logger",
            skillName = "执行日志",
            version = "1.0.0",
            description = "执行日志记录和可观测性管理，支持事件追踪、报告生成",
            author = "Apex Agent",
            tags = listOf("logging", "observability", "tracking"),
            priority = 70,
            capabilities = listOf(
                "event_logging",
                "report_generation",
                "metrics_tracking",
                "task_tracing"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "log"
            val taskId = task.metadata["taskId"] ?: task.id
            
            when (operation) {
                "log" -> {
                    val eventType = task.metadata["eventType"] ?: "EXECUTION"
                    val message = task.input.text ?: "Task execution"
                    val details = task.metadata.filterKeys { it != "operation" && it != "eventType" }
                    
                    logEvent(taskId, ExecutionEvent(
                        eventType = eventType,
                        message = message,
                        details = details,
                        timestamp = System.currentTimeMillis()
                    ))
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Event logged:
                            |- Task ID: $taskId
                            |- Event type: $eventType
                            |- Message: ${message.take(50)}...
                            |- Timestamp: ${Date(System.currentTimeMillis())}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "report" -> {
                    val events = getEvents(taskId)
                    val report = if (context.utilityProcessor?.isEnabled == true && events.isNotEmpty()) {
                        val summaries = events.map { event ->
                            runBlocking {
                                context.utilityProcessor!!.summarizeStep(
                                    "${event.eventType}: ${event.message}"
                                )
                            }
                        }
                        buildString {
                            appendLine("# Utility Execution Report")
                            appendLine("## Summary")
                            appendLine("- Task ID: $taskId")
                            appendLine("- Total events: ${events.size}")
                            appendLine("- Generated at: ${java.util.Date()}")
                            appendLine()
                            summaries.forEachIndexed { i, summary ->
                                appendLine("${i + 1}. $summary")
                            }
                        }
                    } else {
                        generateReport(taskId)
                    }
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = report,
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = eventMap[taskId]?.size ?: 0
                        )
                    )
                }
                "metrics" -> {
                    val metrics = getTaskMetrics(taskId)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Task metrics:
                            |- Task ID: $taskId
                            |- Total events: ${metrics.totalEvents}
                            |- Execution time: ${metrics.totalExecutionTimeMs}ms
                            |- Success rate: ${metrics.successRate}%
                            |- Error count: ${metrics.errorCount}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "cleanup" -> {
                    cleanup(taskId)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = "Cleaned up logs for task: $taskId",
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
    
    fun logEvent(taskId: String, event: ExecutionEvent) {
        val events = eventMap.getOrPut(taskId) { mutableListOf() }
        events.add(event)
        
        // 更新任务指标
        updateMetrics(taskId, event)
    }
    
    fun generateReport(taskId: String): String {
        val events = eventMap.getOrDefault(taskId, emptyList())
        val report = StringBuilder()
        
        report.append("# Execution Report\n\n")
        report.append("## Summary\n")
        report.append("- Task ID: $taskId\n")
        report.append("- Total events: ${events.size}\n")
        report.append("- Generated at: ${Date()}\n\n")
        
        val eventTypes = events.groupingBy { it.eventType }.eachCount()
        report.append("## Event Types\n")
        eventTypes.forEach { (type, count) ->
            report.append("- $type: $count\n")
        }
        
        report.append("\n## Events\n")
        events.forEach { event ->
            report.append("### ${event.eventType}\n")
            report.append("- Time: ${Date(event.timestamp)}\n")
            report.append("- Message: ${event.message}\n")
            if (event.details.isNotEmpty()) {
                report.append("- Details:\n")
                event.details.forEach { (key, value) ->
                    report.append("  - $key: $value\n")
                }
            }
            report.append("\n")
        }
        
        return report.toString()
    }
    
    fun getEvents(taskId: String): List<ExecutionEvent> {
        return eventMap.getOrDefault(taskId, emptyList())
    }
    
    fun getTaskMetrics(taskId: String): TaskMetrics {
        return taskMetrics.getOrDefault(taskId, TaskMetrics(taskId = taskId))
    }
    
    fun cleanup(taskId: String) {
        eventMap.remove(taskId)
        taskMetrics.remove(taskId)
    }
    
    private fun updateMetrics(taskId: String, event: ExecutionEvent) {
        val currentMetrics = taskMetrics.getOrDefault(taskId, TaskMetrics(taskId = taskId))
        
        val newMetrics = currentMetrics.copy(
            totalEvents = currentMetrics.totalEvents + 1,
            totalExecutionTimeMs = if (event.eventType == "COMPLETED") {
                event.timestamp - currentMetrics.startTime
            } else currentMetrics.totalExecutionTimeMs,
            errorCount = if (event.eventType == "ERROR") {
                currentMetrics.errorCount + 1
            } else currentMetrics.errorCount,
            successRate = calculateSuccessRate(taskId)
        )
        
        taskMetrics[taskId] = newMetrics
    }
    
    private fun calculateSuccessRate(taskId: String): Float {
        val events = eventMap[taskId] ?: return 100f
        val successCount = events.count { it.eventType == "SUCCESS" || it.eventType == "COMPLETED" }
        val errorCount = events.count { it.eventType == "ERROR" }
        val total = successCount + errorCount
        
        return if (total > 0) {
            (successCount.toFloat() / total) * 100
        } else 100f
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        eventMap.clear()
        taskMetrics.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.75f
    
    data class ExecutionEvent(
        val eventType: String,
        val message: String,
        val details: Map<String, String>,
        val timestamp: Long
    )
    
    data class TaskMetrics(
        val taskId: String,
        val totalEvents: Int = 0,
        val totalExecutionTimeMs: Long = 0,
        val successRate: Float = 100f,
        val errorCount: Int = 0,
        val startTime: Long = System.currentTimeMillis()
    )
}