package com.apex.selfmodify.plan

import com.apex.selfmodify.workspace.FileChange
import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
data class ModificationPlan(
    val id: String,
    val changes: List<FileChange>,
    val reason: String,
    val riskLevel: RiskLevel,
    val requiresUserConfirm: Boolean,
    val agentId: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ApplyResult {
    data class Success(val plan: ModificationPlan, val compileMs: Long) : ApplyResult()
    data class RolledBack(val plan: ModificationPlan, val reason: String) : ApplyResult()
    data class Rejected(val reason: String) : ApplyResult()
}
