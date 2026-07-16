package com.apex.agent.core.multiagent

// Minimal implementation (original had 19 errors)
// TODO: Restore full implementation from original code

enum class ModelProvider { DEFAULT }
data class ModelRequest(val data: String = "")
data class ModelResponse(val data: String = "")
class MultiModelOrchestrator
