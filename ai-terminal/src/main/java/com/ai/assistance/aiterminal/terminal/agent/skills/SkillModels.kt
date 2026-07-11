package com.ai.assistance.aiterminal.terminal.agent.skills

interface AgentSkill

class SkillResult

data class Success(val placeholder: String = "")

data class Error(val placeholder: String = "")

data class PartialSuccess(val placeholder: String = "")

data class TuningComparison(val placeholder: String = "")

data class TuningMetrics(val placeholder: String = "")

class TuningPreset

object ExtremeBattery

object Balanced

object GamingPerformance

object Performance

data class TuningConfig(val placeholder: String = "")

data class PartitionInfo(val placeholder: String = "")

data class BackupInfo(val placeholder: String = "")

data class MagiskModuleInfo(val placeholder: String = "")

data class SystemAppInfo(val placeholder: String = "")

data class FlashTroubleshootingResult(val placeholder: String = "")

enum class IssueType { DEFAULT }

enum class ConfigurationPreference { DEFAULT }

data class EssentialModuleInfo(val placeholder: String = "")

data class OptimizationItem(val placeholder: String = "")

data class EnvironmentDetectionResult(val placeholder: String = "")

data class SetupReport(val placeholder: String = "")

data class SecurityConfig(val placeholder: String = "")
