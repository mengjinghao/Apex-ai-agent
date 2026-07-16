package com.apex.agent.core.security

// Minimal implementation (original had 104 errors)
// TODO: Restore full implementation from original code

class ExternalDataSanitizer
data class SanitizeStepResult(val data: String = "")
enum class ExternalThreatType { DEFAULT }
data class ExternalFinding(val data: String = "")
data class ExternalSanitizeResult(val data: String = "")
