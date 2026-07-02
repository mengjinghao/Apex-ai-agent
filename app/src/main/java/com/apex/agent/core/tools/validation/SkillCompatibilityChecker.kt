package com.apex.core.tools.validation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.apex.core.tools.PackagePermission
import com.apex.core.tools.ToolPackage
import com.apex.util.AppLogger

class SkillCompatibilityChecker(private val context: Context) {

    companion object {
        private const val TAG = "SkillCompatibility"

        private val DANGEROUS_PERMISSIONS = setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.USE_SIP,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES,
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WRITE_SETTINGS",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        )

        private val SYSTEM_PERMISSIONS = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.VIBRATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.NFC
        )

        private val MIN_SDK_FOR_FEATURES = mapOf(
            "android.permission.USE_BIOMETRIC" to 28,
            "android.permission.USE_FINGERPRINT" to 23,
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to 23,
            "android.permission.ACTIVITY_RECOGNITION" to 29,
            "android.permission.ACCESS_BACKGROUND_LOCATION" to 29,
            "android.permission.READ_MEDIA_IMAGES" to 33,
            "android.permission.READ_MEDIA_VIDEO" to 33,
            "android.permission.READ_MEDIA_AUDIO" to 33,
            "android.permission.POST_NOTIFICATIONS" to 33,
            "android.permission.NEARBY_WIFI_DEVICES" to 33
        )

        private val CONFLICTING_SKILLS = mapOf(
            "android_assistant" to setOf("android_tools", "device_manager"),
            "browser" to setOf("web_scraper", "http_client"),
            "file_tools" to setOf("extended_file_tools", "file_manager")
        )
    }

    fun check(toolPackage: ToolPackage, otherSkills: List<ToolPackage> = emptyList()): CompatibilityReport {
        AppLogger.d(TAG, "Starting compatibility check for skill: ${toolPackage.name}")

        val androidVersionCheck = checkAndroidVersion(toolPackage)
        val permissionChecks = checkPermissions(toolPackage.permissions)
        val dependencyChecks = checkDependencies(toolPackage.dependencies, otherSkills)
        val conflictChecks = checkConflicts(toolPackage, otherSkills)

        val warnings = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        if (!androidVersionCheck.isCompatible) {
            warnings.add(androidVersionCheck.message)
            recommendations.add("Update the minimum Android version requirement or remove incompatible features.")
        }

        val ungrantedDangerousPermissions = permissionChecks.filter {
            it.permission.required && !it.isGranted && DANGEROUS_PERMISSIONS.contains(it.permission.name)
        }
        if (ungrantedDangerousPermissions.isNotEmpty()) {
            warnings.add("This skill requires ${ungrantedDangerousPermissions.size} dangerous permissions that are not granted.")
            recommendations.add("Review the dangerous permissions requested and ensure user consent is obtained before using features that require them.")
        }

        val unmetDependencies = dependencyChecks.filter { !it.isMet }
        if (unmetDependencies.isNotEmpty()) {
            warnings.add("This skill has ${unmetDependencies.size} unmet dependencies.")
            recommendations.add("Install the required dependent skills: ${unmetDependencies.map { it.dependencyName }.joinToString(", ")}")
        }

        if (conflictChecks.isNotEmpty()) {
            val highSeverityConflicts = conflictChecks.filter { it.severity == RiskLevel.HIGH || it.severity == RiskLevel.CRITICAL }
            if (highSeverityConflicts.isNotEmpty()) {
                warnings.add("This skill has ${highSeverityConflicts.size} high-severity conflicts with other installed skills.")
                recommendations.add("Consider disabling conflicting skills to avoid issues.")
            }
        }

        val isPassed = androidVersionCheck.isCompatible &&
                ungrantedDangerousPermissions.isEmpty() &&
                unmetDependencies.isEmpty() &&
                conflictChecks.none { it.severity == RiskLevel.CRITICAL }

        AppLogger.d(TAG, "Compatibility check completed for ${toolPackage.name}: isPassed=${isPassed}")

        return CompatibilityReport(
            isPassed = isPassed,
            androidVersionCheck = androidVersionCheck,
            permissionChecks = permissionChecks,
            dependencyChecks = dependencyChecks,
            conflictChecks = conflictChecks,
            warnings = warnings,
            recommendations = recommendations
        )
    }

    fun checkAndroidVersion(toolPackage: ToolPackage): VersionCheck {
        val requiredVersion = toolPackage.version
        val minSdk = extractMinSdkFromVersion(requiredVersion)
        val currentMinSdk = Build.VERSION.SDK_INT
        val currentTargetSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.packageManager.getApplicationInfo(context.packageName, 0).targetSdkVersion
        } else {
            currentMinSdk
        }

        val isCompatible = currentMinSdk >= minSdk

        val message = when {
            currentMinSdk < minSdk -> "Current Android version (SDK ${currentMinSdk}) is below the required minimum (SDK ${minSdk})"
            currentTargetSdk < minSdk -> "Target SDK (${currentTargetSdk}) is below required minimum (SDK ${minSdk})"
            else -> "Android version compatibility check passed"
        }

        return VersionCheck(
            isCompatible = isCompatible,
            requiredVersion = requiredVersion,
            currentMinSdk = currentMinSdk,
            currentTargetSdk = currentTargetSdk,
            message = message
        )
    }

    fun checkPermissions(permissions: List<PackagePermission>): List<PermissionCheck> {
        return permissions.map { permission ->
            checkPermission(permission)
        }
    }

    private fun checkPermission(permission: PackagePermission): PermissionCheck {
        val isSystemPermission = SYSTEM_PERMISSIONS.contains(permission.name)
        val isDangerousPermission = DANGEROUS_PERMISSIONS.contains(permission.name)

        val isGranted = if (isDangerousPermission) {
            context.checkSelfPermission(permission.name) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val canRequest = isDangerousPermission && !isGranted

        val minSdkForPermission = MIN_SDK_FOR_FEATURES[permission.name]
        val meetsMinSdk = minSdkForPermission?.let { Build.VERSION.SDK_INT >= it } ?: true

        val message = buildString {
            when {
                isSystemPermission -> append("System permission - automatically granted")
                isDangerousPermission && !isGranted -> append("Dangerous permission - requires runtime request")
                isDangerousPermission && isGranted -> append("Dangerous permission - already granted")
                else -> append("Standard permission")
            }
            if (minSdkForPermission != null && !meetsMinSdk) {
                append(" (requires SDK ${minSdkForPermission}, current: ${Build.VERSION.SDK_INT})")
            }
        }

        return PermissionCheck(
            permission = permission,
            isGranted = isGranted,
            isSystemPermission = isSystemPermission,
            canRequest = canRequest,
            message = message
        )
    }

    fun checkDependencies(
        dependencies: List<String>,
        availableSkills: List<ToolPackage>
    ): List<DependencyCheck> {
        val availableSkillNames = availableSkills.map { it.name }.toSet()
        val availableSkillVersions = availableSkills.associate { it.name to it.version }

        return dependencies.map { dependency ->
            val isMet = availableSkillNames.contains(dependency)
            val currentVersion = availableSkillVersions[dependency]

            DependencyCheck(
                dependencyName = dependency,
                isMet = isMet,
                requiredVersion = null,
                currentVersion = currentVersion,
                message = when {
                    isMet -> "Dependency '${dependency}' is satisfied (version: ${currentVersion})"
                    else -> "Dependency '${dependency}' is not installed"
                }
            )
        }
    }

    fun checkConflicts(
        skill: ToolPackage,
        otherSkills: List<ToolPackage>
    ): List<ConflictCheck> {
        val conflicts = mutableListOf<ConflictCheck>()

        val knownConflicts = CONFLICTING_SKILLS[skill.name] ?: emptySet()
        otherSkills.forEach { otherSkill ->
            if (skill.name != otherSkill.name) {
                if (knownConflicts.contains(otherSkill.name)) {
                    conflicts.add(
                        ConflictCheck(
                            conflictingSkill = otherSkill.name,
                            conflictType = ConflictType.DUPLICATE_TOOL,
                            description = "Known conflict between ${skill}.name and ${otherSkill.name}",
                            severity = RiskLevel.MEDIUM
                        )
                    )
                }

                val skillToolNames = skill.tools.map { it.name }.toSet()
                val otherToolNames = otherSkill.tools.map { it.name }.toSet()
                val duplicateTools = skillToolNames.intersect(otherToolNames)

                if (duplicateTools.isNotEmpty()) {
                    conflicts.add(
                        ConflictCheck(
                            conflictingSkill = otherSkill.name,
                            conflictType = ConflictType.DUPLICATE_TOOL,
                            description = "Duplicate tools found: ${duplicateTools.joinToString(", ")}",
                            severity = RiskLevel.HIGH
                        )
                    )
                }
            }
        }

        return conflicts
    }

    fun getSystemAvailablePermissions(): Set<String> {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions?.toSet() ?: emptySet()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting system permissions", e)
            emptySet()
        }
    }

    fun isPermissionAvailable(permission: String): Boolean {
        return try {
            context.packageManager.getPermissionInfo(permission, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking permission availability", e)
            false
        }
    }

    private fun extractMinSdkFromVersion(version: String): Int {
        return try {
            val parts = version.split(".")
            if (parts.size >= 3) {
                parts[2].toIntOrNull() ?: 21
            } else {
                21
            }
        } catch (e: Exception) {
            21
        }
    }

    fun getCompatibilitySummary(report: CompatibilityReport): String {
        return buildString {
            appendLine("=== Compatibility Summary ===")
            appendLine()
            appendLine("Android Version: ${report.androidVersionCheck.message}")
            appendLine()

            val grantedCount = report.permissionChecks.count { it.isGranted }
            val totalCount = report.permissionChecks.size
            appendLine("Permissions: ${grantedCount}/${totalCount} granted")
            report.permissionChecks.filter { !it.isGranted && it.permission.required }.forEach {
                appendLine("  - Missing: ${it.permission.name}")
            }
            appendLine()

            val metDeps = report.dependencyChecks.count { it.isMet }
            val totalDeps = report.dependencyChecks.size
            appendLine("Dependencies: ${metDeps}/${totalDeps} satisfied")
            report.dependencyChecks.filter { !it.isMet }.forEach {
                appendLine("  - Missing: ${it.dependencyName}")
            }
            appendLine()

            if (report.conflictChecks.isNotEmpty()) {
                appendLine("Conflicts: ${report.conflictChecks.size} found")
                report.conflictChecks.forEach {
                    appendLine("  - ${it.conflictingSkill}: ${it.description}")
                }
            } else {
                appendLine("Conflicts: None")
            }
            appendLine()

            appendLine("Status: ${if (report.isPassed) "PASSED" else "FAILED"}")
        }
    }
}