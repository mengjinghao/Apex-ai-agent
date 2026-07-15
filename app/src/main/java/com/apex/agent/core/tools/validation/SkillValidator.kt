package com.apex.core.tools.validation

import android.content.Context
import com.apex.core.tools.ToolPackage
import com.apex.util.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SkillValidator(context: Context) {

    companion object {
        private const val TAG = "SkillValidator"

        @Volatile
        private var INSTANCE: SkillValidator? = null

        fun getInstance(context: Context): SkillValidator {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: SkillValidator(context.applicationContext).also { INSTANCE = it }
                }
        }
    }
        private val securityScanner = SkillSecurityScanner(context)
        private val benchmark = SkillBenchmark(context)
        private val compatibilityChecker = SkillCompatibilityChecker(context)
        private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
        fun validateComplete(toolPackage: ToolPackage, otherSkills: List<ToolPackage> = emptyList()): ValidationReport {
        AppLogger.d(TAG, "Starting complete validation for skill: ${toolPackage.name}")
        val securityReport = securityScanner.scan(toolPackage)
        val performanceReport = benchmark.benchmark(toolPackage)
        val compatibilityReport = compatibilityChecker.check(toolPackage, otherSkills)
        val overallStatus = when {
            !securityReport.isPassed -> ValidationStatus.FAILED
            !compatibilityReport.isPassed -> ValidationStatus.FAILED
            !performanceReport.isPassed -> ValidationStatus.WARNING
            securityReport.riskLevel == RiskLevel.MEDIUM -> ValidationStatus.WARNING
            else -> ValidationStatus.PASSED
        }
        val summary = buildSummary(securityReport, performanceReport, compatibilityReport)

        AppLogger.d(TAG, "Validation completed for ${toolPackage.name}: status=${overallStatus}")
        return ValidationReport(
            skillName = toolPackage.name,
            skillVersion = toolPackage.version,
            securityReport = securityReport,
            performanceReport = performanceReport,
            compatibilityReport = compatibilityReport,
            overallStatus = overallStatus,
            summary = summary
        )
    }
        fun validateScript(
        scriptContent: String,
        skillName: String = "unknown",
        skillVersion: String = "1.0.0"
    ): ValidationReport {
        AppLogger.d(TAG, "Starting script validation for: ${skillName}")
        val securityReport = securityScanner.scanScript(scriptContent, skillName)
        val performanceReport = benchmark.benchmarkScript(scriptContent, skillName)
        val overallStatus = when {
            !securityReport.isPassed -> ValidationStatus.FAILED
            securityReport.riskLevel == RiskLevel.CRITICAL -> ValidationStatus.FAILED
            securityReport.riskLevel == RiskLevel.HIGH -> ValidationStatus.WARNING
            !performanceReport.isPassed -> ValidationStatus.WARNING
            else -> ValidationStatus.PASSED
        }
        val summary = buildScriptSummary(securityReport, performanceReport)
        return ValidationReport(
            skillName = skillName,
            skillVersion = skillVersion,
            securityReport = securityReport,
            performanceReport = performanceReport,
            overallStatus = overallStatus,
            summary = summary
        )
    }
        fun validateSecurity(toolPackage: ToolPackage): SecurityReport {
        return securityScanner.scan(toolPackage)
    }
        fun validatePerformance(toolPackage: ToolPackage): PerformanceReport {
        return benchmark.benchmark(toolPackage)
    }
        fun validateCompatibility(
        toolPackage: ToolPackage,
        otherSkills: List<ToolPackage> = emptyList()
    ): CompatibilityReport {
        return compatibilityChecker.check(toolPackage, otherSkills)
    }
        fun generateReportJson(report: ValidationReport): String {
        return try {
            json.encodeToString(report)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating JSON report", e)
            "{}"
        }
    }
        fun generateMarkdownReport(report: ValidationReport): String {
        return buildMarkdownReport(report)
    }
        private fun buildSummary(
        securityReport: SecurityReport,
        performanceReport: PerformanceReport,
        compatibilityReport: CompatibilityReport
    ): String {
        return buildString {
            append("Security: ${if (securityReport.isPassed) "PASS" else "FAIL"} (${securityReport.riskLevel.name}), ")
            append("Performance: ${if (performanceReport.isPassed) "PASS" else "WARN"} (load: ${performanceReport.loadTimeMs}ms, exec: ${performanceReport.executionTimeMs}ms), ")
            append("Compatibility: ${if (compatibilityReport.isPassed) "PASS" else "FAIL"}")
        }
    }
        private fun buildScriptSummary(
        securityReport: SecurityReport,
        performanceReport: PerformanceReport
    ): String {
        return buildString {
            append("Security: ${if (securityReport.isPassed) "PASS" else "FAIL"} (${securityReport.riskLevel.name}), ")
            append("Performance: ${if (performanceReport.isPassed) "PASS" else "WARN"} (load: ${performanceReport.loadTimeMs}ms, exec: ${performanceReport.executionTimeMs}ms)")
        }
    }
        private fun buildMarkdownReport(report: ValidationReport): String {
        return buildString {
            appendLine("# Skill Validation Report")
            appendLine()
            appendLine("**Skill Name:** ${report.skillName}")
            appendLine("**Version:** ${report.skillVersion}")
            appendLine("**Timestamp:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(report.timestamp))}")
            appendLine("**Overall Status:** ${report.overallStatus.name}")
            appendLine()
            appendLine("---")
            appendLine()

            report.securityReport?.let { security ->
                appendLine("## Security Scan")
                appendLine()
                appendLine("**Status:** ${if (security.isPassed) "PASSED" else "FAILED"}")
                appendLine("**Risk Level:** ${security.riskLevel.name}")
                appendLine()
        if (security.dangerPatterns.isNotEmpty()) {
                    appendLine("### Danger Patterns Detected (${security.dangerPatterns.size})")
                    security.dangerPatterns.forEach { pattern ->
                        appendLine()
                        appendLine("#### ${pattern.type.name}")
                        appendLine("- **Severity:** ${pattern.severity.name}")
                        appendLine("- **Description:** ${pattern.description}")
                        appendLine("- **Line:** ${pattern.lineNumber}")
                        appendLine("- **Code:** `${pattern.codeSnippet.take(50)}...`")
                        appendLine("- **Suggestion:** ${pattern.suggestion}")
                    }
                    appendLine()
                }
        if (security.sensitiveApiCalls.isNotEmpty()) {
                    appendLine("### Sensitive API Calls (${security.sensitiveApiCalls.size})")
                    security.sensitiveApiCalls.forEach { api ->
                        appendLine("- `${api.apiName}` (Line ${api.lineNumber}): ${api.description}")
                    }
                    appendLine()
                }
        if (security.networkRequests.isNotEmpty()) {
                    appendLine("### Network Requests (${security.networkRequests.size})")
                    security.networkRequests.forEach { req ->
                        appendLine("- ${req.url} (Line ${req.lineNumber})${if (req.isSuspicious) " **[SUSPICIOUS]**" else ""}")
                    }
                    appendLine()
                }
        if (security.recommendations.isNotEmpty()) {
                    appendLine("### Recommendations")
                    security.recommendations.forEach { rec ->
                        appendLine("- ${rec}")
                    }
                    appendLine()
                }
            }

            report.performanceReport?.let { perf ->
                appendLine("## Performance Benchmark")
                appendLine()
                appendLine("**Status:** ${if (perf.isPassed) "PASSED" else "WARNING"}")
                appendLine()
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| Load Time | ${perf.loadTimeMs} ms |")
                appendLine("| Execution Time | ${perf.executionTimeMs} ms |")
                appendLine("| Memory Usage | ${formatBytes(perf.memoryUsageBytes)} |")
                appendLine("| Peak Memory | ${formatBytes(perf.memoryUsagePeakBytes)} |")
                appendLine("| Tool Count | ${perf.toolCount} |")
                appendLine()
                appendLine("### Metrics")
                appendLine()
                appendLine("| Metric | Avg | Min | Max |")
                appendLine("|--------|-----|-----|-----|")
                appendLine("| Load Time (ms) | ${perf.metrics.avgLoadTimeMs} | - | - |")
                appendLine("| Execution Time (ms) | ${perf.metrics.avgExecutionTimeMs} | ${perf.metrics.minExecutionTimeMs} | ${perf.metrics.maxExecutionTimeMs} |")
                appendLine()
        if (perf.recommendations.isNotEmpty()) {
                    appendLine("### Recommendations")
                    perf.recommendations.forEach { rec ->
                        appendLine("- ${rec}")
                    }
                    appendLine()
                }
            }

            report.compatibilityReport?.let { compat ->
                appendLine("## Compatibility Check")
                appendLine()
                appendLine("**Status:** ${if (compat.isPassed) "PASSED" else "FAILED"}")
                appendLine()
                appendLine("### Android Version")
                appendLine("- **Required:** ${compat.androidVersionCheck.requiredVersion}")
                appendLine("- **Current Min SDK:** ${compat.androidVersionCheck.currentMinSdk}")
                appendLine("- **Current Target SDK:** ${compat.androidVersionCheck.currentTargetSdk}")
                appendLine("- **Message:** ${compat.androidVersionCheck.message}")
                appendLine()
        if (compat.permissionChecks.isNotEmpty()) {
                    appendLine("### Permissions (${compat.permissionChecks.count { it.isGranted }}/${compat.permissionChecks.size} granted)")
                    compat.permissionChecks.forEach { check ->
                        val status = if (check.isGranted) "�? else "�?
                        appendLine("- ${status} ${check.permission.name}: ${check.message}")
                    }
                    appendLine()
                }
        if (compat.dependencyChecks.isNotEmpty()) {
                    appendLine("### Dependencies (${compat.dependencyChecks.count { it.isMet }}/${compat.dependencyChecks.size} met)")
                    compat.dependencyChecks.forEach { check ->
                        val status = if (check.isMet) "�? else "�?
                        appendLine("- ${status} ${check.dependencyName}${check.currentVersion?.let { " (${it})" } ?: ""}: ${check.message}")
                    }
                    appendLine()
                }
        if (compat.conflictChecks.isNotEmpty()) {
                    appendLine("### Conflicts (${compat.conflictChecks.size})")
                    compat.conflictChecks.forEach { conflict ->
                        appendLine("- **[${conflict.severity.name}]** ${conflict.conflictingSkill}: ${conflict.description}")
                    }
                    appendLine()
                }
        if (compat.recommendations.isNotEmpty()) {
                    appendLine("### Recommendations")
                    compat.recommendations.forEach { rec ->
                        appendLine("- ${rec}")
                    }
                    appendLine()
                }
            }
        }
    }
        private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}