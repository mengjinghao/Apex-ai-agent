package com.apex.agent.plugins.burst.builtin

import kotlinx.coroutines.Dispatchers

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BerserkExecutionSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var ctx: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val completedJobs = ConcurrentHashMap<String, Long>()
    private val verdictCache = ConcurrentHashMap<String, String>()

    init {
        manifest = BurstSkillManifest(
            skillId = "berserk_execution",
            skillName = "狂暴执行引擎",
            version = "1.0.0",
            description = "超并行执行引擎 — 无限制并发、无限重试、自动熔断关闭",
            author = "Apex Agent",
            tags = listOf("berserk", "parallel", "execution", "extreme"),
            priority = 100,
            capabilities = listOf(
                "extreme_parallelism",
                "infinite_retry",
                "circuit_breaker_bypass",
                "speculative_execution",
                "cross_skill_orchestration"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.ctx = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val operation = task.metadata["operation"] ?: "execute"

            when (operation) {
                "execute" -> executeBerserk(task, startTime)
                "orchestrate" -> orchestrateSkills(task, startTime)
                "query_status" -> queryStatus(task, startTime)
                "cancel_all" -> cancelAll(task, startTime)
                else -> failResult("Unknown berserk operation: $operation")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = "BerserkExecution failed: ${e.message}",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }
    }

    private suspend fun executeBerserk(task: BurstTask, startTime: Long): BurstSkillResult {
        val toolName = task.metadata["toolName"] ?: return failResult("toolName required")
        val params = task.metadata
        val maxParallelism = params["maxParallelism"]?.toIntOrNull() ?: Int.MAX_VALUE
        val retryCount = params["retryCount"]?.toIntOrNull() ?: 99

        val jobId = UUID.randomUUID().toString()
        val semaphore = Semaphore(maxParallelism)

        var lastError: String? = null
        var attempt = 0
        var success = false
        var resultOutput = ""

        while (attempt <= retryCount && !success && !isPaused) {
            attempt++
            semaphore.acquire()
            try {
                val job = scope.launch {
                    val subTask = task.copy(
                        id = "${task.id}_attempt_$attempt",
                        metadata = task.metadata + mapOf("attempt" to attempt.toString())
                    )
                    val skillResult = ctx.kernel.executeSkill("tool_${toolName}", subTask)
                    if (skillResult.success) {
                        resultOutput = skillResult.output ?: ""
                        success = true
                    } else {
                        lastError = skillResult.errorMessage
                    }
                }
                activeJobs[jobId] = job
                job.join()
            } finally {
                semaphore.release()
            }

            if (!success) {
                val delayMs = calculateBackoff(attempt)
                delay(delayMs)
            }
        }

        completedJobs[jobId] = System.currentTimeMillis()

        return BurstSkillResult(
            success = success,
            output = if (success) resultOutput else "All $retryCount attempts failed: $lastError",
            errorMessage = if (success) null else lastError,
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = attempt
            )
        )
    }

    private suspend fun orchestrateSkills(task: BurstTask, startTime: Long): BurstSkillResult {
        val skillNames = task.metadata["skills"]?.split(",") ?: emptyList()
        if (skillNames.isEmpty()) return failResult("No skills to orchestrate")

        val results = ConcurrentHashMap<String, BurstSkillResult>()

        coroutineScope {
            skillNames.map { skillName ->
                async {
                    val subTask = task.copy(
                        id = "${task.id}_$skillName",
                        metadata = task.metadata + mapOf("orchestrated_by" to skillName)
                    )
                    val result = ctx.kernel.executeSkill(skillName, subTask)
                    results[skillName] = result
                }
            }
        }

        val allSuccess = results.all { it.value.success }
        val combined = results.entries.joinToString("\n---\n") { (name, r) ->
            "[$name] success=${r.success} | ${r.output?.take(200) ?: ""}"
        }

        return BurstSkillResult(
            success = allSuccess,
            output = combined,
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = results.size
            )
        )
    }

    private fun queryStatus(task: BurstTask, startTime: Long): BurstSkillResult {
        val active = activeJobs.count { it.value.isActive }
        val completed = completedJobs.size

        return BurstSkillResult(
            success = true,
            output = """
                |Berserk Execution Status:
                |- Active jobs: $active
                |- Completed jobs: $completed
                |- Paused: $isPaused
                |- Verdict cache size: ${verdictCache.size}
            """.trimMargin(),
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    private fun cancelAll(task: BurstTask, startTime: Long): BurstSkillResult {
        val count = activeJobs.count { it.value.isActive }
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        verdictCache.clear()

        return BurstSkillResult(
            success = true,
            output = "Cancelled $count active jobs, cleared ${completedJobs.size} completed records",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }

    override fun destroy() {
        scope.cancel()
        activeJobs.clear()
        completedJobs.clear()
        verdictCache.clear()
    }

    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.95f

    private fun failResult(msg: String) = BurstSkillResult(success = false, errorMessage = msg)
    private fun calculateBackoff(attempt: Int): Long = (50L * (1 shl (attempt - 1).coerceAtMost(5))).coerceAtMost(1000L)

    // Minimal counting semaphore for coroutines
    private class Semaphore(private val maxPermits: Int) {
        private val available = AtomicInteger(maxPermits)
        suspend fun acquire() {
            while (true) {
                val cur = available.get()
                if (cur > 0 && available.compareAndSet(cur, cur - 1)) return
                delay(5)
            }
        }
        fun release() { available.incrementAndGet() }
    }
}
