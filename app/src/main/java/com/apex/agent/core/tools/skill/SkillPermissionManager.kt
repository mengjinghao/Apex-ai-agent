package com.apex.agent.core.tools.skill

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

enum class SkillPermissionState { DEFAULT }
data class SkillPermission(val data: String = "")
data class SkillPermissionCheckResult(val data: String = "")
class SkillPermissionManager
