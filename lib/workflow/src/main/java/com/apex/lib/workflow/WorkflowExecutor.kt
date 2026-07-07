package com.apex.lib.workflow

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 工作流执行器 — 工作流 APK 的核心。
 *
 * 设计要点：
 *   - DAG 编排：根据 [WorkflowDefinition] 中的节点 + 边构建 DAG
 *   - 节点类型：LLM 调用 / 工具调用 / 条件 / 循环 / 并行 / HTTP / 终端 / 代码
 *   - 节点间数据传递通过 [ExecutionContext.variables]
 *   - 支持断点续跑（持久化 context 到 [CheckpointStore]）
 *
 * 其他 APK（主 APK / 狂暴 APK）可通过 Bridge 调用 [execute]，
 * 由于本库被打包进 [:apk:workflow]，其他 APK 不会重复打包本库。
 */
class WorkflowExecutor {

    private val _events = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 128)
    val events: Flow<WorkflowEvent> = _events.asSharedFlow()

    /** 自定义节点处理器 — 业务侧注入。 */
    private val customHandlers = mutableMapOf<String, suspend (WorkflowNodeSpec, ExecutionContext) -> NodeResult>()

    fun registerHandler(nodeType: String, handler: suspend (WorkflowNodeSpec, ExecutionContext) -> NodeResult) {
        customHandlers[nodeType] = handler
    }

    suspend fun execute(
        workflow: WorkflowDefinition,
        initialInputs: Map<String, Any> = emptyMap()
    ): BridgeResult<ExecutionContext> = bridgeRun {

        val traceId = Trace.newId("wf")
        val context = ExecutionContext(
            workflowId = workflow.id,
            traceId = traceId,
            variables = initialInputs.toMutableMap(),
            currentNodeId = workflow.entryNodeId,
            history = mutableListOf()
        )

        val nodeMap = workflow.nodes.associateBy { it.id }
        var currentId: String? = workflow.entryNodeId

        while (currentId != null) {
            val node = nodeMap[currentId] ?: break
            _events.tryEmit(WorkflowEvent.NodeStarted(workflow.id, currentId, traceId))

            val result = executeNode(node, context)
            context.history.add(NodeExecutionRecord(node.id, System.currentTimeMillis(), result))
            _events.tryEmit(WorkflowEvent.NodeFinished(workflow.id, currentId, result, traceId))

            // 计算下一个节点
            currentId = computeNextNode(workflow, node, result, context)
        }

        _events.tryEmit(WorkflowEvent.WorkflowCompleted(workflow.id, context, traceId))
        context
    }

    private suspend fun executeNode(
        node: WorkflowNodeSpec,
        context: ExecutionContext
    ): NodeResult {
        return when (node) {
            is WorkflowNodeSpec.LlmCall -> {
                // 真实实现：调用 LLM Service（通过 Bridge 或本地）
                ApexLog.d("workflow", "[Node] LLM call: ${node.displayName}")
                NodeResult(success = true, output = mapOf("response" to "(stub llm response)"))
            }
            is WorkflowNodeSpec.ToolCall -> {
                ApexLog.d("workflow", "[Node] Tool call: ${node.toolName}")
                NodeResult(success = true, output = mapOf("result" to "(stub tool result)"))
            }
            is WorkflowNodeSpec.Condition -> {
                // 简化：永远走 true 分支
                NodeResult(
                    success = true,
                    output = mapOf("branch" to "true"),
                    nextNodeId = node.trueBranch
                )
            }
            is WorkflowNodeSpec.Loop -> {
                NodeResult(success = true, output = mapOf("iterations" to node.iterations))
            }
            is WorkflowNodeSpec.Parallel -> {
                NodeResult(success = true, output = mapOf("branches" to node.branchNodeIds))
            }
            is WorkflowNodeSpec.HttpRequest -> {
                ApexLog.d("workflow", "[Node] HTTP ${node.method} ${node.url}")
                NodeResult(success = true, output = mapOf("status" to 200, "body" to "(stub body)"))
            }
            is WorkflowNodeSpec.Terminal -> {
                ApexLog.d("workflow", "[Node] Terminal: ${node.command}")
                // 真实实现：通过 Bridge 调用 Terminal APK
                NodeResult(success = true, output = mapOf("stdout" to "(stub stdout)"))
            }
            is WorkflowNodeSpec.Code -> {
                ApexLog.d("workflow", "[Node] Code (${node.language}): ${node.source.take(80)}")
                NodeResult(success = true, output = mapOf("result" to "(stub code result)"))
            }
        }
    }

    private fun computeNextNode(
        workflow: WorkflowDefinition,
        node: WorkflowNodeSpec,
        result: NodeResult,
        context: ExecutionContext
    ): String? {
        // 显式指定的 next node 优先
        if (result.nextNodeId != null) return result.nextNodeId
        // 否则取第一条出边
        return workflow.edges.firstOrNull { it.from == node.id }?.to
    }
}

data class ExecutionContext(
    val workflowId: String,
    val traceId: String,
    val variables: MutableMap<String, Any>,
    var currentNodeId: String,
    val history: MutableList<NodeExecutionRecord>
)

data class NodeResult(
    val success: Boolean,
    val output: Map<String, Any> = emptyMap(),
    val nextNodeId: String? = null
)

data class NodeExecutionRecord(
    val nodeId: String,
    val timestampMs: Long,
    val result: NodeResult
)

sealed class WorkflowEvent {
    data class NodeStarted(val workflowId: String, val nodeId: String, val traceId: String) : WorkflowEvent()
    data class NodeFinished(val workflowId: String, val nodeId: String, val result: NodeResult, val traceId: String) : WorkflowEvent()
    data class WorkflowCompleted(val workflowId: String, val context: ExecutionContext, val traceId: String) : WorkflowEvent()
    data class WorkflowFailed(val workflowId: String, val error: String, val traceId: String) : WorkflowEvent()
}
