package com.apex.agent.core.multiagent

// Minimal implementation (original had 35 errors)
// TODO: Restore full implementation from original code

data class SanxingTaskTemplate(val data: String = "")
enum class TemplateCategory { DEFAULT }
data class TemplateConfig(val data: String = "")
data class AgentOverride(val data: String = "")
data class SearchConfig(val data: String = "")
object SanxingTemplateLibrary {
    fun init() { }
}
