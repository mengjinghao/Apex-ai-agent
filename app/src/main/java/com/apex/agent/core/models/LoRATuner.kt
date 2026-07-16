package com.apex.agent.core.models

// Minimal implementation (original had 12 errors)
// TODO: Restore full implementation from original code

class LoRATuner
data class LoRAConfig(val data: String = "")
data class LoRAModel(val data: String = "")
enum class TrainingStatus { DEFAULT }
data class TrainingProgress(val data: String = "")
interface TrainingCallback
