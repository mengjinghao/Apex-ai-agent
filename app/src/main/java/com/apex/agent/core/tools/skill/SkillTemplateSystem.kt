package com.apex.agent.core.tools.skill

// Minimal implementation (original had 796 errors)
// TODO: Restore full implementation from original code

class SkillTemplateSystem
data class SkillTemplate(val data: String = "")
data class TemplateVariable(val data: String = "")
enum class VariableType { DEFAULT }
enum class Difficulty { DEFAULT }
data class TemplateWorkflow(val data: String = "")
data class TemplateNode(val data: String = "")
data class TemplateConnection(val data: String = "")
data class TemplateFile(val data: String = "")
data class SkillCreationResult(val data: String = "")
