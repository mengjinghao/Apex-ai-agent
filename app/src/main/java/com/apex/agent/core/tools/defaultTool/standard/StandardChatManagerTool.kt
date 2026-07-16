package com.apex.agent.core.tools.defaultTool.standard

// Minimal implementation (original had 592 errors)
// TODO: Restore full implementation from original code

data class MessageSendStreamSession(val data: String = "")
sealed class MessageSendStreamStartResult
data class Started(val data: String = "")
class StandardChatManagerTool
