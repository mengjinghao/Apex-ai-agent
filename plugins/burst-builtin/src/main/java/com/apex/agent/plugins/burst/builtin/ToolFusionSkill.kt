package com.apex.agent.plugins.burst.builtin

import kotlinx.coroutines.Dispatchers

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class ToolFusionSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var ctx: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val fusionCache = ConcurrentHashMap<String, List<String>>()

    init {
        manifest = BurstSkillManifest(
            skillId = "tool_fusion",
            skillName = "工具熔断融合",
            version = "1.0.0",
            description = "工具调用失败时并行尝试所有备选工具，自动发现融合策略",
            author = "Apex Agent",
            tags = listOf("berserk", "fusion", "fallback", "tool"),
            priority = 90,
            capabilities = listOf(
                "parallel_fallback",
                "tool_discovery",
                "fallback_chain_construction",
                "cross_tool_substitution"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.ctx = context
        buildFusionStrategies()
    }

    private fun buildFusionStrategies() {
        fusionCache["click_element"] = listOf(
            "click_element", "tap", "long_press", "press_key",
            "scroll_and_click", "coordinate_click", "accessibility_click"
        )
        fusionCache["input_text"] = listOf(
            "input_text", "set_text", "type_text", "paste_text",
            "adb_input", "keyboard_type"
        )
        fusionCache["read_file"] = listOf(
            "read_file", "cat", "less", "head", "tail",
            "grep_code", "content_search"
        )
        fusionCache["write_file"] = listOf(
            "write_file", "append_file", "edit_file", "sed_replace"
        )
        fusionCache["delete_file"] = listOf(
            "delete_file", "rm", "remove", "trash"
        )
        fusionCache["list_files"] = listOf(
            "list_files", "ls", "dir", "glob", "tree"
        )
        fusionCache["visit_web"] = listOf(
            "visit_web", "web_fetch", "http_get", "curl",
            "browser_open", "webview_load"
        )
        fusionCache["execute_shell"] = listOf(
            "execute_shell", "run_command", "shell_exec", "adb_shell"
        )
        fusionCache["install_app"] = listOf(
            "install_app", "adb_install", "pm_install", "sideload"
        )
        fusionCache["capture_screenshot"] = listOf(
            "capture_screenshot", "screenshot", "screen_capture",
            "adb_screencap", "ui_screenshot"
        )
        fusionCache["send_message_to_ai"] = listOf(
            "send_message_to_ai", "chat", "llm_query", "ai_complete"
        )
        fusionCache["ffmpeg_execute"] = listOf(
            "ffmpeg_execute", "media_convert", "video_process", "audio_process"
        )
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val operation = task.metadata["operation"] ?: "fuse"

            when (operation) {
                "fuse" -> executeFusion(task, startTime)
                "discover" -> discoverFallbacks(task, startTime)
                "add_strategy" -> addStrategy(task, startTime)
                else -> BurstSkillResult(
                    success = false,
                    errorMessage = "Unknown fusion operation: $operation",
                    metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = "ToolFusion failed: ${e.message}",
                metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
            )
        }
    }

    private suspend fun executeFusion(task: BurstTask, startTime: Long): BurstSkillResult {
        val toolName = task.metadata["toolName"] ?: return BurstSkillResult(
            success = false,
            errorMessage = "toolName required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
        val params = task.metadata
        val fusionGroup = fusionCache[toolName] ?: listOf(toolName)

        val results = ConcurrentHashMap<String, BurstSkillResult>()

        coroutineScope {
            fusionGroup.map { altTool ->
                async {
                    val altTask = task.copy(
                        id = "${task.id}_fusion_$altTool",
                        metadata = params + mapOf("toolName" to altTool, "isFallback" to "true")
                    )
                    val result = ctx.kernel.executeSkill("tool_$altTool", altTask)
                    results[altTool] = result
                }
            }
        }

        val firstSuccess = results.entries.firstOrNull { it.value.success }
        if (firstSuccess != null) {
            return BurstSkillResult(
                success = true,
                output = "[${firstSuccess.key}] ${firstSuccess.value.output}",
                errorMessage = null,
                metrics = SkillMetrics(
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    stepsCompleted = results.size
                )
            )
        }

        val summary = results.entries.joinToString("\n") { (name, r) ->
            "  $name: FAIL - ${r.errorMessage ?: "unknown error"}"
        }
        return BurstSkillResult(
            success = false,
            output = "All ${fusionGroup.size} fusion attempts failed",
            errorMessage = summary,
            metrics = SkillMetrics(
                executionTimeMs = System.currentTimeMillis() - startTime,
                stepsCompleted = results.size
            )
        )
    }

    private suspend fun discoverFallbacks(task: BurstTask, startTime: Long): BurstSkillResult {
        val availableSkills = ctx.kernel.getAvailableSkills()
        val fallbackMap = fusionCache.mapValues { (_, group) ->
            group.filter { alt -> availableSkills.any { it.skillId == "tool_$alt" } }
        }

        val report = fallbackMap.entries.joinToString("\n") { (tool, alts) ->
            "  $tool -> [${alts.joinToString(", ")}]"
        }

        return BurstSkillResult(
            success = true,
            output = "Available fallback strategies:\n$report",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    private fun addStrategy(task: BurstTask, startTime: Long): BurstSkillResult {
        val toolName = task.metadata["toolName"] ?: return BurstSkillResult(
            success = false,
            errorMessage = "toolName required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
        val fallbacks = task.metadata["fallbacks"]?.split(",") ?: return BurstSkillResult(
            success = false,
            errorMessage = "fallbacks (comma-separated) required",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )

        fusionCache[toolName] = fusionCache.getOrDefault(toolName, emptyList()) + fallbacks

        return BurstSkillResult(
            success = true,
            output = "Added fallbacks for $toolName: ${fallbacks.joinToString(", ")}",
            metrics = SkillMetrics(executionTimeMs = System.currentTimeMillis() - startTime)
        )
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel(); fusionCache.clear() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.88f
}
