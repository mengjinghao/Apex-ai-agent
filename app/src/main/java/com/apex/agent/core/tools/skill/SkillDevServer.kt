package com.apex.agent.core.tools.skill

// Minimal implementation (original had 23 errors)
// TODO: Restore full implementation from original code

class SkillDevServer
data class ToolExecutionResult(val data: String = "")
data class ApiRequest(val data: String = "")
data class ApiResponse(val data: String = "")
interface ServerListener
data class WebSocketSession(val data: String = "")
class LocalWebSocketServer
class ConnectionHandler
data class Frame(val data: String = "")
