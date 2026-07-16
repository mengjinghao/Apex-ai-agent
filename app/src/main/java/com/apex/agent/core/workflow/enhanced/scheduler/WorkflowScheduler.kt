package com.apex.agent.core.workflow.enhanced.scheduler

import com.apex.agent.core.workflow.enhanced.EnhancedWorkflowExecutor
import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow
import com.apex.agent.core.workflow.enhanced.model.ScheduleConfigDef
import com.apex.agent.core.workflow.enhanced.model.ScheduleTypeDef
import com.apex.agent.core.workflow.enhanced.model.TriggerConfigDef
import com.apex.agent.core.workflow.enhanced.model.TriggerTypeDef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流定时调度器
 *
 * 参照 Apache Airflow 的 DagRunScheduler、n8n 的 Schedule Trigger、
 * Dify 的 Cron 触发器、Unix cron 的 5 字段表达式
 *
 * 支持三种调度模式：
 * - INTERVAL: 固定间隔（如每 5 分钟）
 * - SPECIFIC_TIME: 每天特定时间（如每天 09:00）
 * - CRON: 标准 5 字段 cron 表达式（分 时 日 月 周）
 *
 * 特性：
 * - 动态注册/注销调度
 * - 任务错过执行时按策略处理（立即执行/跳过/合并）
 * - 时区支持
 * - 调度事件流（实时通知）
 * - 持久化接口（可注入 Persistor 防止进程重启丢失）
 */
class WorkflowScheduler(
    private val executor: EnhancedWorkflowExecutor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistor: SchedulePersistor? = null,
    private val defaultTimeZone: TimeZone = TimeZone.getDefault()
) {
    /**
     * 调度任务定义
     */
    data class ScheduledJob(
        val id: String,
        val workflow: EnhancedWorkflow,
        val scheduleConfig: ScheduleConfigDef,
        val inputs: Map<String, Any> = emptyMap(),
        val misfirePolicy: MisfirePolicy = MisfirePolicy.FIRE_ONCE,
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        val lastRunAt: Long? = null,
        val nextRunAt: Long? = null,
        val runCount: Int = 0,
        val lastResult: EnhancedWorkflowExecutor.ExecutionResult? = null
    )

    /**
     * 错过执行策略
     * - FIRE_ONCE: 错过立即执行一次（默认）
     * - SKIP: 跳过错过的执行
     * - MERGE: 合并为一次执行
     */
    enum class MisfirePolicy { FIRE_ONCE, SKIP, MERGE }

    /**
     * 调度事件
     */
    sealed class ScheduleEvent {
        data class JobRegistered(val jobId: String, val workflowName: String) : ScheduleEvent()
        data class JobUnregistered(val jobId: String) : ScheduleEvent()
        data class JobTriggered(val jobId: String, val scheduledTime: Long) : ScheduleEvent()
        data class JobSucceeded(val jobId: String, val threadId: String, val durationMs: Long) : ScheduleEvent()
        data class JobFailed(val jobId: String, val error: String) : ScheduleEvent()
        data class JobMisfired(val jobId: String, val missedCount: Int) : ScheduleEvent()
        data class JobPaused(val jobId: String) : ScheduleEvent()
        data class JobResumed(val jobId: String) : ScheduleEvent()
    }

    private val _events = MutableSharedFlow<ScheduleEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ScheduleEvent> = _events.asSharedFlow()

    private val jobs = ConcurrentHashMap<String, ScheduledJob>()
    private val jobCoroutines = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    init {
        // 启动时从持久化加载
        scope.launch {
            persistor?.loadAll()?.forEach { job ->
                if (job.enabled) startJobLoop(job)
                jobs[job.id] = job
            }
        }
    }

    /**
     * 注册调度任务
     */
    suspend fun schedule(
        workflow: EnhancedWorkflow,
        scheduleConfig: ScheduleConfigDef,
        inputs: Map<String, Any> = emptyMap(),
        misfirePolicy: MisfirePolicy = MisfirePolicy.FIRE_ONCE,
        jobId: String = "job_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    ): ScheduledJob {
        val job = ScheduledJob(
            id = jobId,
            workflow = workflow,
            scheduleConfig = scheduleConfig,
            inputs = inputs,
            misfirePolicy = misfirePolicy,
            nextRunAt = computeNextRun(scheduleConfig, System.currentTimeMillis())
        )
        jobs[jobId] = job
        persistor?.save(job)
        if (job.enabled) startJobLoop(job)
        _events.emit(ScheduleEvent.JobRegistered(jobId, workflow.name))
        return job
    }

    /**
     * 便捷方法：固定间隔调度
     */
    suspend fun scheduleAtFixedRate(
        workflow: EnhancedWorkflow,
        intervalMs: Long,
        inputs: Map<String, Any> = emptyMap(),
        jobId: String = "job_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    ): ScheduledJob {
        return schedule(
            workflow = workflow,
            scheduleConfig = ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.INTERVAL,
                intervalMs = intervalMs,
                repeat = true,
                enabled = true
            ),
            inputs = inputs,
            jobId = jobId
        )
    }

    /**
     * 便捷方法：每天特定时间调度
     * @param time HH:mm 格式，如 "09:30"
     */
    suspend fun scheduleDaily(
        workflow: EnhancedWorkflow,
        time: String,
        inputs: Map<String, Any> = emptyMap(),
        jobId: String = "job_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    ): ScheduledJob {
        return schedule(
            workflow = workflow,
            scheduleConfig = ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.SPECIFIC_TIME,
                specificTime = time,
                repeat = true,
                enabled = true
            ),
            inputs = inputs,
            jobId = jobId
        )
    }

    /**
     * 便捷方法：Cron 表达式调度
     * @param cronExpression 标准 5 字段：分 时 日 月 周（如 "0 9 * * 1-5" = 周一到周五 9 点）
     */
    suspend fun scheduleCron(
        workflow: EnhancedWorkflow,
        cronExpression: String,
        inputs: Map<String, Any> = emptyMap(),
        jobId: String = "job_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    ): ScheduledJob {
        return schedule(
            workflow = workflow,
            scheduleConfig = ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.CRON,
                cronExpression = cronExpression,
                repeat = true,
                enabled = true
            ),
            inputs = inputs,
            jobId = jobId
        )
    }

    /**
     * 取消调度
     */
    suspend fun unschedule(jobId: String): Boolean {
        val job = jobs.remove(jobId) ?: return false
        jobCoroutines.remove(jobId)?.cancel()
        persistor?.delete(jobId)
        _events.emit(ScheduleEvent.JobUnregistered(jobId))
        return true
    }

    /**
     * 暂停调度（不删除，可恢复）
     */
    suspend fun pause(jobId: String): Boolean = mutex.withLock {
        val job = jobs[jobId] ?: return false
        val updated = job.copy(enabled = false)
        jobs[jobId] = updated
        jobCoroutines.remove(jobId)?.cancel()
        persistor?.save(updated)
        _events.emit(ScheduleEvent.JobPaused(jobId))
        true
    }

    /**
     * 恢复调度
     */
    suspend fun resume(jobId: String): Boolean = mutex.withLock {
        val job = jobs[jobId] ?: return false
        if (job.enabled) return false
        val updated = job.copy(
            enabled = true,
            nextRunAt = computeNextRun(job.scheduleConfig, System.currentTimeMillis())
        )
        jobs[jobId] = updated
        startJobLoop(updated)
        persistor?.save(updated)
        _events.emit(ScheduleEvent.JobResumed(jobId))
        true
    }

    /**
     * 立即触发一次
     */
    suspend fun triggerNow(jobId: String): EnhancedWorkflowExecutor.ExecutionResult? {
        val job = jobs[jobId] ?: return null
        return executeJob(job)
    }

    /**
     * 列出所有调度任务
     */
    fun listJobs(): List<ScheduledJob> = jobs.values.sortedBy { it.createdAt }.toList()

    /**
     * 获取调度任务
     */
    fun getJob(jobId: String): ScheduledJob? = jobs[jobId]

    /**
     * 关闭所有调度
     */
    fun shutdown() {
        jobCoroutines.values.forEach { it.cancel() }
        jobCoroutines.clear()
        jobs.clear()
        scope.cancel()
    }

    /**
     * 启动任务循环
     */
    private fun startJobLoop(job: ScheduledJob) {
        val j = scope.launch {
            var current = job
            while (true) {
                val nextRun = current.nextRunAt ?: break
                val now = System.currentTimeMillis()
                val waitMs = (nextRun - now).coerceAtLeast(0)

                if (waitMs > 0) delay(waitMs)

                // 检查是否被取消或禁用
                val latest = jobs[current.id] ?: break
                if (!latest.enabled) break

                // 处理 misfire
                val actualNow = System.currentTimeMillis()
                if (actualNow - nextRun > MISFIRE_THRESHOLD_MS) {
                    val missedCount = ((actualNow - nextRun) / (current.scheduleConfig.intervalMs ?: 60_000L)).toInt()
                    when (latest.misfirePolicy) {
                        MisfirePolicy.SKIP -> {
                            _events.emit(ScheduleEvent.JobMisfired(current.id, missedCount))
                        }
                        MisfirePolicy.MERGE -> {
                            _events.emit(ScheduleEvent.JobMisfired(current.id, missedCount))
                            executeJob(latest)
                        }
                        MisfirePolicy.FIRE_ONCE -> {
                            _events.emit(ScheduleEvent.JobMisfired(current.id, missedCount))
                            executeJob(latest)
                        }
                    }
                } else {
                    executeJob(latest)
                }

                // 计算下次运行时间
                if (!current.scheduleConfig.repeat) break
                val newNextRun = computeNextRun(current.scheduleConfig, System.currentTimeMillis())
                current = latest.copy(
                    lastRunAt = actualNow,
                    nextRunAt = newNextRun,
                    runCount = latest.runCount + 1
                )
                jobs[current.id] = current
                persistor?.save(current)
            }
        }
        jobCoroutines[job.id] = j
    }

    /**
     * 执行任务
     */
    private suspend fun executeJob(job: ScheduledJob): EnhancedWorkflowExecutor.ExecutionResult {
        _events.emit(ScheduleEvent.JobTriggered(job.id, System.currentTimeMillis()))
        val start = System.currentTimeMillis()
        return try {
            val result = executor.execute(job.workflow, job.inputs)
            val updated = jobs[job.id]?.copy(lastResult = result)
            if (updated != null) {
                jobs[job.id] = updated
                persistor?.save(updated)
            }
            if (result.success) {
                _events.emit(ScheduleEvent.JobSucceeded(job.id, result.threadId, System.currentTimeMillis() - start))
            } else {
                _events.emit(ScheduleEvent.JobFailed(job.id, result.error ?: "unknown error"))
            }
            result
        } catch (e: Exception) {
            _events.emit(ScheduleEvent.JobFailed(job.id, e.message ?: e.toString()))
            throw e
        }
    }

    /**
     * 计算下次运行时间
     */
    fun computeNextRun(config: ScheduleConfigDef, fromTime: Long): Long? {
        if (!config.enabled) return null
        return when (config.scheduleType) {
            ScheduleTypeDef.INTERVAL -> {
                val interval = config.intervalMs ?: return null
                fromTime + interval
            }
            ScheduleTypeDef.SPECIFIC_TIME -> {
                val time = config.specificTime ?: return null
                computeNextSpecificTime(time, fromTime)
            }
            ScheduleTypeDef.CRON -> {
                val expr = config.cronExpression ?: return null
                CronParser.nextRun(expr, fromTime, defaultTimeZone)
            }
        }
    }

    /**
     * 计算下一个每天特定时间
     */
    private fun computeNextSpecificTime(time: String, fromTime: Long): Long {
        val parts = time.split(":")
        if (parts.size < 2) return fromTime + 24 * 60 * 60_000L
        val hour = parts[0].toIntOrNull() ?: 0
        val minute = parts[1].toIntOrNull() ?: 0

        val cal = Calendar.getInstance(defaultTimeZone)
        cal.timeInMillis = fromTime
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (cal.timeInMillis <= fromTime) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    companion object {
        private const val MISFIRE_THRESHOLD_MS = 60_000L  // 超过 1 分钟视为 misfire
    }
}

/**
 * 调度持久化接口
 *
 * 生产环境应实现为 Room/SQLite，防止进程重启丢失调度
 */
interface SchedulePersistor {
    suspend fun save(job: WorkflowScheduler.ScheduledJob)
    suspend fun delete(jobId: String)
    suspend fun loadAll(): List<WorkflowScheduler.ScheduledJob>
}

/**
 * 内存调度持久化（测试用）
 */
class InMemorySchedulePersistor : SchedulePersistor {
    private val storage = ConcurrentHashMap<String, WorkflowScheduler.ScheduledJob>()
    override suspend fun save(job: WorkflowScheduler.ScheduledJob) { storage[job.id] = job }
    override suspend fun delete(jobId: String) { storage.remove(jobId) }
    override suspend fun loadAll(): List<WorkflowScheduler.ScheduledJob> = storage.values.toList()
}
