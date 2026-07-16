package com.apex.agent.core.evaluation

// Minimal implementation (original had 35 errors)
// TODO: Restore full implementation from original code

enum class ConfidenceLevel { DEFAULT }
data class QualityGateResult(val data: String = "")
object QualityGate {
    fun init() { }
}
