package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class RecoveryChainSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var ctx: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val chainRegistry = ConcurrentHashMap<String, List<RecoveryStep>>()
    private val chainHistory = ConcurrentHashMap<String, ChainExecution>()

    data class RecoveryStep(
        val id: String,
        val toolName: String,
        val description: String,
        val params: Map<String, String> = emptyMap(),
        val timeoutMs: Long = 5000L,
        val retryCount: Int = 3,
        val condition: String? = null
    )

    data class ChainExecution(
        val chainId: String,
        val originalTool: String,
        val steps: List<RecoveryStep>,
        val lastStep: Int,
        val success: Boolean,
        val winnerStep: String?,
        val totalTime: Long
    )

    init {
        manifest = BurstSkillManifest(
            skillId = "recovery_chain",
            skillName = "恢复链引擎",
            version = "1.0.0",
            description = "多步骤恢复链 — 工具失败时自动构建并执行递进式恢复策略",
            author = "Apex Agent",
            tags = listOf("berserk", "recovery", "chain", "resilience"),
            priority = 92,
            capabilities = listOf(
                "chain_construction",
                "step_retry",
                "conditional_execution",
                "chain_pruning",
                "result_propagation"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.ctx = context
        buildChains()
    }

    private fun buildChains() {
        chainRegistry["click_element"] = listOf(
            RecoveryStep("1", "tap", "短点击替代点击"),
            RecoveryStep("2", "long_press", "尝试长按"),
            RecoveryStep("3", "press_key", "键盘按键替代"),
            RecoveryStep("4", "scroll_and_click", "滚动后点击"),
            RecoveryStep("5", "coordinate_click", "坐标强制点击"),
            RecoveryStep("6", "accessibility_click", "无障碍点击")
        )
        chainRegistry["read_file"] = listOf(
            RecoveryStep("1", "cat", "cat读取", retryCount = 3),
            RecoveryStep("2", "less", "less读取"),
            RecoveryStep("3", "head", "head读取"),
            RecoveryStep("4", "tail", "tail读取"),
            RecoveryStep("5", "content_search", "内容搜索回落")
        )
        chainRegistry["write_file"] = listOf(
            RecoveryStep("1", "append_file", "追加写入"),
            RecoveryStep("2", "edit_file", "编辑写入"),
            RecoveryStep("3", "sed_replace", "sed替换写入"),
            RecoveryStep("4", "shell_exec", "shell命令写入")
        )
        chainRegistry["visit_web"] = listOf(
            RecoveryStep("1", "web_fetch", "HTTP获取"),
            RecoveryStep("2", "http_get", "原始HTTP请求"),
            RecoveryStep("3", "curl", "curl命令"),
            RecoveryStep("4", "browser_open", "浏览器打开")
        )
        chainRegistry["delete_file"] = listOf(
            RecoveryStep("1", "rm", "rm删除"),
            RecoveryStep("2", "remove", "remove删除"),
            RecoveryStep("3", "trash", "移动到回收站"),
            RecoveryStep("4", "shell_exec", "shell命令删除", params = mapOf("command" to "del"))
        )
        chainRegistry["install_app"] = listOf(
            RecoveryStep("1", "adb_install", "ADB安装"),
            RecoveryStep("2", "pm_install", "包管理器安装"),
            RecoveryStep("3", "sideload", "侧载安装"),
            RecoveryStep("4", "shell_exec", "命令行安装", params = mapOf("command" to "adb install"))
        )
        chainRegistry["capture_screenshot"] = listOf(
            RecoveryStep("1", "screenshot", "截图"),
            RecoveryStep("2", "adb_screencap", "ADB截图"),
            RecoveryStep("3", "screen_capture", "屏幕捕获"),
            RecoveryStep("4", "shell_exec", "命令行截图", params = mapOf("command" to "screencap"))
        )
        chainRegistry["send_message_to_ai"] = listOf(
            RecoveryStep("1", "chat", "聊天接口"),
            RecoveryStep("2", "llm_query", "LLM直连"),
            RecoveryStep("3", "ai_complete", "AI补全"),
            RecoveryStep("4", "http_post", "HTTP POST")
        )
        chainRegistry["ffmpeg_execute"] = listOf(
            RecoveryStep("1", "media_convert", "媒体转换"),
            RecoveryStep("2", "video_process", "视频处理"),
            RecoveryStep("3", "audio_process", "音频处理"),
            RecoveryStep("4", "shell_exec", "命令行FFmpeg")
        )
        chainRegistry["execute_shell"] = listOf(
            RecoveryStep("1", "run_command", "命令运行"),
            RecoveryStep("2", "shell_exec", "Shell执行"),
            RecoveryStep("3", "adb_shell", "ADB Shell")
        )
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        try {
            val operation = task.metadata["operation"] ?: "chain_execute"

            when (operation) {
                "chain_execute" -> executeChain(task, startTime)
                "build_chain" -> buildCustomChain(task, startTime)
                "history" -> chainHistory(task, startTime)
                "clear" -> clearHistory(task, startTime)
                else -> BurstSkillResult(
                    success = false,
                    errorMessage = "Unknown chain operation: $operation",
                    metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = "RecoveryChain failed: ${e.message}",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }
    }

    private suspend fun executeChain(task: BurstTask, startTime: Long): BurstSkillResult {
        val toolName = task.metadata["toolName"] ?: return BurstSkillResult(
            success = false,
            errorMessage = "toolName required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )

        val chain = chainRegistry[toolName] ?: return BurstSkillResult(
            success = false,
            errorMessage = "No recovery chain for tool: $toolName",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )

        var lastError: String? = null
        var stepIndex = 0

        for ((i, step) in chain.withIndex()) {
            if (isPaused) break
            stepIndex = i

            var attempt = 0
            while (attempt < step.retryCount && !isPaused) {
                attempt++

                val stepTask = task.copy(
                    id = "${task.id}_chain_${step.id}_attempt_$attempt",
                    metadata = task.metadata + step.params + mapOf(
                        "toolName" to step.toolName,
                        "chainStep" to step.id,
                        "chainDescription" to step.description,
                        "recoveryAttempt" to attempt.toString(),
                        "originalTool" to toolName
                    )
                )

                val result = withTimeoutOrNull(step.timeoutMs) {
                    ctx.kernel.executeSkill("tool_${step.toolName}", stepTask)
                }

                if (result != null && result.success) {
                    val elapsed = System.currentTimeMillis() - startTime
                    recordHistory(toolName, chain, stepIndex, true, step.id, elapsed)

                    return BurstSkillResult(
                        success = true,
                        output = "[CHAIN RECOVERED at step ${step.id}: ${step.description}] ${result.output}",
                        metrics = SkillMetrics(
                            executionTimeMs = elapsed,
                            stepsCompleted = i + 1
                        )
                    )
                }

                lastError = result?.errorMessage ?: "Timeout after ${step.timeoutMs}ms"
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        recordHistory(toolName, chain, stepIndex, false, null, elapsed)

        return BurstSkillResult(
            success = false,
            output = "Recovery chain exhausted for $toolName (${chain.size} steps)",
            errorMessage = lastError ?: "All recovery steps failed",
            metrics = SkillMetrics(
                executionTimeMs = elapsed,
                stepsCompleted = chain.size
            )
        )
    }

    private fun buildCustomChain(task: BurstTask, startTime: Long): BurstSkillResult {
        val toolName = task.metadata["toolName"] ?: return BurstSkillResult(
            success = false, errorMessage = "toolName required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
        val stepsRaw = task.metadata["steps"] ?: return BurstSkillResult(
            success = false, errorMessage = "steps (JSON array) required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )

        val parsedSteps = stepsRaw.split(";").mapIndexed { idx, stepDef ->
            val parts = stepDef.split("|")
            RecoveryStep(
                id = idx.toString(),
                toolName = parts.getOrElse(0) { "unknown" },
                description = parts.getOrElse(1) { "" },
                retryCount = parts.getOrElse(2) { "1" }.toIntOrNull() ?: 1,
                timeoutMs = parts.getOrElse(3) { "5000" }.toLongOrNull() ?: 5000L
            )
        }

        chainRegistry[toolName] = chainRegistry.getOrDefault(toolName, emptyList()) + parsedSteps

        return BurstSkillResult(
            success = true,
            output = "Registered ${parsedSteps.size} custom recovery steps for $toolName",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    private fun chainHistory(task: BurstTask, startTime: Long): BurstSkillResult {
        val recent = chainHistory.entries.toList().takeLast(20).joinToString("\n") { (key, exec) ->
            "  [$key] ${exec.originalTool} -> step ${exec.lastStep}/${exec.steps.size} " +
                "winner=${exec.winnerStep ?: "none"} ${if (exec.success) "OK" else "FAIL"}"
        }
        return BurstSkillResult(
            success = true,
            output = "Chain execution history (last 20):\n$recent",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    private fun clearHistory(task: BurstTask, startTime: Long): BurstSkillResult {
        val count = chainHistory.size
        chainHistory.clear()
        return BurstSkillResult(
            success = true,
            output = "Cleared $count chain history entries",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    private fun recordHistory(
        originalTool: String,
        steps: List<RecoveryStep>,
        lastStep: Int,
        success: Boolean,
        winnerStep: String?,
        totalTime: Long
    ) {
        val key = "${originalTool}_${System.currentTimeMillis()}"
        chainHistory[key] = ChainExecution(
            chainId = key,
            originalTool = originalTool,
            steps = steps,
            lastStep = lastStep,
            success = success,
            winnerStep = winnerStep,
            totalTime = totalTime
        )
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel(); chainRegistry.clear(); chainHistory.clear() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.90f
}
