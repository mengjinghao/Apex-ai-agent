package com.apex.agent.core.workflow.enhanced.handlers

import com.apex.agent.core.workflow.enhanced.EnhancedWorkflowExecutor

/**
 * 内置动作处理器集合 - 开箱即用的常用处理器
 *
 * 业务侧可直接注册到 EnhancedWorkflowExecutor
 */

/**
 * 日志打印处理器 - 将消息写入上下文变量与日志
 */
class LogActionHandler : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val message = params["message"] ?: ""
        android.util.Log.i("WorkflowLog", "[${context.threadId}] $message")
        context.variables["__log_${System.currentTimeMillis()}"] = message
        return EnhancedWorkflowExecutor.ActionResult.Success(message)
    }
}

/**
 * 变量设置处理器 - 修改变量
 */
class SetVariableActionHandler : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val key = params["key"] ?: return EnhancedWorkflowExecutor.ActionResult.Failure("缺少 key 参数")
        val value = params["value"] ?: ""
        context.variables[key] = value
        return EnhancedWorkflowExecutor.ActionResult.Success(value)
    }
}

/**
 * HTTP 请求处理器（占位 - 需要业务侧注入 OkHttp 实例）
 */
class HttpRequestActionHandler(
    private val client: Any? = null  // 实际应为 OkHttpClient
) : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val url = params["url"] ?: return EnhancedWorkflowExecutor.ActionResult.Failure("缺少 url 参数")
        val method = params["method"] ?: "GET"
        return EnhancedWorkflowExecutor.ActionResult.Success(
            mapOf("url" to url, "method" to method, "status" to "ok")
        )
    }
}

/**
 * 字符串拼接处理器
 */
class ConcatActionHandler : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val parts = params.entries
            .filter { it.key.startsWith("part") }
            .sortedBy { it.key.removePrefix("part").toIntOrNull() ?: 0 }
            .map { it.value }
        val separator = params["separator"] ?: ""
        val result = parts.joinToString(separator)
        return EnhancedWorkflowExecutor.ActionResult.Success(result)
    }
}

/**
 * 条件求值处理器
 */
class EvaluateConditionHandler : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val expr = params["expression"] ?: return EnhancedWorkflowExecutor.ActionResult.Failure("缺少 expression")
        val result = when {
            expr.contains("==") -> {
                val (l, r) = expr.split("==", limit = 2).map { it.trim() }
                l == r
            }
            expr.contains("!=") -> {
                val (l, r) = expr.split("!=", limit = 2).map { it.trim() }
                l != r
            }
            else -> false
        }
        return EnhancedWorkflowExecutor.ActionResult.Success(result.toString())
    }
}

/**
 * 通知发送处理器（占位 - 实际应调用 Android 通知系统）
 */
class NotifyActionHandler : EnhancedWorkflowExecutor.ActionHandler {
    override suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ): EnhancedWorkflowExecutor.ActionResult {
        val title = params["title"] ?: "工作流通知"
        val text = params["text"] ?: ""
        android.util.Log.i("WorkflowNotify", "[$title] $text")
        return EnhancedWorkflowExecutor.ActionResult.Success(mapOf("title" to title, "text" to text))
    }
}

/**
 * 默认补偿处理器 - 记录补偿日志
 */
class DefaultCompensateHandler : EnhancedWorkflowExecutor.CompensateHandler {
    override suspend fun compensate(
        actionType: String,
        params: Map<String, String>,
        result: Any?,
        context: EnhancedWorkflowExecutor.ExecutionContext
    ) {
        android.util.Log.w(
            "WorkflowCompensate",
            "[${context.threadId}] 补偿 $actionType: params=$params, result=$result"
        )
    }
}

/**
 * 预置处理器注册表
 */
object BuiltinHandlers {
    fun registerAll(builder: EnhancedWorkflowExecutor.Builder): EnhancedWorkflowExecutor.Builder {
        return builder
            .withActionHandler("log", LogActionHandler())
            .withActionHandler("set_variable", SetVariableActionHandler())
            .withActionHandler("http_request", HttpRequestActionHandler())
            .withActionHandler("concat", ConcatActionHandler())
            .withActionHandler("evaluate_condition", EvaluateConditionHandler())
            .withActionHandler("notify", NotifyActionHandler())
            .withCompensateHandler("log", DefaultCompensateHandler())
            .withCompensateHandler("set_variable", DefaultCompensateHandler())
            .withCompensateHandler("http_request", DefaultCompensateHandler())
            .withCompensateHandler("notify", DefaultCompensateHandler())
    }
}
