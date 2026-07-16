package com.apex.agent.core.normal.toolpreview

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * F7: 工具调用预估与确认
 *
 * 模型发出工具调用后，先暂停执行，展示"将调用 X 工具，参数 Y，
 * 预估耗时 Z 秒，需要 N 权限"，用户确认后才执行。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 自主调用
 * - 狂暴直接跳过权限
 * - 本功能强化 NORMAL 的"渐进式权限"，让用户有控制感
 */

/**
 * 工具调用预览信息
 */
data class ToolPreview(
    val toolCallId: String,
    val toolName: String,
    val displayName: String,
    val description: String,
    val arguments: Map<String, Any?>,
    val estimatedDurationMs: Long,
    val requiredPermission: PermissionLevel,
    val sideEffects: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val reversible: Boolean = true,
    val dataAccess: List<String> = emptyList()
)


enum class RiskLevel {
    /** 只读，无副作用 */
    LOW,
    /** 修改本地数据 */
    MEDIUM,
    /** 发送消息/网络请求 */
    HIGH,
    /** 删除/不可逆操作 */
    CRITICAL
}

/**
 * 确认结果
 */
sealed class ConfirmationResult {
    data class Approved(val scope: ApprovalScope = ApprovalScope.ONCE) : ConfirmationResult()
    data class Rejected(val reason: String = "") : ConfirmationResult()
    data object TimedOut : ConfirmationResult()
}

enum class ApprovalScope {
    /** 本次允许 */
    ONCE,
    /** 本会话允许 */
    SESSION,
    /** 始终允许 */
    ALWAYS
}

/**
 * 工具调用预览生成器
 */
class ToolPreviewGenerator {

    private val toolMetadata = ConcurrentHashMap<String, ToolMetadata>()

    data class ToolMetadata(
        val name: String,
        val displayName: String,
        val description: String,
        val defaultPermission: PermissionLevel = PermissionLevel.STANDARD,
        val defaultRiskLevel: RiskLevel = RiskLevel.LOW,
        val defaultDurationMs: Long = 5_000L,
        val sideEffects: List<String> = emptyList(),
        val reversible: Boolean = true,
        val dataAccess: List<String> = emptyList()
    )

    /**
     * 注册工具元数据
     */
    fun registerTool(meta: ToolMetadata) {
        toolMetadata[meta.name] = meta
    }

    /**
     * 生成预览
     */
    fun generate(toolCallId: String, toolName: String, arguments: Map<String, Any?>): ToolPreview {
        val meta = toolMetadata[toolName] ?: ToolMetadata(
            name = toolName,
            displayName = toolName,
            description = "未注册的工具",
            defaultRiskLevel = inferRiskLevel(toolName, arguments)
        )
        return ToolPreview(
            toolCallId = toolCallId,
            toolName = toolName,
            displayName = meta.displayName,
            description = meta.description,
            arguments = arguments,
            estimatedDurationMs = estimateDuration(meta, arguments),
            requiredPermission = meta.defaultPermission,
            sideEffects = meta.sideEffects,
            riskLevel = assessRisk(meta, arguments),
            reversible = meta.reversible,
            dataAccess = meta.dataAccess
        )
    }

    /**
     * 生成用户可读的预览文本
     */
    fun formatPreview(preview: ToolPreview): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 工具调用预览 ═══")
        sb.appendLine("工具: ${preview.displayName}")
        sb.appendLine("说明: ${preview.description}")
        sb.appendLine("参数:")
        preview.arguments.forEach { (k, v) ->
            val displayValue = if (k.lowercase().containsAny("password", "secret", "token", "key")) {
                "***"
            } else v?.toString() ?: "null"
            sb.appendLine("  $k = $displayValue")
        }
        sb.appendLine("预估耗时: ${formatDuration(preview.estimatedDurationMs)}")
        sb.appendLine("权限要求: ${preview.requiredPermission}")
        sb.appendLine("风险等级: ${preview.riskLevel}")
        if (preview.sideEffects.isNotEmpty()) {
            sb.appendLine("副作用: ${preview.sideEffects.joinToString()}")
        }
        sb.appendLine("可撤销: ${if (preview.reversible) "是" else "否"}")
        if (preview.dataAccess.isNotEmpty()) {
            sb.appendLine("数据访问: ${preview.dataAccess.joinToString()}")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun estimateDuration(meta: ToolMetadata, args: Map<String, Any?>): Long {
        var duration = meta.defaultDurationMs
        // 网络请求类工具耗时更长
        if (meta.name.containsAny("http", "fetch", "request", "upload", "download")) {
            duration = (duration * 2).coerceAtLeast(10_000L)
        }
        // 大文件操作耗时更长
        args["size"]?.toString()?.toLongOrNull()?.let { size ->
            if (size > 1_000_000) duration += size / 1000  // 1ms per KB
        }
        return duration
    }

    private fun assessRisk(meta: ToolMetadata, args: Map<String, Any?>): RiskLevel {
        var risk = meta.defaultRiskLevel
        // 删除类操作提升风险
        if (meta.name.containsAny("delete", "remove", "drop", "rm", "rmdir")) {
            risk = RiskLevel.CRITICAL
        }
        // 发送消息类提升风险
        if (meta.name.containsAny("send", "post", "publish", "broadcast")) {
            risk = maxOf(risk.ordinal, RiskLevel.HIGH.ordinal).let { RiskLevel.values()[it] }
        }
        // 网络请求
        if (meta.name.containsAny("http", "fetch", "request") && risk == RiskLevel.LOW) {
            risk = RiskLevel.MEDIUM
        }
        return risk
    }

    private fun inferRiskLevel(toolName: String, args: Map<String, Any?>): RiskLevel {
        return when {
            toolName.containsAny("delete", "remove", "drop", "rm") -> RiskLevel.CRITICAL
            toolName.containsAny("send", "post", "publish") -> RiskLevel.HIGH
            toolName.containsAny("http", "fetch", "request", "write", "create") -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000.0}s"
        else -> "${ms / 60_000}min ${(ms % 60_000) / 1000}s"
    }
}

/**
 * 工具调用确认网关
 *
 * 管理待确认的工具调用，使用 CompletableDeferred 挂起执行
 */
class ToolConfirmationGateway(
    private val autoApproveLowRisk: Boolean = false,
    private val timeoutMs: Long = 60_000L
) {

    private val pendingConfirmations = ConcurrentHashMap<String, PendingConfirmation>()

    data class PendingConfirmation(
        val preview: ToolPreview,
        val deferred: CompletableDeferred<ConfirmationResult>,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 请求确认（挂起协程直到用户响应）
     */
    suspend fun requestConfirmation(preview: ToolPreview): ConfirmationResult {
        // 低风险自动批准
        if (autoApproveLowRisk && preview.riskLevel == RiskLevel.LOW) {
            return ConfirmationResult.Approved(ApprovalScope.SESSION)
        }

        val deferred = CompletableDeferred<ConfirmationResult>()
        pendingConfirmations[preview.toolCallId] = PendingConfirmation(preview, deferred)

        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingConfirmations.remove(preview.toolCallId)
            ConfirmationResult.TimedOut
        }
    }

    /**
     * 用户响应
     */
    fun respond(toolCallId: String, result: ConfirmationResult): Boolean {
        val pending = pendingConfirmations.remove(toolCallId) ?: return false
        return pending.deferred.complete(result)
    }

    /**
     * 列出待确认
     */
    fun listPending(): List<ToolPreview> =
        pendingConfirmations.values.map { it.preview }.sortedBy { it.riskLevel.ordinal }

    /**
     * 取消
     */
    fun cancel(toolCallId: String, reason: String = "cancelled"): Boolean {
        val pending = pendingConfirmations.remove(toolCallId) ?: return false
        return pending.deferred.complete(ConfirmationResult.Rejected(reason))
    }
}
