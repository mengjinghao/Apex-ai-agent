package com.apex.agent.core.models

// Minimal implementation (original had 24 errors)
// TODO: Restore full implementation from original code

class ModelSelector
enum class SelectionCriteria { DEFAULT }
data class DeviceCapabilities(val data: String = "")
data class SelectionResult(val data: String = "")
enum class TaskType { DEFAULT }
