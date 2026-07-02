
package com.apex.agent.core.multiagent

data class AgentRoleImageMapping(
    val role: String,
    val displayName: String,
    val description: String,
    val iconRes: Int? = null,
    val colorHex: String = "#4A90D9"
) {
    companion object {
        fun getDefaultForRole(role: String): AgentRoleImageMapping {
            return AgentRoleImageMapping(
                role = role,
                displayName = role.lowercase().replaceFirstChar { it.uppercase() },
                description = "Agent specialized in $role tasks"
            )
        }
    }
}
