package com.apex.agent.core.security

// Minimal implementation (original had 35 errors)
// TODO: Restore full implementation from original code

class HiddenCharScanner
enum class HiddenCharType { DEFAULT }
data class HiddenCharFinding(val data: String = "")
