package com.apex.agent.orchestration.sanxing

import com.apex.agent.orchestration.agent.model.AgentPermissions

/**
 * 三星制角色的配置数据类�? */
data class SanxingRoleConfig(
    val roleId: String,
    val roleName: String,
    val title: String,
    val description: String,
    val colorHex: String,
    val defaultModel: String,
    val provider: String = "openai",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val temperature: Double = 0.5,
    val topP: Double = 0.95,
    val maxTokens: Int = 4096,
    val permissionTags: Set<String> = DEFAULT_PERMISSIONS,
    val systemPrompt: String = ""
)

const val PERM_TOOLS = "tools"
const val PERM_INTERNET = "internet"
const val PERM_READ = "read"
const val PERM_WRITE = "write"
const val PERM_CALL_AGENTS = "call_agents"

val DEFAULT_PERMISSIONS: Set<String> = setOf(
    PERM_TOOLS,
    PERM_INTERNET,
    PERM_READ,
    PERM_WRITE,
    PERM_CALL_AGENTS
)

fun Set<String>.toAgentPermissions(): AgentPermissions = AgentPermissions(
    canUseTools = PERM_TOOLS in this,
    canAccessInternet = PERM_INTERNET in this,
    canReadFiles = PERM_READ in this,
    canWriteFiles = PERM_WRITE in this,
    canCallOtherAgents = PERM_CALL_AGENTS in this
)

fun SanxingRoleConfig.toAgentPermissions(): AgentPermissions = permissionTags.toAgentPermissions()
