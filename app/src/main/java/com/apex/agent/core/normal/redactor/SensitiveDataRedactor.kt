package com.apex.agent.core.normal.redactor

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

enum class SensitiveType { DEFAULT }
data class SensitivePattern(val data: String = "")
enum class MaskStrategy { DEFAULT }
data class RedactedText(val data: String = "")
class SensitiveDataRedactor
data class DetectionResult(val data: String = "")
