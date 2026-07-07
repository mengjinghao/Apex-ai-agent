package com.apex.agent.core.workflow.enhanced.hitl

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 人在回路（Human-in-the-Loop）审批组件
 *
 * 参照 LangGraph 的 interrupt() + Command(resume=...) 机制
 *
 * 工作流程：
 * 1. 工作流执行到 HUMAN_INPUT 节点
 * 2. 节点调用 approvalGateway.awaitApproval(payload)
 * 3. 当前协程挂起（CompletableDeferred），状态置为 WAITING_HUMAN
 * 4. UI/通知展示审批请求
 * 5. 用户点击"批准/拒绝"，调用 approvalGateway.resume(interruptId, command)
 * 6. 挂起的协程恢复，工作流继续
 */

/**
 * 中断负载 - 审批请求的完整信息
 */
data class InterruptPayload(
    val interruptId: String = UUID.randomUUID().toString(),
    val nodeId: String,
    val threadId: String,               // 工作流执行线程
    val workflowId: String,
    val workflowName: String,
    val prompt: String,                 // 给用户看的问题/说明
    val options: List<String> = listOf("approve", "reject"),
    val details: Map<String, Any> = emptyMap(),
    val timeoutMs: Long = 24 * 60 * 60_000L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 恢复命令 - 外部用户对中断的响应
 */
sealed class ResumeCommand {
    data class Approve(val reason: String? = null, val extra: Map<String, Any> = emptyMap()) : ResumeCommand()
    data class Reject(val reason: String? = null) : ResumeCommand()
    data class Provide(val value: Any, val resumeAll: Boolean = false) : ResumeCommand()
    data class Timeout(val interruptId: String) : ResumeCommand()
}

/**
 * 审批网关接口
 *
 * 业务侧（UI 层）实现此接口，桥接工作流引擎与用户交互
 */
interface ApprovalGateway {
    /**
     * 节点调用：挂起协程直到外部响应
     * @throws HumanRejectedByUserException 用户拒绝
     * @throws HumanApprovalTimeoutException 超时
     */
    suspend fun awaitApproval(payload: InterruptPayload): ResumeCommand.Approve

    /** 外部调用：唤醒挂起的协程 */
    fun resume(interruptId: String, command: ResumeCommand): Boolean

    /** 列出所有 pending 审批 */
    fun pending(threadId: String? = null): List<InterruptPayload>

    /** 取消某个中断 */
    fun cancel(interruptId: String, reason: String = "cancelled"): Boolean
}

/**
 * 默认内存审批网关实现
 *
 * 使用 CompletableDeferred 实现协程挂起/恢复
 */
class InMemoryApprovalGateway : ApprovalGateway {

    private val pendingInterrupts = ConcurrentHashMap<String, PendingInterrupt>()
    private val listeners = mutableListOf<(InterruptPayload) -> Unit>()

    /**
     * 注册监听器（UI 层用于接收新审批请求）
     */
    fun addListener(listener: (InterruptPayload) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (InterruptPayload) -> Unit) {
        listeners.remove(listener)
    }

    override suspend fun awaitApproval(payload: InterruptPayload): ResumeCommand.Approve {
        val deferred = CompletableDeferred<ResumeCommand>()
        pendingInterrupts[payload.interruptId] = PendingInterrupt(payload, deferred)

        // 通知监听器（UI 层）
        listeners.forEach { it(payload) }

        // 等待响应（带超时）
        val command = try {
            kotlinx.coroutines.withTimeout(payload.timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingInterrupts.remove(payload.interruptId)
            throw HumanApprovalTimeoutException(payload.interruptId, payload.timeoutMs)
        }

        pendingInterrupts.remove(payload.interruptId)

        return when (command) {
            is ResumeCommand.Approve -> command
            is ResumeCommand.Reject -> throw HumanRejectedByUserException(payload.interruptId, command.reason)
            is ResumeCommand.Provide -> throw IllegalArgumentException(
                "期望 Approve，收到 Provide。如需用户提供值，应使用其他节点类型。"
            )
            is ResumeCommand.Timeout -> throw HumanApprovalTimeoutException(payload.interruptId, 0)
        }
    }

    override fun resume(interruptId: String, command: ResumeCommand): Boolean {
        val pending = pendingInterrupts[interruptId] ?: return false
        return pending.deferred.complete(command)
    }

    override fun pending(threadId: String?): List<InterruptPayload> {
        return pendingInterrupts.values
            .map { it.payload }
            .filter { threadId == null || it.threadId == threadId }
            .sortedBy { it.createdAt }
    }

    override fun cancel(interruptId: String, reason: String): Boolean {
        val pending = pendingInterrupts.remove(interruptId) ?: return false
        return pending.deferred.complete(
            ResumeCommand.Reject(reason = "cancelled: $reason")
        )
    }

    private data class PendingInterrupt(
        val payload: InterruptPayload,
        val deferred: CompletableDeferred<ResumeCommand>
    )
}

/**
 * 人工审批相关异常
 */
class HumanRejectedByUserException(
    val interruptId: String,
    val rejectReason: String?
) : RuntimeException("人工审批被拒绝: ${rejectReason ?: "无理由"}")

class HumanApprovalTimeoutException(
    val interruptId: String,
    val timeoutMs: Long
) : RuntimeException("人工审批超时 (interruptId=$interruptId, timeoutMs=$timeoutMs)")

/**
 * 审批网关持有者 - 全局单例
 */
object ApprovalGatewayHolder {
    @Volatile
    private var instance: ApprovalGateway = InMemoryApprovalGateway()

    fun get(): ApprovalGateway = instance

    fun set(gateway: ApprovalGateway) {
        instance = gateway
    }
}
