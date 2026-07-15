package com.apex.agent.mts.bridge

import com.apex.agent.domain.model.BurstInput
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.mts.executor.ToolInvoker
import com.apex.agent.mts.schema.ExecutionResult
import com.apex.agent.mts.schema.ToolOutcome
import com.apex.agent.mts.registry.ToolRegistry
import com.apex.agent.mts.schema.ParsedToolCall
import com.apex.agent.mts.schema.ToolSpec
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BerserkModeOrchestrator(
    private val registry: ToolRegistry,
    private val invoker: ToolInvoker
) {
    private val kernel get() = BurstKernel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val racingContestants = mapOf(
        "read_file" to listOf("read_file", "cat", "less", "head", "tail"),
        "list_files" to listOf("list_files", "ls", "glob", "dir"),
        "capture_screenshot" to listOf("capture_screenshot", "adb_screencap", "screen_capture"),
        "visit_web" to listOf("visit_web", "http_get", "web_fetch", "curl"),
        "grep_code" to listOf("grep_code", "content_search", "ripgrep")
    )
        private val fusionStrategies = mapOf(
        "click_element" to listOf("click_element", "tap", "long_press", "press_key", "scroll_and_click", "coordinate_click", "accessibility_click"),
        "input_text" to listOf("input_text", "set_text", "type_text", "paste_text", "adb_input", "keyboard_type"),
        "read_file" to listOf("read_file", "cat", "less", "head", "tail", "grep_code", "content_search"),
        "write_file" to listOf("write_file", "append_file", "edit_file", "sed_replace"),
        "delete_file" to listOf("delete_file", "rm", "remove", "trash"),
        "list_files" to listOf("list_files", "ls", "dir", "glob", "tree"),
        "visit_web" to listOf("visit_web", "web_fetch", "http_get", "curl", "browser_open", "webview_load"),
        "execute_shell" to listOf("execute_shell", "run_command", "shell_exec", "adb_shell"),
        "install_app" to listOf("install_app", "adb_install", "pm_install", "sideload"),
        "capture_screenshot" to listOf("capture_screenshot", "screenshot", "screen_capture", "adb_screencap", "ui_screenshot"),
        "send_message_to_ai" to listOf("send_message_to_ai", "chat", "llm_query", "ai_complete"),
        "ffmpeg_execute" to listOf("ffmpeg_execute", "media_convert", "video_process", "audio_process")
    )

    suspend fun executeBerserk(call: ParsedToolCall): ExecutionResult {
        val spec = call.toolSpec
        val toolName = spec.name

        val result = if (racingContestants.containsKey(toolName)) {
            executeWithRacing(call, spec)
        } else {
            executeWithRetry(call, spec)
        }
        if (result.outcome is ToolOutcome.Failure && fusionStrategies.containsKey(toolName)) {
            val fusion = executeWithFusion(call, spec)
        if (fusion != null) return fusion
        }
        return result
    }
        private suspend fun executeWithRacing(call: ParsedToolCall, spec: ToolSpec): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val contestants = racingContestants[spec.name] ?: return executeDefault(call)
        val winner = CompletableDeferred<ExecutionResult>()
        val jobs = contestants.mapNotNull { name ->
            registry.getByName(name)?.let { altSpec ->
                async(start = CoroutineStart.LAZY) {
                    if (winner.isCompleted) return@async
                    try {
                        val outcome = withTimeout(15000) { invoker.invoke(altSpec, call.arguments) }
        if (outcome is ToolOutcome.Success) {
                            winner.tryComplete(ExecutionResult(
                                toolCallId = call.id,
                                toolName = "${spec.name}[RACER:$name]",
                                outcome = outcome,
                                durationMs = System.currentTimeMillis() - startTime
                            ))
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        jobs.forEach { it.start() }
        return withTimeoutOrNull(30000L) { winner.await() } ?: run {
            val fallback = executeDefault(call)
            fallback
        }
    }
        private suspend fun executeDefault(call: ParsedToolCall): ExecutionResult {
        val startTime = System.currentTimeMillis()
        return try {
            val outcome = invoker.invoke(call.toolSpec, call.arguments)
            ExecutionResult(
                toolCallId = call.id,
                toolName = call.toolSpec.name,
                outcome = outcome,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ExecutionResult(
                toolCallId = call.id,
                toolName = call.toolSpec.name,
                outcome = ToolOutcome.Failure(e.message ?: "Unknown error"),
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }
        private suspend fun executeWithRetry(call: ParsedToolCall, spec: ToolSpec): ExecutionResult {
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < 99) {
            attempt++
            try {
                val outcome = withTimeout(Long.MAX_VALUE) {
                    invoker.invoke(spec, call.arguments)
                }
        return ExecutionResult(
                    toolCallId = call.id,
                    toolName = spec.name,
                    outcome = outcome,
                    durationMs = System.currentTimeMillis() - startTime,
                    retryCount = attempt - 1
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                delay(calculateDelay(attempt))
            }
        }
        return ExecutionResult(
            toolCallId = call.id,
            toolName = spec.name,
            outcome = ToolOutcome.Failure(lastError?.message ?: "Failed after 99 retries"),
            durationMs = System.currentTimeMillis() - startTime,
            retryCount = 99
        )
    }
        private suspend fun executeWithFusion(call: ParsedToolCall, spec: ToolSpec): ExecutionResult? {
        val startTime = System.currentTimeMillis()
        val fusions = fusionStrategies[spec.name] ?: return null

        val results = ConcurrentHashMap<String, ExecutionResult>()

        coroutineScope {
            fusions.mapNotNull { name ->
                registry.getByName(name)?.let { altSpec ->
                    if (name == spec.name) null else async {
                        try {
                            val outcome = withTimeout(10000) { invoker.invoke(altSpec, call.arguments) }
                            results[name] = ExecutionResult(
                                toolCallId = call.id,
                                toolName = "${spec.name}[FUSION:$name]",
                                outcome = outcome,
                                durationMs = System.currentTimeMillis() - startTime
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return results.entries.firstOrNull { it.value.outcome is ToolOutcome.Success }?.value
    }
        private fun calculateDelay(attempt: Int): Long = (50L * (1 shl (attempt - 1).coerceAtMost(5))).coerceAtMost(1000L)
        fun stop() { scope.cancel() }
        fun toBurstTask(call: ParsedToolCall, operation: String): BurstTask = BurstTask(
        id = UUID.randomUUID().toString(),
        name = "berserk_${call.toolSpec.name}",
        description = operation,
        input = BurstInput(
            text = call.arguments.toString(),
            parameters = call.arguments.mapValues { it.value.toString() }
        ),
        metadata = call.arguments.mapValues { it.value.toString() } +
            mapOf("toolName" to call.toolSpec.name, "operation" to operation),
        priority = Int.MAX_VALUE,
        maxRetries = 99
    )
}
