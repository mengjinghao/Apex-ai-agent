package com.apex.agent.core.tools.result

// Minimal implementation (original had 43 errors)
// TODO: Restore full implementation from original code

sealed class UIToolsResult
    fun init() { }
}
object ServiceNotAvailable {
    fun init() { }
}
object PermissionDenied {
    fun init() { }
}
enum class UIToolsErrorCode { DEFAULT }
data class OperationLogEntry(val data: String = "")
class OperationLogger
