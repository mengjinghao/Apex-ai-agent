package com.apex.agent.mts.executor

import com.apex.agent.mts.registry.ToolRegistry
import com.apex.agent.mts.schema.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.system.measureTimeMillis

interface ToolInvoker {
    suspend fun invoke(toolSpec: ToolSpec, args: Map<String, Any?>): ToolOutcome
}

data class ExecutionConfig(
    val maxParallelism: Int = 4,
    val defaultTimeoutMs: Long = 30000,
    val maxRetries: Int = 2,
    val enableCircuitBreaker: Boolean = true,
    val circuitBreakerThreshold: Int = 5,
    val circuitBreakerResetMs: Long = 60000,
    val enableDependencyAnalysis: Boolean = true,
    /** 狂暴专属：跳过所有权限检查 */
    val skipPermissionChecks: Boolean = false,
    /** 狂暴专属：工具融合 — 单个工具失败时并行尝试所有备选 */
    val enableToolFusion: Boolean = false,
    /** 狂暴专属：竞速模式 — 对读操作用多个工具并发争抢最快结果 */
    val enableRacing: Boolean = false,
    /** 狂暴专属：UI暴力破解 — 同时尝试多种方式操作UI */
    val bruteForceUI: Boolean = false,
    /** 狂暴专属：推测执行 — 无视依赖关系提前执行后续工具 */
    val speculativeExecution: Boolean = false,
    /** 狂暴专属：无限重试 — 任何失败都重试直到成功或手动终止 */
    val infiniteRetry: Boolean = false
) {
    companion object {
        val NORMAL = ExecutionConfig(
            maxParallelism = 2,
            defaultTimeoutMs = 30000,
            maxRetries = 1,
            enableCircuitBreaker = true,
            circuitBreakerThreshold = 3
        )

        val MULTI_AGENT = ExecutionConfig(
            maxParallelism = 6,
            defaultTimeoutMs = 60000,
            maxRetries = 2,
            enableCircuitBreaker = true,
            circuitBreakerThreshold = 5
        )

        val BERSERK = ExecutionConfig(
            maxParallelism = Int.MAX_VALUE,
            defaultTimeoutMs = Long.MAX_VALUE,
            maxRetries = 99,
            enableCircuitBreaker = false,
            circuitBreakerThreshold = Int.MAX_VALUE,
            circuitBreakerResetMs = 1000,
            enableDependencyAnalysis = false,
            skipPermissionChecks = true,
            enableToolFusion = true,
            enableRacing = true,
            bruteForceUI = true,
            speculativeExecution = true,
            infiniteRetry = true
        )

        fun forMode(mode: AgentMode): ExecutionConfig = when (mode) {
            AgentMode.NORMAL -> NORMAL
            AgentMode.MULTI_AGENT -> MULTI_AGENT
            AgentMode.BERSERK -> BERSERK
        }
    }
}

data class ExecutionDAG(
    val nodes: List<ExecutionNode>,
    val edges: List<ExecutionEdge>
)

data class ExecutionNode(
    val call: ParsedToolCall,
    val order: Int,
    val dependsOn: List<String> = emptyList()
)

data class ExecutionEdge(
    val from: String,
    val to: String
)

data class ExecutionSummary(
    val totalCalls: Int,
    val succeeded: Int,
    val failed: Int,
    val cancelled: Int,
    val totalDurationMs: Long,
    val results: List<ExecutionResult>,
    val errors: Map<String, String>
)

class DependencyAnalyzer {
    fun analyze(calls: List<ParsedToolCall>): ExecutionDAG {
        val nodes = calls.mapIndexed { index, call ->
            val deps = findDependencies(call, calls)
            ExecutionNode(call = call, order = index, dependsOn = deps)
        }
        val edges = mutableListOf<ExecutionEdge>()
        for (node in nodes) {
            for (dep in node.dependsOn) {
                edges.add(ExecutionEdge(from = dep, to = node.call.rawName))
            }
        }
        return ExecutionDAG(nodes, edges)
    }

    private fun findDependencies(
        call: ParsedToolCall,
        allCalls: List<ParsedToolCall>
    ): List<String> {
        val deps = mutableListOf<String>()
        val args = call.arguments.values.map { it?.toString()?.lowercase() ?: "" }

        for (other in allCalls) {
            if (other.toolCallId == call.toolCallId) continue
            val otherIdx = allCalls.indexOf(other)
            val thisIdx = allCalls.indexOf(call)
            if (otherIdx >= thisIdx) continue

            if (isWriteTool(other.toolSpec) && isReadTool(call.toolSpec)) {
                deps.add(other.rawName)
            }

            val outputMatches = args.any { arg ->
                other.toolSpec.outputDescription?.let { desc ->
                    desc.lowercase().split(" ").any { arg.contains(it) }
                } ?: false
            }
            if (outputMatches) {
                deps.add(other.rawName)
            }
        }
        return deps.distinct()
    }

    private fun isWriteTool(spec: ToolSpec): Boolean = when (spec.name) {
        "write_file", "write_file_binary", "delete_file", "move_file", "copy_file",
        "apply_file", "set_input_text", "modify_system_setting", "install_app",
        "uninstall_app", "send_broadcast", "execute_intent", "create_memory",
        "update_memory", "delete_memory", "update_user_preferences",
        "create_workflow", "update_workflow", "delete_workflow",
        "set_sandbox_package_enabled", "write_environment_variable" -> true
        else -> false
    }

    private fun isReadTool(spec: ToolSpec): Boolean = when (spec.name) {
        "read_file", "read_file_part", "read_file_full", "list_files",
        "file_exists", "file_info", "find_files", "grep_code", "grep_context",
        "device_info", "get_system_setting", "query_memory",
        "get_memory_by_title", "get_all_workflows", "get_workflow",
        "browser_snapshot", "capture_screenshot" -> true
        else -> false
    }

    fun findParallelizable(calls: List<ParsedToolCall>): Set<String> {
        val dag = analyze(calls)
        val nameCount = mutableMapOf<String, Int>()
        for (node in dag.nodes) {
            nameCount[node.call.rawName] = (nameCount[node.call.rawName] ?: 0) + 1
        }

        return dag.nodes
            .filter { node ->
                node.dependsOn.isEmpty() &&
                    (nameCount[node.call.rawName] ?: 0) <= 1 &&
                    node.call.toolSpec.parallelSafe
            }
            .map { it.call.rawName }
            .toSet()
    }
}

class CircuitBreaker(
    private var threshold: Int = 5,
    private var resetMs: Long = 60000
) {
    fun reset(threshold: Int, resetMs: Long) {
        this.threshold = threshold
        this.resetMs = resetMs
    }
    private data class BreakerState(
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        var isOpen: Boolean = false
    )
    private val breakers = ConcurrentHashMap<String, BreakerState>()

    fun allowCall(toolName: String): Boolean {
        val state = breakers.getOrPut(toolName) { BreakerState() }
        if (state.isOpen) {
            if (System.currentTimeMillis() - state.lastFailureTime > resetMs) {
                state.isOpen = false
                state.failureCount = 0
                return true
            }
            return false
        }
        return true
    }

    fun recordFailure(toolName: String) {
        val state = breakers.getOrPut(toolName) { BreakerState() }
        state.failureCount++
        state.lastFailureTime = System.currentTimeMillis()
        if (state.failureCount >= threshold) {
            state.isOpen = true
        }
    }

    fun recordSuccess(toolName: String) {
        val state = breakers.getOrPut(toolName) { BreakerState() }
        state.failureCount = 0
    }
}

class SmartExecutor(
    private val registry: ToolRegistry,
    private val invoker: ToolInvoker,
    private val defaultConfig: ExecutionConfig = ExecutionConfig.NORMAL
) {
    private val analyzer = DependencyAnalyzer()
    private var activeConfig: ExecutionConfig = defaultConfig
    private val circuitBreaker = CircuitBreaker()
    private val semaphore = Semaphore()

    private class Semaphore {
        private val current = AtomicInteger(0)

        suspend fun acquire(max: Int) {
            if (max == Int.MAX_VALUE) return
            while (true) {
                val c = current.get()
                if (c >= max) {
                    delay(1)
                    continue
                }
                if (current.compareAndSet(c, c + 1)) return
            }
        }

        fun release() {
            current.decrementAndGet()
        }
    }

    suspend fun execute(
        calls: List<ParsedToolCall>,
        context: ExecutionContext = ExecutionContext()
    ): ExecutionSummary {
        activeConfig = ExecutionConfig.forMode(context.agentMode)
        circuitBreaker.reset(activeConfig.circuitBreakerThreshold, activeConfig.circuitBreakerResetMs)
        val startTime = System.currentTimeMillis()
        val results = ConcurrentHashMap<String, ExecutionResult>()
        val errors = ConcurrentHashMap<String, String>()

        val isBerserk = context.agentMode == AgentMode.BERSERK

        val dag = if (activeConfig.enableDependencyAnalysis && !isBerserk) {
            analyzer.analyze(calls)
        } else {
            ExecutionDAG(
                calls.mapIndexed { i, c -> ExecutionNode(c, i) },
                emptyList()
            )
        }

        val completed = ConcurrentHashMap.newKeySet<String>()
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            val jobs = if (isBerserk) {
                launchBerserk(dag, completed, inFlight, results, errors, context)
            } else {
                dag.nodes.map { node ->
                    async {
                        executeWithDependencies(
                            node, dag, completed, inFlight, results, errors, context
                        )
                    }
                }
            }
            if (jobs is List) {
                jobs.forEach { it.await() }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val resultList = dag.nodes.mapNotNull { node ->
            results[node.call.toolCallId]
        }

        return ExecutionSummary(
            totalCalls = calls.size,
            succeeded = resultList.count { it.outcome is ToolOutcome.Success },
            failed = resultList.count { it.outcome is ToolOutcome.Failure },
            cancelled = resultList.count { it.outcome is ToolOutcome.Cancelled },
            totalDurationMs = duration,
            results = resultList,
            errors = errors.toMap()
        )
    }

    private fun coroutineScope(block: suspend CoroutineScope.() -> List<Job>): List<Job> {
        return kotlinx.coroutines.coroutineScope { block() }
    }

    private fun CoroutineScope.launchBerserk(
        dag: ExecutionDAG,
        completed: MutableSet<String>,
        inFlight: MutableSet<String>,
        results: MutableMap<String, ExecutionResult>,
        errors: MutableMap<String, String>,
        context: ExecutionContext
    ): List<Job> {
        return dag.nodes.map { node ->
            launch {
                try {
                    val toolName = node.call.rawName
                    val toolSpec = node.call.toolSpec

                    inFlight.add(toolName)

                    val result = if (activeConfig.enableRacing && isReadOperation(toolSpec)) {
                        executeRacing(node.call, toolSpec, context)
                    } else {
                        executeWithRetry(node.call, toolSpec, context)
                    }

                    results[node.call.toolCallId] = result

                    if (result.outcome is ToolOutcome.Failure && activeConfig.enableToolFusion) {
                        val fusionResult = executeToolFusion(node.call, toolSpec, context)
                        if (fusionResult != null) {
                            results[node.call.toolCallId] = fusionResult
                        }
                    }

                    if (result.outcome is ToolOutcome.Success) {
                        circuitBreaker.recordSuccess(toolName)
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Berserk execution error"
                    errors[node.call.toolCallId] = errorMsg
                    results[node.call.toolCallId] = ExecutionResult(
                        toolCallId = node.call.toolCallId,
                        toolName = node.call.rawName,
                        outcome = ToolOutcome.Failure(errorMsg, recoverable = true),
                        durationMs = 0
                    )
                } finally {
                    inFlight.remove(node.call.rawName)
                    completed.add(node.call.toolCallId)
                }
            }
        }
    }

    private fun isReadOperation(spec: ToolSpec): Boolean {
        return spec.name in setOf(
            "read_file", "list_files", "find_files", "grep_code", "grep_context",
            "visit_web", "http_request", "query_memory", "device_info",
            "browser_snapshot", "capture_screenshot"
        )
    }

    private suspend fun executeRacing(
        call: ParsedToolCall,
        spec: ToolSpec,
        context: ExecutionContext
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val competitors = findRacingCompetitors(spec)
        if (competitors.isEmpty()) {
            return executeWithRetry(call, spec, context)
        }

        val allAttempts = listOf(spec) + competitors
        val results = mutableMapOf<String, ToolOutcome>()

        coroutineScope {
            val jobs = allAttempts.map { attempt ->
                async {
                    try {
                        val outcome = invoker.invoke(attempt, call.arguments)
                        synchronized(results) {
                            if (results.values.none { it is ToolOutcome.Success }) {
                                results[attempt.name] = outcome
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            jobs.forEach {
                try {
                    withTimeout(5000) { it.await() }
                } catch (_: Exception) { }
            }
        }

        val winner = results.entries.firstOrNull { it.value is ToolOutcome.Success }
        if (winner != null) {
            return ExecutionResult(
                toolCallId = call.toolCallId,
                toolName = "${spec.name}(${winner.key})",
                outcome = winner.value,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        return executeWithRetry(call, spec, context)
    }

    private fun findRacingCompetitors(spec: ToolSpec): List<ToolSpec> {
        return when (spec.name) {
            "read_file" -> listOfNotNull(
                registry.getByName("read_file_part"),
                registry.getByName("read_file_full")
            )
            "list_files" -> listOfNotNull(
                registry.getByName("find_files")
            )
            "click_element" -> listOfNotNull(
                registry.getByName("tap")
            )
            "browser_navigate" -> listOfNotNull(
                registry.getByName("visit_web")
            )
            "execute_shell" -> listOfNotNull(
                registry.getByName("execute_hidden_terminal_command"),
                registry.getByName("create_terminal_session")
            )
            "send_message_to_ai" -> listOfNotNull(
                registry.getByName("send_message_to_ai_advanced")
            )
            "ffmpeg_execute" -> listOfNotNull(
                registry.getByName("ffmpeg_convert")
            )
            else -> emptyList()
        }
    }

    private suspend fun executeToolFusion(
        call: ParsedToolCall,
        spec: ToolSpec,
        context: ExecutionContext
    ): ExecutionResult? {
        val fallbacks = findAllFallbacks(spec)
        if (fallbacks.isEmpty()) return null

        val startTime = System.currentTimeMillis()
        lateinit var firstResult: ExecutionResult

        coroutineScope {
            val jobs = fallbacks.map { fallback ->
                async {
                    try {
                        val outcome = invoker.invoke(fallback, call.arguments)
                        if (outcome is ToolOutcome.Success) {
                            firstResult = ExecutionResult(
                                toolCallId = call.toolCallId,
                                toolName = "${spec.name}>>${fallback.name}",
                                outcome = outcome,
                                durationMs = System.currentTimeMillis() - startTime
                            )
                        }
                    } catch (_: Exception) { }
                }
            }
            jobs.forEach {
                try {
                    withTimeout(30000) { it.await() }
                } catch (_: Exception) { }
            }
        }

        return if (::firstResult.isInitialized) firstResult else null
    }

    private fun findAllFallbacks(spec: ToolSpec): List<ToolSpec> {
        val fallbackMap = mapOf(
            "read_file" to listOf("read_file_part", "read_file_full", "read_file_binary", "file_info"),
            "write_file" to listOf("apply_file"),
            "list_files" to listOf("find_files", "file_info"),
            "click_element" to listOf("tap", "long_press"),
            "browser_navigate" to listOf("visit_web", "browser_snapshot"),
            "send_message_to_ai" to listOf("send_message_to_ai_advanced"),
            "execute_shell" to listOf("execute_hidden_terminal_command", "create_terminal_session"),
            "ffmpeg_execute" to listOf("ffmpeg_convert", "ffmpeg_info"),
            "http_request" to listOf("visit_web"),
            "device_info" to listOf("battery_info", "network_info", "system_settings"),
            "query_memory" to listOf("get_memory_by_title", "find_files"),
            "grep_code" to listOf("grep_context", "find_files"),
            "install_app" to listOf("start_app"),
            "set_input_text" to listOf("press_key", "tap"),
            "capture_screenshot" to listOf("browser_take_screenshot"),
            "start_app" to listOf("execute_intent"),
            "create_memory" to listOf("update_memory")
        )
        return fallbackMap[spec.name]?.mapNotNull { registry.getByName(it) } ?: emptyList()
    }

    private fun executeWithDependencies(
        node: ExecutionNode,
        dag: ExecutionDAG,
        completed: MutableSet<String>,
        inFlight: MutableSet<String>,
        results: MutableMap<String, ExecutionResult>,
        errors: MutableMap<String, String>,
        context: ExecutionContext
    ) {
    }

    private fun CoroutineScope.asyncExecuteWithDependencies(
        node: ExecutionNode,
        dag: ExecutionDAG,
        completed: MutableSet<String>,
        inFlight: MutableSet<String>,
        results: MutableMap<String, ExecutionResult>,
        errors: MutableMap<String, String>,
        context: ExecutionContext
    ): kotlinx.coroutines.Deferred<Unit> {
        return async {
            val toolName = node.call.rawName
            val toolSpec = node.call.toolSpec

            for (dep in node.dependsOn) {
                val depNode = dag.nodes.find { it.call.rawName == dep }
                if (depNode != null && completed.contains(depNode.call.toolCallId).not()) {
                    asyncExecuteWithDependencies(
                        depNode, dag, completed, inFlight, results, errors, context
                    ).await()
                }
            }

            if (inFlight.contains(toolName)) {
                results[node.call.toolCallId] = ExecutionResult(
                    toolCallId = node.call.toolCallId,
                    toolName = toolName,
                    outcome = ToolOutcome.Failure("Skipped: another call to '$toolName' is in flight"),
                    durationMs = 0
                )
                return@async
            }

            inFlight.add(toolName)
            try {
                semaphore.acquire(activeConfig.maxParallelism)
                try {
                    val result = executeWithRetry(node.call, toolSpec, context)
                    results[node.call.toolCallId] = result
                    if (result.outcome is ToolOutcome.Success) {
                        circuitBreaker.recordSuccess(toolName)
                    } else {
                        circuitBreaker.recordFailure(toolName)
                    }
                } finally {
                    semaphore.release()
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                errors[node.call.toolCallId] = errorMsg
                results[node.call.toolCallId] = ExecutionResult(
                    toolCallId = node.call.toolCallId,
                    toolName = toolName,
                    outcome = ToolOutcome.Failure(errorMsg, recoverable = false),
                    durationMs = 0
                )
                circuitBreaker.recordFailure(toolName)
            } finally {
                inFlight.remove(toolName)
                completed.add(node.call.toolCallId)
            }
        }
    }

    private suspend fun executeWithRetry(
        call: ParsedToolCall,
        spec: ToolSpec,
        context: ExecutionContext
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val isBerserk = context.agentMode == AgentMode.BERSERK

        if (!isBerserk && activeConfig.enableCircuitBreaker && circuitBreaker.allowCall(spec.name).not()) {
            return ExecutionResult(
                toolCallId = call.toolCallId,
                toolName = spec.name,
                outcome = ToolOutcome.Failure(
                    "Circuit breaker open for '$spec.name'",
                    code = "CIRCUIT_OPEN",
                    recoverable = true
                ),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val timeout = if (isBerserk) {
            Long.MAX_VALUE
        } else {
            spec.constraints.timeoutMs.coerceAtMost(activeConfig.defaultTimeoutMs)
        }
        val maxAttempts = if (isBerserk || activeConfig.infiniteRetry) Int.MAX_VALUE
            else if (spec.errorRecovery == FailureStrategy.FAIL_FAST) 1
            else (activeConfig.maxRetries + 1)

        var lastError: Throwable? = null
        var attempt = 1
        while (attempt <= maxAttempts) {
            try {
                semaphore.acquire(activeConfig.maxParallelism)
                try {
                    val outcome = withTimeout(timeout) {
                        invoker.invoke(spec, call.arguments)
                    }
                    return ExecutionResult(
                        toolCallId = call.toolCallId,
                        toolName = spec.name,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - startTime,
                        retryCount = attempt - 1
                    )
                } finally {
                    semaphore.release()
                }
            } catch (e: TimeoutCancellationException) {
                lastError = e
                if (attempt < maxAttempts) {
                    val delayMs = if (isBerserk) 100L else 1000L * attempt
                    delay(delayMs)
                } else break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxAttempts) break
                if (!isBerserk && !isRetryable(e)) break
                val delayMs = if (isBerserk) {
                    min(50L * attempt, 1000L)
                } else {
                    min(500L * attempt, 10000L)
                }
                delay(delayMs)
            }
            attempt++
        }

        val recoveryResult = if (isBerserk) {
            attemptBerserkRecovery(call, spec, lastError, context)
        } else {
            attemptRecovery(call, spec, lastError)
        }

        return recoveryResult ?: ExecutionResult(
            toolCallId = call.toolCallId,
            toolName = spec.name,
            outcome = ToolOutcome.Failure(
                lastError?.message ?: "Execution failed after $attempt attempts",
                recoverable = isBerserk || spec.errorRecovery != FailureStrategy.FAIL_FAST
            ),
            durationMs = System.currentTimeMillis() - startTime,
            retryCount = attempt - 1
        )
    }

    private suspend fun attemptBerserkRecovery(
        call: ParsedToolCall,
        spec: ToolSpec,
        error: Throwable?,
        context: ExecutionContext
    ): ExecutionResult? {
        val recoveryChain = buildBerserkRecoveryChain(spec)
        var firstSuccess: ExecutionResult? = null

        for ((attemptIdx, recoveryStep) in recoveryChain.withIndex()) {
            if (firstSuccess != null) break
            try {
                val outcome = withTimeout(30000) {
                    invoker.invoke(recoveryStep, call.arguments)
                }
                if (outcome is ToolOutcome.Success) {
                    firstSuccess = ExecutionResult(
                        toolCallId = call.toolCallId,
                        toolName = "${spec.name}>>>${recoveryStep.name}",
                        outcome = outcome,
                        durationMs = 0,
                        retryCount = attemptIdx + 1
                    )
                }
            } catch (_: Exception) { }
        }

        return firstSuccess
    }

    private fun buildBerserkRecoveryChain(failed: ToolSpec): List<ToolSpec> {
        val chain = mutableListOf<ToolSpec>()

        when (failed.name) {
            "click_element" -> {
                listOf("tap", "long_press", "press_key").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "read_file" -> {
                listOf("read_file_full", "read_file_part", "read_file_binary", "file_info").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "write_file" -> {
                listOf("apply_file", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "visit_web" -> {
                listOf("http_request", "browser_navigate", "browser_snapshot").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "execute_shell" -> {
                listOf("execute_hidden_terminal_command", "create_terminal_session",
                    "execute_in_terminal_session").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "list_files" -> {
                listOf("find_files", "file_info", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "grep_code" -> {
                listOf("grep_context", "find_files", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "install_app" -> {
                listOf("start_app", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "delete_file" -> {
                registry.getByName("execute_shell")?.let { chain.add(it) }
            }
            "send_message_to_ai" -> {
                listOf("send_message_to_ai_advanced", "create_new_chat",
                    "start_chat_service").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "capture_screenshot" -> {
                listOf("browser_take_screenshot", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "ffmpeg_execute" -> {
                listOf("ffmpeg_convert", "ffmpeg_info", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "start_app" -> {
                listOf("execute_intent", "execute_shell").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            else -> {
                findAllFallbacks(failed).forEach { chain.add(it) }
            }
        }

        return chain.distinct()
    }

    private suspend fun attemptRecovery(
        call: ParsedToolCall,
        spec: ToolSpec,
        error: Throwable?
    ): ExecutionResult? {
        return when (spec.errorRecovery) {
            FailureStrategy.FALLBACK_CHAIN -> {
                val fallback = findFallbackTool(spec)
                if (fallback != null) {
                    val outcome = invoker.invoke(fallback, call.arguments)
                    ExecutionResult(
                        toolCallId = call.toolCallId,
                        toolName = "${spec.name}->${fallback.name}",
                        outcome = outcome,
                        durationMs = 0,
                        retryCount = 1
                    )
                } else null
            }
            else -> null
        }
    }

    private fun findFallbackTool(failed: ToolSpec): ToolSpec? {
        val fallbackMap = mapOf(
            "read_file" to listOf("read_file_part", "read_file_full"),
            "browser_navigate" to listOf("visit_web"),
            "send_message_to_ai" to listOf("send_message_to_ai_advanced"),
            "execute_shell" to listOf("execute_hidden_terminal_command"),
            "click_element" to listOf("tap"),
            "ffmpeg_execute" to listOf("ffmpeg_convert")
        )
        return fallbackMap[failed.name]?.mapNotNull { registry.getByName(it) }?.firstOrNull()
    }

    private fun isRetryable(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("timeout") || msg.contains("network") ||
            msg.contains("unavailable") || msg.contains("busy") ||
            msg.contains("rate limit") || msg.contains("too many") ||
            msg.contains("refused") || msg.contains("reset") ||
            msg.contains("interrupted") || msg.contains("temporarily")
    }
}

private fun <T> coroutineScope(block: suspend CoroutineScope.() -> T): T {
    return kotlinx.coroutines.coroutineScope { block() }
}

fun List<ParsedToolCall>.toExecutionFlow(
    executor: SmartExecutor,
    context: ExecutionContext = ExecutionContext()
): Flow<ExecutionResult> = flow {
    val summary = executor.execute(this@toExecutionFlow, context)
    for (result in summary.results) {
        emit(result)
    }
}
