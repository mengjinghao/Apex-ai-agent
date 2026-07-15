package com.apex.agent.core.tools.result

// STUBBED: had 43 errors
sealed class UIToolsResult
data class Success(val placeholder: String = "")
data class Error(val placeholder: String = "")
object Timeout
object ServiceNotAvailable
object PermissionDenied
enum class UIToolsErrorCode { DEFAULT }
data class OperationLogEntry(val placeholder: String = "")
class OperationLogger
