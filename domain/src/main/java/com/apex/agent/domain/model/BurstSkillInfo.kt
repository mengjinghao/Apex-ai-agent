package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BurstSkillInfo(
    val skillId: String,
    val skillName: String,
    val version: String,
    val description: String,
    val priority: Int = 0,
    val isEnabled: Boolean = true
)
