package com.apex.agent.core.normal

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

class NormalAgentOrchestrator
data class InputProcessResult(val data: String = "")
data class OutputProcessResult(val data: String = "")
data class ToolCallProcessResult(val data: String = "")
sealed class NormalAction
data class IntentDetected(val data: String = "")
data class DepthResolved(val data: String = "")
data class ClarificationNeeded(val data: String = "")
data class SensitiveDetected(val data: String = "")
data class EmotionDetected(val data: String = "")
data class MemeDetected(val data: String = "")
data class EasterEggTriggered(val data: String = "")
data class EnhancedInputProcessResult(val data: String = "")
data class EnhancedOutputProcessResult(val data: String = "")
data class V3InputProcessResult(val data: String = "")
data class V3OutputProcessResult(val data: String = "")
