package com.ai.assistance.aiterminal.terminal.agent

data class SystemProbeData(val placeholder: String = "")

data class SystemInfo(val placeholder: String = "")

enum class RootScheme { DEFAULT }

enum class SELinuxStatus { DEFAULT }

data class HardwareState(val placeholder: String = "")

data class WakeLockInfo(val placeholder: String = "")

data class SoftwareState(val placeholder: String = "")

data class AppInfo(val placeholder: String = "")

data class ProcessInfo(val placeholder: String = "")

data class PartitionInfo(val placeholder: String = "")

class ProbeResult

data class Success(val placeholder: String = "")

data class Error(val placeholder: String = "")

object Loading
