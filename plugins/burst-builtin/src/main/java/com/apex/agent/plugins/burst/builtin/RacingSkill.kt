package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class RacingSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var ctx: BurstSkillContext
    // 修复 X3：isPaused 跨线程读写需 @Volatile
    @Volatile private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 修复 C8：raceHistory 原为无界 ConcurrentHashMap，长期运行内存泄漏。
    // 改为有界 LRU（同步包装），最多保留 1000 条。
    private val raceHistory: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 1000
            }
        })

    /**
     * 动态参赛者注册表：key = 工具类别（如 "read_file"），value = 参赛者工具名列表。
     *
     * 当 [executeRace] 找不到内置默认参赛者时，会回退到该表；
     * 业务侧通过 `operation=add_contestant` 动态注入新的参赛者，
     * 让 RacingSkill 能够扩展出仓库默认未覆盖的工具组合。
     */
    private val dynamicContestants = ConcurrentHashMap<String, MutableList<String>>()

    /** 已注册参赛者的元信息（用于 history 接口展示）。 */
    private data class ContestantInfo(
        val name: String,
        val category: String,
        val registeredAt: Long,
        val registeredBy: String
    )
    private val contestantInfos = ConcurrentHashMap<String, ContestantInfo>()

    init {
        manifest = BurstSkillManifest(
            skillId = "tool_racing",
            skillName = "工具竞速",
            version = "1.0.0",
            description = "读操作工具竞速 — 同时启动多个竞争工具，返回最先成功的结果",
            author = "Apex Agent",
            tags = listOf("berserk", "racing", "speed", "read"),
            priority = 88,
            capabilities = listOf(
                "parallel_racing",
                "first_success_wins",
                "loser_cancellation",
                "latency_optimization"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.ctx = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        try {
            // 修复 C4：旧版 execute 完全未检查 isPaused，pause() 失效
            if (isPaused) {
                return@runBlocking BurstSkillResult(
                    success = false,
                    errorMessage = "Skill paused",
                    metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
                )
            }

            val operation = task.metadata["operation"] ?: "race"

            when (operation) {
                "race" -> executeRace(task, startTime)
                "add_contestant" -> addContestant(task, startTime)
                "history" -> raceHistory(task, startTime)
                else -> BurstSkillResult(
                    success = false,
                    errorMessage = "Unknown racing operation: $operation",
                    metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = "Racing failed: ${e.message}",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }
    }

    private suspend fun executeRace(task: BurstTask, startTime: Long): BurstSkillResult {
        val contestants = task.metadata["contestants"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: task.metadata["toolName"]?.let { getMergedContestants(it) }
            ?: return BurstSkillResult(
                success = false,
                errorMessage = "contestants or toolName required",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )

        if (contestants.isEmpty()) {
            return BurstSkillResult(
                success = false,
                errorMessage = "No contestants to race",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }

        // 修复 C1（critical）：旧版 ctx.kernel.executeSkill("tool_$name", ...) 拼接的
        // skillId（如 tool_cat / tool_read_file）与仓库内任何已注册 skillId 都不匹配，
        // BurstPluginLoader.executeSkill 对未知 skillId 返回 success=false，
        // 导致所有内置默认 contestant 必定失败，racing 永远走“all failed”分支。
        // 新版：优先用 name 本身作为 skillId，如果失败再尝试 tool_ 前缀。
        // 同时在启动前用 kernel.getAvailableSkills() 过滤出真实可用的 contestant，
        // 避免对未知 skillId 发起注定失败的调用。
        val kernel = ctx.kernel
        val availableSkillIds: Set<String> = if (kernel != null) {
            kernel.getAvailableSkills().map { it.skillId }.toSet()
        } else {
            emptySet()
        }

        val resolvedContestants: List<Pair<String, String>> = contestants.mapNotNull { name ->
            // 候选 skillId 顺序：name 本身 → tool_$name
            val candidates = listOfNotNull(
                name,
                if (name.startsWith("tool_")) null else "tool_$name"
            )
            // 如果 kernel 提供了可用列表，优先选在列表里的；否则全用 name 本身
            val resolved = candidates.firstOrNull { availableSkillIds.isEmpty() || it in availableSkillIds }
                ?: name  // 兔底：如果可用列表为空或都不在，仍用 name 本身试一次
            name to resolved
        }

        if (resolvedContestants.isEmpty()) {
            return BurstSkillResult(
                success = false,
                errorMessage = "No resolvable contestants (kernel has no matching skills)",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }

        val winner = AtomicBoolean(false)
        val results = ConcurrentHashMap<String, BurstSkillResult>()
        val completion = CompletableDeferred<Pair<String, BurstSkillResult>>()

        // 修复 C2/C3：winner 选出后必须 cancel 输家，避免资源泄漏 + 僵尸协程
        // 修复 C6：原版 if (winner.get()) return@async 早退检查是死代码，删除
        val jobs = resolvedContestants.map { (displayName, skillId) ->
            scope.async(start = CoroutineStart.LAZY) {
                val racerTask = task.copy(
                    id = "${task.id}_racer_${displayName.filter { it.isLetterOrDigit() || it == '_' }}",
                    metadata = task.metadata + mapOf("toolName" to displayName, "isRace" to "true")
                )
                val result = runCatching {
                    kernel?.executeSkill(skillId, racerTask)
                }.getOrNull() ?: BurstSkillResult(success = false, errorMessage = "kernel unavailable")

                results[displayName] = result

                if (result.success && winner.compareAndSet(false, true)) {
                    completion.complete(Pair(displayName, result))
                }
                result
            }
        }

        jobs.forEach { it.start() }

        val winnerPair = withTimeoutOrNull(30000L) {
            completion.await()
        } ?: run {
            val winnerEntry = results.entries.firstOrNull { it.value.success }
            if (winnerEntry != null) {
                Pair(winnerEntry.key, winnerEntry.value)
            } else {
                null
            }
        }

        // 修复 C2/C3：无论是否产生 winner，都取消所有还在跑的 racer 协程
        jobs.forEach { job ->
            if (!job.isCompleted) {
                runCatching { job.cancel() }
            }
        }

        val (winnerName, winnerResult) = winnerPair ?: Pair(null, null)

        val elapsed = System.currentTimeMillis() - startTime

        return if (winnerName != null) {
            synchronized(raceHistory) {
                raceHistory["${task.id}:$winnerName"] = "${elapsed}ms"
            }

            BurstSkillResult(
                success = true,
                output = "[RACE WINNER: $winnerName] ${winnerResult?.output ?: ""}",
                metrics = SkillMetrics(
                    executionTimeMs = elapsed,
                    stepsCompleted = contestants.size
                )
            )
        } else {
            // 修复 C11：原版只遍历 results.entries，超时未完成的 racer 不在 results 里
            // 新版显式遍历 contestants，对 results 缺失的输出 timeout
            val summary = contestants.joinToString("\n") { name ->
                val r = results[name]
                if (r == null) {
                    "  $name: TIMEOUT"
                } else if (r.success) {
                    "  $name: OK"
                } else {
                    "  $name: FAIL - ${r.errorMessage ?: "unknown"}"
                }
            }
            // 修复 C12：失败时 stepsCompleted 应是实际有结果的 racer 数，不是全部
            BurstSkillResult(
                success = false,
                output = "All ${contestants.size} racers failed",
                errorMessage = summary,
                metrics = SkillMetrics(
                    executionTimeMs = elapsed,
                    stepsCompleted = results.size
                )
            )
        }
    }

    private fun getDefaultContestants(toolName: String): List<String> {
        return when (toolName) {
            "read_file" -> listOf("read_file", "cat", "less", "head", "tail")
            "list_files" -> listOf("list_files", "ls", "glob", "dir")
            "capture_screenshot" -> listOf("capture_screenshot", "adb_screencap", "screen_capture")
            "visit_web" -> listOf("visit_web", "http_get", "web_fetch", "curl")
            "grep_code" -> listOf("grep_code", "content_search", "ripgrep")
            else -> listOf(toolName)
        }
    }

    /**
     * 合并内置默认参赛者与动态注册的参赛者，去重后返回。
     * 顺序：内置在前，动态在后；保持注册时的相对顺序。
     */
    private fun getMergedContestants(toolName: String): List<String> {
        val builtIn = getDefaultContestants(toolName).toMutableList()
        val dynamic = dynamicContestants[toolName].orEmpty()
        // 去重：动态列表中已存在于 builtIn 的项不重复加入
        dynamic.forEach { name ->
            if (name !in builtIn) builtIn.add(name)
        }
        return builtIn
    }

    /**
     * 动态注册参赛者。
     *
     * 输入 metadata 字段：
     * - `toolName`     : 工具类别（如 "read_file"），必填
     * - `contestant`   : 单个参赛者名称，与 `contestants` 二选一
     * - `contestants`  : 多个参赛者名称（逗号分隔）
     * - `source`       : 注册来源标识，可选，用于审计
     *
     * 返回：成功时列出该类别当前的全部参赛者。
     */
    private fun addContestant(task: BurstTask, startTime: Long): BurstSkillResult {
        val category = task.metadata["toolName"]
            ?: return BurstSkillResult(
                success = false,
                errorMessage = "toolName (category) is required to register a contestant",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )

        val newNames = (task.metadata["contestants"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: task.metadata["contestant"]?.let { listOf(it.trim()) }
            ?: emptyList())

        if (newNames.isEmpty()) {
            return BurstSkillResult(
                success = false,
                errorMessage = "contestant or contestants field is required",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }

        val source = task.metadata["source"] ?: "unknown"
        val now = System.currentTimeMillis()

        // 注册到并发表中
        val list = dynamicContestants.computeIfAbsent(category) { CopyOnWriteArrayList() }
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        newNames.forEach { name ->
            // 全局去重：已注册的参赛者（无论是否在内置列表中）跳过
            val alreadyKnown = name in getDefaultContestants(category) || contestantInfos.containsKey(name)
            if (alreadyKnown) {
                skipped.add(name)
            } else {
                @Suppress("UNCHECKED_CAST")
                (list as MutableList<String>).add(name)
                contestantInfos[name] = ContestantInfo(
                    name = name,
                    category = category,
                    registeredAt = now,
                    registeredBy = source
                )
                added.add(name)
            }
        }

        val merged = getMergedContestants(category)
        val report = buildString {
            appendLine("Contestant registration result for [$category]:")
            appendLine("  added: ${added.size} -> ${added.joinToString(", ") { "'$it'" }}")
            if (skipped.isNotEmpty()) {
                appendLine("  skipped (already known): ${skipped.size} -> ${skipped.joinToString(", ") { "'$it'" }}")
            }
            appendLine("  current contestants (${merged.size}): ${merged.joinToString(", ")}")
        }

        return BurstSkillResult(
            success = true,
            output = report,
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = added.size
            )
        )
    }

    private fun raceHistory(task: BurstTask, startTime: Long): BurstSkillResult {
        // 修复 C7：raceHistory 现在是同步 LRU，取最近 20 条需同步访问
        val recent = synchronized(raceHistory) {
            raceHistory.entries.toList().takeLast(20).joinToString("\n") { (key, value) ->
                "  $key -> $value"
            }
        }
        val dynamicSummary = if (contestantInfos.isEmpty()) {
            "  (no dynamically registered contestants)"
        } else {
            contestantInfos.entries.take(20).joinToString("\n") { (name, info) ->
                "  $name [${info.category}] registered by ${info.registeredBy} at ${info.registeredAt}"
            }
        }
        return BurstSkillResult(
            success = true,
            output = buildString {
                appendLine("Recent race history (last 20):")
                appendLine(recent)
                appendLine()
                appendLine("Dynamically registered contestants:")
                append(dynamicSummary)
            },
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() {
        scope.cancel()
        raceHistory.clear()
        dynamicContestants.clear()
        contestantInfos.clear()
    }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.87f
}
