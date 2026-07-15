package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkflowToolExecutor {

    companion object {
        private const val TAG = "WorkflowToolExecutor"
    }
        private val toolHandlers = mutableMapOf<String, ToolHandler>()

    interface ToolHandler {
        suspend fun execute(config: Map<String, Any>): Any
        fun validate(config: Map<String, Any>): ValidationResult
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList()
    )

    init {
        registerDefaultHandlers()
    }
        private fun registerDefaultHandlers() {
        registerHandler("log") { config ->
            val message = config["message"]?.toString() ?: "No message"
            AppLogger.d(TAG, "[Workflow Log] ${message}")
            message
        }

        registerHandler("delay") { config ->
            val duration = (config["durationMs"]?.toString()?.toLongOrNull() ?: 1000L)
            kotlinx.coroutines.delay(duration)
            "Delayed for ${duration}ms"
        }

        registerHandler("set_variable") { config ->
            val name = config["name"]?.toString() ?: "variable"
        val value = config["value"]?.toString() ?: ""
            mapOf("name" to name, "value" to value)
        }

        registerHandler("get_variable") { config ->
            val name = config["name"]?.toString() ?: "variable"
            mapOf("name" to name, "value" to "")
        }

        registerHandler("http_request") { config ->
            val url = config["url"]?.toString() ?: throw IllegalArgumentException("url is required")
        val method = config["method"]?.toString() ?: "GET"
        val body = config["body"]?.toString()

            mapOf(
                "url" to url,
                "method" to method,
                "status" to "completed",
                "responseCode" to 200
            )
        }

        registerHandler("notification") { config ->
            val title = config["title"]?.toString() ?: "Workflow Notification"
        val content = config["content"]?.toString() ?: ""

            mapOf(
                "title" to title,
                "content" to content,
                "sent" to true
            )
        }

        registerHandler("toast") { config ->
            val message = config["message"]?.toString() ?: ""
            mapOf("message" to message, "displayed" to true)
        }
    }
        fun registerHandler(toolName: String, handler: suspend (Map<String, Any>) -> Any) {
        toolHandlers[toolName] = object : ToolHandler {
            override suspend fun execute(config: Map<String, Any>): Any = handler(config)
            override fun validate(config: Map<String, Any>): ValidationResult = ValidationResult(true)
        }
        AppLogger.d(TAG, "Registered tool handler: ${toolName}")
    }
        fun registerHandler(toolName: String, handler: ToolHandler) {
        toolHandlers[toolName] = handler
        AppLogger.d(TAG, "Registered tool handler: ${toolName}")
    }
        fun unregisterHandler(toolName: String): Boolean {
        return toolHandlers.remove(toolName) != null
    }
        fun getRegisteredTools(): List<String> = toolHandlers.keys.toList()

    suspend fun execute(toolName: String, config: Map<String, Any>): Any {
        val handler = toolHandlers[toolName] ?: throw IllegalArgumentException("Unknown tool: ${toolName}")
        val validation = handler.validate(config)
        if (!validation.isValid) {
            throw IllegalArgumentException("Tool validation failed: ${validation.errors}")
        }
        return withContext(Dispatchers.Default) {
            try {
                AppLogger.d(TAG, "Executing tool: ${toolName} with config: ${config}")
        val result = handler.execute(config)
                AppLogger.d(TAG, "Tool ${toolName} executed successfully: ${result}")
                result
            } catch (e: Exception) {
                AppLogger.e(TAG, "Tool execution failed: ${toolName}", e)
        throw e
            }
        }
    }
        fun validate(toolName: String, config: Map<String, Any>): ValidationResult {
        val handler = toolHandlers[toolName] ?: return ValidationResult(false, listOf("Unknown tool: ${toolName}"))
        return handler.validate(config)
    }
}
