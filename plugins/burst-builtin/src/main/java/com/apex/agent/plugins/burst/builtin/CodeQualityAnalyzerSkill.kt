package com.apex.agent.plugins.burst.builtin

class CodeQualityAnalyzerSkill

data class CodeQualityIssue(val placeholder: String = "")

enum class Severity { DEFAULT }

enum class IssueType { DEFAULT }

data class CodeQualityAnalysis(val placeholder: String = "")

data class FileAnalysis(val placeholder: String = "")

data class ComplexityResult(val placeholder: String = "")

data class CodeStyleRule(val placeholder: String = "")
