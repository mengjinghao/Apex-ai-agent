package com.apex.agent.core.tools.system

// Minimal implementation (original had 44 errors)
// TODO: Restore full implementation from original code

enum class PermissionMode { DEFAULT }
data class PermissionModeState(val data: String = "")
enum class RootExecutionMode { DEFAULT }
data class RootDetectionResult(val data: String = "")
enum class RootScheme { DEFAULT }
enum class SELinuxStatus { DEFAULT }
data class ShizukuDetectionResult(val data: String = "")
