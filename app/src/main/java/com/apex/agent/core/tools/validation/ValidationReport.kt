package com.apex.core.tools.validation

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

data class ValidationReport(val data: String = "")
data class SecurityReport(val data: String = "")
data class DangerPattern(val data: String = "")
enum class DangerPatternType { DEFAULT }
data class SensitiveApiCall(val data: String = "")
data class NetworkRequest(val data: String = "")
data class FileOperation(val data: String = "")
data class CompatibilityReport(val data: String = "")
data class VersionCheck(val data: String = "")
data class PermissionCheck(val data: String = "")
data class DependencyCheck(val data: String = "")
data class ConflictCheck(val data: String = "")
enum class ValidationStatus { DEFAULT }
