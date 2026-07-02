package com.ai.assistance.aiterminal.terminal.agent

/**
 * 系统探测完整数据模型
 */
data class SystemProbeData(
    val systemInfo: SystemInfo,
    val hardwareState: HardwareState,
    val softwareState: SoftwareState,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 格式化输出用于 LLM 上下文
     */
    fun toPromptString(): String = buildString {
        append("=== System Probe Data ===\n")
        append("Timestamp: $timestamp\n\n")
        
        append(systemInfo.toPromptString())
        append(hardwareState.toPromptString())
        append(softwareState.toPromptString())
    }
}

/**
 * 系统信息
 */
data class SystemInfo(
    val androidVersion: String? = null,
    val kernelVersion: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val rootScheme: RootScheme? = null,
    val selinuxStatus: SELinuxStatus? = null,
    val securityPatch: String? = null,
    val buildId: String? = null
) {
    fun toPromptString(): String = buildString {
        append("[System Info]\n")
        androidVersion?.let { append("Android Version: $it\n") }
        kernelVersion?.let { append("Kernel Version: $it\n") }
        model?.let { append("Model: $it\n") }
        manufacturer?.let { append("Manufacturer: $it\n") }
        rootScheme?.let { append("Root Scheme: $it\n") }
        selinuxStatus?.let { append("SELinux Status: $it\n") }
        securityPatch?.let { append("Security Patch: $it\n") }
        buildId?.let { append("Build ID: $it\n") }
        append("\n")
    }
}

/**
 * Root 方案枚举
 */
enum class RootScheme(val displayName: String) {
    MAGISK("Magisk"),
    KERNELSU("KernelSU"),
    SUPERSU("SuperSU"),
    KINGROOT("KingRoot"),
    UNKNOWN("Unknown"),
    NONE("No Root")
}

/**
 * SELinux 状态枚举
 */
enum class SELinuxStatus(val displayName: String) {
    ENFORCING("Enforcing"),
    PERMISSIVE("Permissive"),
    DISABLED("Disabled"),
    UNKNOWN("Unknown")
}

/**
 * 硬件状态
 */
data class HardwareState(
    val cpuArchitecture: String? = null,
    val cpuCores: Int? = null,
    val cpuFrequency: String? = null,
    val cpuGovernor: String? = null,
    val gpuInfo: String? = null,
    val totalMemory: Long? = null,
    val availableMemory: Long? = null,
    val usedMemory: Long? = null,
    val totalStorage: Long? = null,
    val availableStorage: Long? = null,
    val batteryLevel: Int? = null,
    val batteryHealth: String? = null,
    val wakeLocks: List<WakeLockInfo> = emptyList()
) {
    fun toPromptString(): String = buildString {
        append("[Hardware State]\n")
        cpuArchitecture?.let { append("CPU Architecture: $it\n") }
        cpuCores?.let { append("CPU Cores: $it\n") }
        cpuFrequency?.let { append("CPU Frequency: $it\n") }
        cpuGovernor?.let { append("CPU Governor: $it\n") }
        gpuInfo?.let { append("GPU Info: $it\n") }
        totalMemory?.let { append("Total Memory: ${formatBytes(it)}\n") }
        availableMemory?.let { append("Available Memory: ${formatBytes(it)}\n") }
        usedMemory?.let { append("Used Memory: ${formatBytes(it)}\n") }
        totalStorage?.let { append("Total Storage: ${formatBytes(it)}\n") }
        availableStorage?.let { append("Available Storage: ${formatBytes(it)}\n") }
        batteryLevel?.let { append("Battery Level: $it%\n") }
        batteryHealth?.let { append("Battery Health: $it\n") }
        
        if (wakeLocks.isNotEmpty()) {
            append("Active Wake Locks (${wakeLocks.size}):\n")
            wakeLocks.take(10).forEach { 
                append("  - ${it.name} (${it.type})\n")
            }
            if (wakeLocks.size > 10) {
                append("  ... and ${wakeLocks.size - 10} more\n")
            }
        }
        append("\n")
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

/**
 * 唤醒锁信息
 */
data class WakeLockInfo(
    val name: String,
    val type: String,
    val packageName: String? = null,
    val duration: Long? = null
)

/**
 * 软件状态
 */
data class SoftwareState(
    val installedApps: List<AppInfo> = emptyList(),
    val runningProcesses: List<ProcessInfo> = emptyList(),
    val mountedPartitions: List<PartitionInfo> = emptyList(),
    val systemApps: List<AppInfo> = emptyList()
) {
    fun toPromptString(): String = buildString {
        append("[Software State]\n")
        
        if (runningProcesses.isNotEmpty()) {
            append("Running Processes (${runningProcesses.size}):\n")
            runningProcesses.take(20).forEach {
                append("  - ${it.name} (PID: ${it.pid})\n")
            }
            if (runningProcesses.size > 20) {
                append("  ... and ${runningProcesses.size - 20} more\n")
            }
        }
        
        if (installedApps.isNotEmpty()) {
            append("Installed Apps (${installedApps.size} total):\n")
        }
        
        if (mountedPartitions.isNotEmpty()) {
            append("Mounted Partitions:\n")
            mountedPartitions.take(10).forEach {
                append("  - ${it.mountPoint}: ${it.fsType}\n")
            }
        }
        append("\n")
    }
}

/**
 * 应用信息
 */
data class AppInfo(
    val packageName: String,
    val appName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val isSystemApp: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * 进程信息
 */
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val user: String? = null,
    val memoryUsage: Long? = null,
    val cpuUsage: Float? = null
)

/**
 * 分区信息
 */
data class PartitionInfo(
    val mountPoint: String,
    val fsType: String,
    val device: String? = null,
    val totalSize: Long? = null,
    val availableSize: Long? = null
)

/**
 * 探测结果状态
 */
sealed class ProbeResult<out T> {
    data class Success<out T>(val data: T) : ProbeResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : ProbeResult<Nothing>()
    object Loading : ProbeResult<Nothing>()
}
