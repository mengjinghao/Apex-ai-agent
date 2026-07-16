package com.apex.agent.core

// Minimal implementation (original had 25 errors)
// TODO: Restore full implementation from original code

class CoreModuleInitializer
interface ModuleInitializable
object HermesIntegration {
    fun init() { }
}
data class AgentExtension(val data: String = "")
class AgentStorage
data class AutoSaveStrategy(val data: String = "")
class MCPBridge
data class MCPBridgeTool(val data: String = "")
class ToolManager
data class ToolRegistration(val data: String = "")
data class ToolPermission(val data: String = "")
data class ToolQuota(val data: String = "")
enum class TaskPriority { DEFAULT }
class WorkflowSubAgent
interface ChatHistoryPort
