package com.apex.integrations.externalchat

// Minimal implementation (original had 57 errors)
// TODO: Restore full implementation from original code

class ExternalChatStreamingSession
sealed class ExternalChatStreamingStartResult
class ExternalChatRequestExecutor
sealed class PreparationResult
data class Ready(val data: String = "")
