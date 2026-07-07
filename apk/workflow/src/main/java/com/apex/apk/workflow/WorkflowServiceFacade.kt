package com.apex.apk.workflow

import android.content.Context
import com.apex.lib.workflow.ExecutionContext
import com.apex.lib.workflow.NodeResult
import com.apex.lib.workflow.WorkflowDefinition
import com.apex.lib.workflow.WorkflowEvent
import com.apex.lib.workflow.WorkflowExecutor
import com.apex.lib.workflow.WorkflowNodeSpec
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Workflow APK 的核心服务实现。
 *
 * 包装 [WorkflowExecutor]，对其他 APK 暴露统一的 Kotlin API。
 *
 * **能力清单**：
 *   1. 注册 / 列出 / 删除工作流定义
 *   2. 同步 / 异步执行工作流
 *   3. 8 种节点类型：LLM 调用 / 工具调用 / 条件 / 循环 / 并行 / HTTP / 终端 / 代码
 *   4. 自定义节点处理器
 *   5. 断点续跑（待扩展）
 *   6. 执行历史查询
 */
class WorkflowServiceFacade(private val context: Context) {

    private val executor = WorkflowExecutor()
    private val workflows = mutableMapOf<String, WorkflowDefinition>()
    private val history = mutableListOf<WorkflowExecutionRecord>()

    private val _events = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    init {
        registerBuiltinWorkflows()
    }

    /**
     * 注册一个工作流定义。
     */
    suspend fun registerWorkflow(workflow: WorkflowDefinition): BridgeResult<Unit> = bridgeRun {
        workflows[workflow.id] = workflow
        ApexLog.i(ApexSuite.ApkId.WORKFLOW, "[Facade] workflow registered: ${workflow.id} (${workflow.displayName})")
    }

    /**
     * 异步执行工作流。
     */
    suspend fun execute(
        workflowId: String,
        inputs: Map<String, Any> = emptyMap()
    ): BridgeResult<ExecutionContext> = bridgeRun {
        val workflow = workflows[workflowId] ?: throw IllegalStateException("workflow not found: $workflowId")
        val startMs = System.currentTimeMillis()
        val result = executor.execute(workflow, inputs)
        val ctx = result.getOrNull() ?: throw IllegalStateException("execution failed")
        history.add(
            WorkflowExecutionRecord(
                workflowId = workflowId,
                traceId = ctx.traceId,
                startedAt = startMs,
                completedAt = System.currentTimeMillis(),
                success = true,
                nodeCount = ctx.history.size
            )
        )
        ctx
    }

    /**
     * 列出所有已注册的工作流。
     */
    suspend fun listWorkflows(): BridgeResult<List<WorkflowSummary>> = bridgeRun {
        workflows.values.map { w ->
            WorkflowSummary(
                id = w.id,
                displayName = w.displayName,
                nodeCount = w.nodes.size,
                edgeCount = w.edges.size,
                entryNodeId = w.entryNodeId
            )
        }
    }

    /**
     * 删除工作流。
     */
    fun unregisterWorkflow(workflowId: String): Boolean {
        return workflows.remove(workflowId) != null
    }

    /**
     * 注册自定义节点处理器。
     */
    fun registerHandler(nodeType: String, handler: suspend (WorkflowNodeSpec, ExecutionContext) -> NodeResult) {
        executor.registerHandler(nodeType, handler)
    }

    /**
     * 查询执行历史。
     */
    fun getHistory(): List<WorkflowExecutionRecord> = history.toList()

    /**
     * 从 JSON 解析工作流定义。
     */
    fun parseWorkflow(jsonStr: String): WorkflowDefinition? {
        return try {
            Json.decodeFromString(WorkflowDefinition.serializer(), jsonStr)
        } catch (t: Throwable) {
            ApexLog.w(ApexSuite.ApkId.WORKFLOW, "[Facade] parseWorkflow failed: ${t.message}")
            null
        }
    }

    /**
     * 把工作流定义序列化为 JSON。
     */
    fun serializeWorkflow(workflow: WorkflowDefinition): String {
        return Json.encodeToString(WorkflowDefinition.serializer(), workflow)
    }

    private fun registerBuiltinWorkflows() {
        // 内置工作流 1：简单的 LLM 调用链
        val simpleLlm = WorkflowDefinition(
            id = "builtin.simple-llm-chain",
            displayName = "简单 LLM 调用链",
            nodes = listOf(
                WorkflowNodeSpec.LlmCall(
                    id = "step1",
                    displayName = "第一步：理解任务",
                    promptTemplate = "请分析以下任务并给出执行步骤：{{input}}"
                ),
                WorkflowNodeSpec.LlmCall(
                    id = "step2",
                    displayName = "第二步：生成结果",
                    promptTemplate = "基于以下分析结果生成最终回答：{{step1.output}}"
                )
            ),
            edges = listOf(
                com.apex.lib.workflow.WorkflowEdge(from = "step1", to = "step2")
            ),
            entryNodeId = "step1"
        )
        workflows[simpleLlm.id] = simpleLlm

        // 内置工作流 2：HTTP 请求 + 处理
        val httpFetch = WorkflowDefinition(
            id = "builtin.http-fetch-process",
            displayName = "HTTP 抓取 + 处理",
            nodes = listOf(
                WorkflowNodeSpec.HttpRequest(
                    id = "fetch",
                    displayName = "抓取数据",
                    url = "https://api.github.com/repos/mengjinghao/Apex-ai-agent",
                    method = "GET"
                ),
                WorkflowNodeSpec.LlmCall(
                    id = "summarize",
                    displayName = "总结数据",
                    promptTemplate = "总结以下 JSON 数据的关键信息：{{fetch.body}}"
                )
            ),
            edges = listOf(
                com.apex.lib.workflow.WorkflowEdge(from = "fetch", to = "summarize")
            ),
            entryNodeId = "fetch"
        )
        workflows[httpFetch.id] = httpFetch

        // 内置工作流 3：条件分支
        val conditional = WorkflowDefinition(
            id = "builtin.conditional-branch",
            displayName = "条件分支示例",
            nodes = listOf(
                WorkflowNodeSpec.LlmCall(
                    id = "analyze",
                    displayName = "分析任务类型",
                    promptTemplate = "判断以下任务是代码类还是文本类：{{input}}"
                ),
                WorkflowNodeSpec.Condition(
                    id = "branch",
                    displayName = "分支判断",
                    expression = "contains(analyze.output, 'code')",
                    trueBranch = "code-handler",
                    falseBranch = "text-handler"
                ),
                WorkflowNodeSpec.LlmCall(
                    id = "code-handler",
                    displayName = "代码处理",
                    promptTemplate = "处理代码任务：{{input}}"
                ),
                WorkflowNodeSpec.LlmCall(
                    id = "text-handler",
                    displayName = "文本处理",
                    promptTemplate = "处理文本任务：{{input}}"
                )
            ),
            edges = listOf(
                com.apex.lib.workflow.WorkflowEdge(from = "analyze", to = "branch"),
                com.apex.lib.workflow.WorkflowEdge(from = "code-handler", to = ""),
                com.apex.lib.workflow.WorkflowEdge(from = "text-handler", to = "")
            ),
            entryNodeId = "analyze"
        )
        workflows[conditional.id] = conditional

        ApexLog.i(ApexSuite.ApkId.WORKFLOW, "[Facade] builtin workflows registered: ${workflows.size}")
    }
}

/** 工作流摘要。 */
data class WorkflowSummary(
    val id: String,
    val displayName: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val entryNodeId: String
)

/** 工作流执行记录。 */
data class WorkflowExecutionRecord(
    val workflowId: String,
    val traceId: String,
    val startedAt: Long,
    val completedAt: Long,
    val success: Boolean,
    val nodeCount: Int
) {
    val durationMs: Long get() = completedAt - startedAt
}
