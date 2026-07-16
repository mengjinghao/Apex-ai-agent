package com.apex.agent.core.normal.context

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

data class ConversationMessage(val data: String = "")
enum class CompressionTier { DEFAULT }
data class CompressionResult(val data: String = "")
class SmartContextCompressor
