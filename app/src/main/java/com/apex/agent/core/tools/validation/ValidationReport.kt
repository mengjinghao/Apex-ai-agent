package com.apex.core.tools.validation

import com.apex.core.tools.PackagePermission
import com.apex.core.tools.ToolPackage
import kotlinx.serialization.Serializable

@Serializable
data class ValidationReport(
    val skillName: String,
    val skillVersion: String,
    val timestamp: Long = System.currentTimeMillis(),
    val securityReport: SecurityReport? = null,
    val performanceReport: PerformanceReport? = null,
    val compatibilityReport: CompatibilityReport? = null,
    val overallStatus: ValidationStatus = ValidationStatus.UNKNOWN,
    val summary: String = ""
)

@Serializable
data class SecurityReport(
    val isPassed: Boolean,
    val riskLevel: RiskLevel = RiskLevel.NONE,
    val dangerPatterns: List<DangerPattern> = emptyList(),
    val sensitiveApiCalls: List<SensitiveApiCall> = emptyList(),
    val networkRequests: List<NetworkRequest> = emptyList(),
    val fileOperations: List<FileOperation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
)

@Serializable
data class DangerPattern(
    val type: DangerPatternType,
    val severity: RiskLevel,
    val description: String,
    val codeSnippet: String,
    val lineNumber: Int,
    val suggestion: String
)

@Serializable
enum class DangerPatternType {
    CODE_INJECTION,
    COMMAND_INJECTION,
    REFLECTION_ABUSE,
    RUNTIME_EXEC,
    DANGEROUS_PERMISSION,
    HARDCODED_SECRET,
    INSECURE_CRYPTO,
    LOCAL_FILE_INCLUSION,
    EVAL_USAGE,
    OBFUSCATED_CODE,
    SUSPICIOUS_FUNCTION,
    DYNAMIC_CODE_LOADING,
    NATIVE_CODE_ACCESS,
    PROCESS_INFO_LEAK
}

@Serializable
data class SensitiveApiCall(
    val apiName: String,
    val severity: RiskLevel,
    val description: String,
    val lineNumber: Int,
    val context: String
)

@Serializable
data class NetworkRequest(
    val url: String,
    val isSuspicious: Boolean,
    val reason: String,
    val lineNumber: Int
)

@Serializable
data class FileOperation(
    val operation: String,
    val path: String,
    val isDangerous: Boolean,
    val reason: String,
    val lineNumber: Int
)

@Serializable
data class PerformanceReport(
    val isPassed: Boolean,
    val loadTimeMs: Long,
    val executionTimeMs: Long,
    val memoryUsageBytes: Long,
    val memoryUsagePeakBytes: Long,
    val toolCount: Int,
    val metrics: PerformanceMetrics = PerformanceMetrics(),
    val recommendations: List<String> = emptyList()
)

@Serializable
data class PerformanceMetrics(
    val avgLoadTimeMs: Long = 0,
    val avgExecutionTimeMs: Long = 0,
    val minExecutionTimeMs: Long = 0,
    val maxExecutionTimeMs: Long = 0,
    val totalToolExecutions: Int = 0,
    val memoryOverheadPerToolBytes: Long = 0
)

@Serializable
data class CompatibilityReport(
    val isPassed: Boolean,
    val androidVersionCheck: VersionCheck = VersionCheck(),
    val permissionChecks: List<PermissionCheck> = emptyList(),
    val dependencyChecks: List<DependencyCheck> = emptyList(),
    val conflictChecks: List<ConflictCheck> = emptyList(),
    val warnings: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
)

@Serializable
data class VersionCheck(
    val isCompatible: Boolean,
    val requiredVersion: String,
    val currentMinSdk: Int,
    val currentTargetSdk: Int,
    val message: String
)

@Serializable
data class PermissionCheck(
    val permission: PackagePermission,
    val isGranted: Boolean,
    val isSystemPermission: Boolean,
    val canRequest: Boolean,
    val message: String
)

@Serializable
data class DependencyCheck(
    val dependencyName: String,
    val isMet: Boolean,
    val requiredVersion: String?,
    val currentVersion: String?,
    val message: String
)

@Serializable
data class ConflictCheck(
    val conflictingSkill: String,
    val conflictType: ConflictType,
    val description: String,
    val severity: RiskLevel
)

@Serializable
enum class ConflictType {
    DUPLICATE_TOOL,
    RESOURCE_CONFLICT,
    PERMISSION_CONFLICT,
    DEPENDENCY_CONFLICT
}

@Serializable
enum class ValidationStatus {
    PASSED,
    FAILED,
    WARNING,
    UNKNOWN
}

@Serializable
enum class RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}