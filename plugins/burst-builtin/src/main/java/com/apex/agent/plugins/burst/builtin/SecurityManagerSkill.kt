package com.apex.agent.plugins.burst.builtin

class SecurityManagerSkill

data class SecurityCheckResult(val placeholder: String = "")

data class SecurityIssue(val placeholder: String = "")

data class SensitiveInfoCheckResult(val placeholder: String = "")

enum class Severity { DEFAULT }

class PolicyEngine

data class PolicyCheckResult(val placeholder: String = "")

class ResourceController

data class ResourceCheckResult(val placeholder: String = "")
