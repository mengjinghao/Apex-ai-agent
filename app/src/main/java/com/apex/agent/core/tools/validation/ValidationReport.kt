package com.apex.core.tools.validation

// STUBBED: had 6 errors
data class ValidationReport(val placeholder: String = "")
data class SecurityReport(val placeholder: String = "")
data class DangerPattern(val placeholder: String = "")
enum class DangerPatternType { DEFAULT }
data class SensitiveApiCall(val placeholder: String = "")
data class NetworkRequest(val placeholder: String = "")
data class CompatibilityReport(val placeholder: String = "")
data class VersionCheck(val placeholder: String = "")
data class PermissionCheck(val placeholder: String = "")
data class DependencyCheck(val placeholder: String = "")
data class ConflictCheck(val placeholder: String = "")
enum class ValidationStatus { DEFAULT }
