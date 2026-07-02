package com.apex.agent.orchestration.sanxing

import com.apex.agent.core.permissions.rbac.RbacManager

object SanxingRbacBridge {

    private val permissionMapping: Map<String, String> = mapOf(
        PERM_TOOLS to "agent:tools",
        PERM_INTERNET to "agent:internet",
        PERM_READ to "agent:read",
        PERM_WRITE to "agent:write",
        PERM_CALL_AGENTS to "agent:call_agents"
    )

    fun toRbacPermission(permissionTag: String): String? = permissionMapping[permissionTag]

    fun toRbacPermissions(tags: Set<String>): Set<String> =
        tags.mapNotNull { permissionMapping[it] }.toSet()

    fun toRbacPermissions(config: SanxingRoleConfig): Set<String> =
        toRbacPermissions(config.permissionTags)

    suspend fun checkRolePermissions(
        rbacManager: RbacManager,
        userId: Long,
        role: SanxingRole
    ): Map<String, Boolean> {
        val rbacPerms = toRbacPermissions(role.permissions)
        return rbacPerms.associateWith { permName ->
            rbacManager.hasPermission(userId, permName)
        }
    }

    suspend fun hasAllPermissions(
        rbacManager: RbacManager,
        userId: Long,
        role: SanxingRole
    ): Boolean {
        val rbacPerms = toRbacPermissions(role.permissions)
        return rbacPerms.all { rbacManager.hasPermission(userId, it) }
    }

    suspend fun getMissingPermissions(
        rbacManager: RbacManager,
        userId: Long,
        role: SanxingRole
    ): List<String> {
        val rbacPerms = toRbacPermissions(role.permissions)
        return rbacPerms.filter { !rbacManager.hasPermission(userId, it) }
    }
}
