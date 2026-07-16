package com.apex.agent.core.workflow.enhanced.subworkflow

import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow
import java.util.concurrent.ConcurrentHashMap

/**
 * 子工作流调用配置
 */
data class SubWorkflowInvocation(
    val subWorkflowId: String,
    val subWorkflowVersion: Int? = null,          // null=最新
    val inputs: Map<String, Any> = emptyMap(),
    val waitForCompletion: Boolean = true,        // false=fire-and-forget
    val timeoutMs: Long = 5 * 60_000L,
    val inheritContext: Boolean = false,
    val parentThreadId: String,
    val parentNodeId: String
)

/**
 * 父子工作流链接关系
 */
enum class ParentChildLink {
    /** 父等待子完成 */
    WAIT_FOR_CHILD,
    /** 触发后即返回（异步） */
    FIRE_AND_FORGET,
    /** 仅通过信号通信 */
    SIGNAL_ONLY
}

/**
 * 子工作流执行结果
 */
sealed class SubWorkflowResult {
    data class AsyncStarted(val subThreadId: String) : SubWorkflowResult()
}

/**
 * 子工作流执行器接口
 *
 * 参照 Temporal Child Workflows
 */
interface SubWorkflowExecutor {
    /**
     * 调用子工作流
     */
    suspend fun invoke(invocation: SubWorkflowInvocation): SubWorkflowResult

    /**
     * 列出某父工作流的所有子工作流（用于 UI 树状展示）
     */
    fun children(parentThreadId: String): List<SubWorkflowExecution>

    /**
     * 取消子工作流
     */
    suspend fun cancel(subThreadId: String): Boolean
}

/**
 * 子工作流执行记录
 */
data class SubWorkflowExecution(
    val subThreadId: String,
    val subWorkflowId: String,
    val subWorkflowVersion: Int,
    val parentThreadId: String,
    val parentNodeId: String,
    val status: SubWorkflowStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val outputs: Map<String, Any> = emptyMap()
)

enum class SubWorkflowStatus {
    RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED, ASYNC_RUNNING
}

/**
 * 子工作流执行器注册表（占位实现，需要注入实际的工作流执行器）
 *
 * 实际实现需要依赖 EnhancedWorkflowExecutor，形成递归调用
 * 这里通过函数引用避免循环依赖
 */
class DelegatingSubWorkflowExecutor(
    private val executeWorkflow: suspend (EnhancedWorkflow, Map<String, Any>, String) -> Map<String, Any>
) : SubWorkflowExecutor {

    private val executions = ConcurrentHashMap<String, SubWorkflowExecution>()

    override suspend fun invoke(invocation: SubWorkflowInvocation): SubWorkflowResult {
        val subThreadId = "sub_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        val record = SubWorkflowExecution(
            subThreadId = subThreadId,
            subWorkflowId = invocation.subWorkflowId,
            subWorkflowVersion = invocation.subWorkflowVersion ?: 0,
            parentThreadId = invocation.parentThreadId,
            parentNodeId = invocation.parentNodeId,
            status = SubWorkflowStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        executions[subThreadId] = record

        if (!invocation.waitForCompletion) {
            // Fire-and-forget: 异步启动后立即返回
            return SubWorkflowResult.AsyncStarted(subThreadId)
        }

        return try {
            kotlinx.coroutines.withTimeout(invocation.timeoutMs) {
                // 实际工作流需要从注册表加载，这里简化为占位
                // 真实实现应调用 workflowRegistry.get(invocation.subWorkflowId) 加载定义
                val mockWorkflow = EnhancedWorkflow(
                    id = invocation.subWorkflowId,
                    name = "SubWorkflow_${invocation.subWorkflowId}",
                    nodes = emptyList(),
                    connections = emptyList()
                )
                val outputs = executeWorkflow(mockWorkflow, invocation.inputs, subThreadId)
                executions[subThreadId] = record.copy(
                    status = SubWorkflowStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    outputs = outputs
                )
                SubWorkflowResult.Completed(outputs)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            executions[subThreadId] = record.copy(
                status = SubWorkflowStatus.TIMED_OUT,
                completedAt = System.currentTimeMillis()
            )
            SubWorkflowResult.TimedOut(subThreadId)
        } catch (e: Throwable) {
            executions[subThreadId] = record.copy(
                status = SubWorkflowStatus.FAILED,
                completedAt = System.currentTimeMillis()
            )
            SubWorkflowResult.Failed(e)
        }
    }

    override fun children(parentThreadId: String): List<SubWorkflowExecution> {
        return executions.values.filter { it.parentThreadId == parentThreadId }
            .sortedBy { it.startedAt }
    }

    override suspend fun cancel(subThreadId: String): Boolean {
        val record = executions[subThreadId] ?: return false
        if (record.status != SubWorkflowStatus.RUNNING) return false
        executions[subThreadId] = record.copy(
            status = SubWorkflowStatus.CANCELLED,
            completedAt = System.currentTimeMillis()
        )
        return true
    }
}
