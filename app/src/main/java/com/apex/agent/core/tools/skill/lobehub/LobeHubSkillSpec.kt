package com.apex.agent.core.tools.skill.lobehub

import kotlinx.serialization.Serializable

/**
 * LobeHub Skill specification data model
 * Compatible with LobeHub Skills Marketplace format
 * 
 * LobeHub Skills use SKILL.md format with YAML frontmatter
 * Reference: https://lobehub.com/skills/skill.md
 */
@Serializable
data class LobeHubSkillSpec(
    val identifier: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val homepage: String = "",
    val license: String = "MIT",
    val tags: List<String> = emptyList(),
    val agent: List<String> = emptyList(),  // Supported agents: open-claw, claude-code, codex, cursor
    val capabilities: LobeHubCapabilities = LobeHubCapabilities(),
    val install: LobeHubInstallConfig = LobeHubInstallConfig(),
    val inputs: List<LobeHubInput> = emptyList()
)

@Serializable
data class LobeHubCapabilities(
    val skills: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    val extends: List<String> = emptyList()
)

@Serializable
data class LobeHubInstallConfig(
    val method: String = "npx",  // npx, npm, git, direct
    val command: String = "",
    val agent: String = "",       // Target agent for installation
    val requireAuth: Boolean = false,
    val apiKeys: List<String> = emptyList()
)

@Serializable
data class LobeHubInput(
    val name: String,
    val description: String = "",
    val type: String = "string",
    val required: Boolean = false,
    val default: String = "",
    val options: List<String> = emptyList()
)

/**
 * LobeHub Marketplace listing info
 */
@Serializable
data class LobeHubSkillListing(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val rating: Float,
    val installCount: Int,
    val tags: List<String>,
    val agent: List<String>,
    val updatedAt: String,
    val skillMdUrl: String,
    val homepage: String
)

/**
 * LobeHub API response models
 */
@Serializable
data class LobeHubApiResponse(
    val success: Boolean,
    val data: List<LobeHubSkillListing> = emptyList(),
    val error: String? = null
)

@Serializable
data class LobeHubSkillDetail(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val rating: Float,
    val installCount: Int,
    val tags: List<String>,
    val agent: List<String>,
    val updatedAt: String,
    val readme: String,
    val skillMdContent: String,
    val homepage: String
)

/**
 * Search filters for LobeHub marketplace
 */
data class LobeHubSearchFilters(
    val query: String = "",
    val agent: String? = null,      // open-claw, claude-code, codex, cursor
    val category: String? = null,
    val sort: LobeHubSortOption = LobeHubSortOption.POPULAR,
    val page: Int = 1,
    val pageSize: Int = 20
)

enum class LobeHubSortOption {
    POPULAR,
    NEWEST,
    RATING,
    INSTALLS
}
