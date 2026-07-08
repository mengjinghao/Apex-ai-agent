package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * System domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class SystemSettingData(val namespace: String, val setting: String, val value: String) :
        ToolResultData() {
    override fun toString(): String {
        return "Current value of ${namespace}.${setting}: ${value}"
    }
}

/** 应用操作结果数据 */

@Serializable
data class AppOperationData(
        val operationType: String,
        val packageName: String,
        val success: Boolean,
        val details: String = ""
) : ToolResultData() {
    override fun toString(): String {
        return when (operationType) {
            "install" -> "Successfully installed app: ${packageName} ${details}"
            "uninstall" -> "Successfully uninstalled app: ${packageName} ${details}"
            "start" -> "Successfully started app: ${packageName} ${details}"
            "stop" -> "Successfully stopped app: ${packageName} ${details}"
            else -> details
        }
    }
}

/** 应用列表数据 */

@Serializable
data class AppListData(val includesSystemApps: Boolean, val packages: List<String>) :
        ToolResultData() {
    override fun toString(): String {
        val appType = if (includesSystemApps) "All Apps" else "Third-Party Apps"
        return "Installed ${appType} List:\n${packages.joinToString("\n")}"
    }
}

/** 单个应用的使用时长统�*/

@Serializable
data class AppUsageTimeEntry(
        val packageName: String,
        val appName: String,
        val totalForegroundTimeMs: Long,
        val lastTimeUsed: Long,
        val isSystemApp: Boolean
)

/** 应用使用时长数据 */

@Serializable
data class AppUsageTimeResultData(
        val startTime: Long,
        val endTime: Long,
        val sinceHours: Int,
        val requestedPackageName: String? = null,
        val includesSystemApps: Boolean,
        val totalEntries: Int,
        val entries: List<AppUsageTimeEntry>
) : ToolResultData() {
    override fun toString(): String {
        val header =
                buildString {
                    append("App usage time")
                    append(" (last ${sinceHours}h)")
                    requestedPackageName?.takeIf { it.isNotBlank() }?.let {
                        append(" for ${it}")
                    }
                }

        if (entries.isEmpty()) {
            return "${header}\nNo app usage found in the selected time window."
        }

        val lines =
                entries.joinToString("\n") { entry ->
                    "- ${entry.appName} (${entry.packageName}): ${formatDuration(entry.totalForegroundTimeMs)}"
                }

        return "${header}\n${lines}"
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0s"
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        if (seconds > 0 || parts.isEmpty()) parts.add("${seconds}s")
        return parts.joinToString(" ")
    }
}

/** Represents UI node structure for hierarchical display */

@Serializable
data class DeviceInfoResultData(
        val deviceId: String,
        val model: String,
        val manufacturer: String,
        val androidVersion: String,
        val sdkVersion: Int,
        val screenResolution: String,
        val screenDensity: Float,
        val totalMemory: String,
        val availableMemory: String,
        val totalStorage: String,
        val availableStorage: String,
        val batteryLevel: Int,
        val batteryCharging: Boolean,
        val cpuInfo: String,
        val networkType: String,
        val additionalInfo: Map<String, String> = emptyMap()
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Device Information:")
        sb.appendLine("Device Model: ${manufacturer} ${model}")
        sb.appendLine("Android Version: ${androidVersion} (SDK ${sdkVersion})")
        sb.appendLine("Device ID: ${deviceId}")
        sb.appendLine("Screen: ${screenResolution} (${screenDensity}dp)")
        sb.appendLine("Memory: Available ${availableMemory} / Total ${totalMemory}")
        sb.appendLine("Storage: Available ${availableStorage} / Total ${totalStorage}")
        sb.appendLine("Battery: ${batteryLevel}% ${if (batteryCharging) "(Charging)" else ""}")
        sb.appendLine("Network: ${networkType}")
        sb.appendLine("Processor: ${cpuInfo}")

        if (additionalInfo.isNotEmpty()) {
            sb.appendLine("\nOther Information:")
            additionalInfo.forEach { (key, value) -> sb.appendLine("${key}: ${value}") }
        }

        return sb.toString()
    }
}

/** Web page visit result data */

@Serializable
data class NotificationData(val notifications: List<Notification>, val timestamp: Long) :
        ToolResultData() {
    @Serializable
    data class Notification(val packageName: String, val text: String, val timestamp: Long)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Device Notifications (${notifications.size} total):")

        notifications.forEachIndexed { index, notification ->
            sb.appendLine("${index + 1}. Package: ${notification.packageName}")
            sb.appendLine("   Content: ${notification.text}")
            sb.appendLine()
        }

        if (notifications.isEmpty()) {
            sb.appendLine("No notifications")
        }

        return sb.toString()
    }
}

/** 位置数据结构 */

@Serializable
data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val provider: String,
        val timestamp: Long,
        val rawData: String,
        val address: String = "",
        val city: String = "",
        val province: String = "",
        val country: String = ""
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Device Location Information:")
        sb.appendLine("Longitude: ${longitude}")
        sb.appendLine("Latitude: ${latitude}")
        sb.appendLine("Accuracy: ${accuracy} meters")
        sb.appendLine("Provider: ${provider}")
        sb.appendLine(
                "Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(timestamp))}"
        )

        if (address.isNotEmpty()) {
            sb.appendLine("Address: ${address}")
        }
        if (city.isNotEmpty()) {
            sb.appendLine("City: ${city}")
        }
        if (province.isNotEmpty()) {
            sb.appendLine("Province/State: ${province}")
        }
        if (country.isNotEmpty()) {
            sb.appendLine("Country: ${country}")
        }

        return sb.toString()
    }
}

/** Represents a simplified HTML node for computer desktop actions, focusing on interactability */

