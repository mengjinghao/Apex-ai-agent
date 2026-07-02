package com.apex.agent.plugins.burst.base

/**
 * 技能清单 - 描述技能的基本信息和依赖
 */
data class BurstSkillManifest(
    val skillId: String,
    val skillName: String,
    val version: String,
    val description: String,
    val author: String = "",
    val tags: List<String> = emptyList(),
    val dependencies: List<SkillDependency> = emptyList(),
    val priority: Int = 0,
    val capabilities: List<String> = emptyList()
)

/**
 * 技能依赖声明
 */
data class SkillDependency(
    val skillId: String,
    val versionRange: String  // e.g., ">=1.0.0,<2.0.0"
)