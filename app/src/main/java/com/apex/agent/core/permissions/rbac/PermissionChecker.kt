package com.apex.agent.core.permissions.rbac

import android.content.Context
import com.apex.agent.core.tools.system.PermissionMode
import com.apex.agent.core.tools.system.PermissionModeManager

class PermissionChecker(private val context: Context) {

    private val rbacManager: RbacManager by lazy { RbacManager.getInstance(context) }
    private val permissionModeManager: PermissionModeManager by lazy { PermissionModeManager.getInstance(context) }

    suspend fun check(userId: Long, permissionName: String): PermissionResult =
        rbacManager.checkPermission(userId, permissionName)

    suspend fun require(userId: Long, permissionName: String) =
        rbacManager.requirePermission(userId, permissionName)

    suspend fun hasAll(userId: Long, vararg permissionNames: String): Boolean {
        for (name in permissionNames) {
            if (!rbacManager.hasPermission(userId, name)) return false
        }
        return true
    }

    suspend fun hasAny(userId: Long, vararg permissionNames: String): Boolean {
        for (name in permissionNames) {
            if (rbacManager.hasPermission(userId, name)) return true
        }
        return false
    }

    suspend fun requireAll(userId: Long, vararg permissionNames: String) {
        for (name in permissionNames) {
            rbacManager.requirePermission(userId, name)
        }
    }

    suspend fun checkWithMode(
        userId: Long,
        permissionName: String,
        requiredMode: PermissionMode
    ): PermissionResult {
        val rbacResult = rbacManager.checkPermission(userId, permissionName)
        if (rbacResult is PermissionResult.Denied) return rbacResult

        val modeState = permissionModeManager.getModeState(requiredMode)
        return if (modeState != null && modeState.isUsable) {
            PermissionResult.Granted
        } else {
            PermissionResult.Denied("需�?${requiredMode.displayName} 权限模式")
        }
    }

    suspend fun highestRoleLevel(userId: Long): Int =
        rbacManager.getMaxRoleLevelForUser(userId)

    suspend fun isAdmin(userId: Long): Boolean =
        rbacManager.hasPermission(userId, "system:admin")

    suspend fun isSuperAdmin(userId: Long): Boolean =
        rbacManager.getMaxRoleLevelForUser(userId) >= 5
}
