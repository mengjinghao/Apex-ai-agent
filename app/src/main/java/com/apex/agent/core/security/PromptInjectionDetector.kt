package com.apex.agent.core.security

// Minimal implementation (original had 82 errors)
// TODO: Restore full implementation from original code

class PromptInjectionDetector
enum class InjectionPatternType { DEFAULT }
data class InjectionPattern(val data: String = "")
data class InjectionFinding(val data: String = "")
