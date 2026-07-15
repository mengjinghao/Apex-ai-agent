package com.apex.agent.core.tools.result

// STUBBED: had 43 errors
sealed class UIToolsResult
object Timeout
object ServiceNotAvailable
object PermissionDenied
enum class UIToolsErrorCode { DEFAULT }
data class OperationLogEntry(val placeholder: String = "")
class OperationLogger
