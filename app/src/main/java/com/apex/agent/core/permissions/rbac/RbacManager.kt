package com.apex.agent.core.permissions.rbac

import android.content.Context
import com.apex.agent.database.AppDatabase
import com.apex.agent.database.DatabaseRepository
import com.apex.agent.database.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class RbacManager private constructor(private val context: Context) {

    companion object {
        private const val CACHE_TTL = 30_000L

        @Volatile
        private var instance: RbacManager? = null

        fun getInstance(context: Context): RbacManager =
            instance ?: synchronized(this) {
                instance ?: RbacManager(context.applicationContext).also { it.initialize() }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permissionCache = ConcurrentHashMap<String, CacheEntry>()

    private lateinit var repo: DatabaseRepository

    private data class CacheEntry(
        val result: Boolean,
        val timestamp: Long
    )

    private fun initialize() {
        val db = AppDatabase.getDatabase(context)
        repo = DatabaseRepository(
            userDao = db.userDao(),
            taskDao = db.taskDao(),
            userWithTasksDao = db.userWithTasksDao(),
            permissionDao = db.permissionDao(),
            roleDao = db.roleDao(),
            userRoleDao = db.userRoleDao(),
            rolePermissionDao = db.rolePermissionDao()
        )
        scope.launch { ensureDefaultData() }
    }

    private suspend fun ensureDefaultData() {
        if (repo.roleCount() > 0) return
        val now = System.currentTimeMillis()
        val superAdminRole = repo.insertRole(Role(name = "super_admin", description = "超级管理�?- 全部权限", level = 5, isSystem = true))
        val adminRole = repo.insertRole(Role(name = "admin", description = "管理�?- 高级权限", level = 4, isSystem = true))
        val userRole = repo.insertRole(Role(name = "user", description = "普通用�?- 标准权限", level = 1, isSystem = true))
        val guestRole = repo.insertRole(Role(name = "guest", description = "访客 - 只读权限", level = 0, isSystem = true))

        val permIds = mutableMapOf<String, Long>()
        for (perm in defaultPermissions()) {
            permIds[perm.name] = repo.insertPermission(perm)
        }

        fun add(roleId: Long, vararg permNames: String) {
            for (name in permNames) {
                permIds[name]?.let { pid ->
                    repo.assignPermissionToRole(roleId, pid)
                }
            }
        }

        add(superAdminRole, *permIds.keys.toTypedArray())

        add(adminRole,
            "agent:tools", "agent:internet", "agent:read", "agent:write", "agent:call_agents",
            "api:tasks:create", "api:tasks:read", "api:tasks:update", "api:tasks:cancel",
            "api:tasks:pause", "api:tasks:resume",
            "api:stats:read", "api:files:upload",
            "file:read", "file:write", "file:delete",
            "system:settings", "system:audit"
        )

        add(userRole,
            "agent:tools", "agent:internet", "agent:read", "agent:write", "agent:call_agents",
            "api:tasks:create", "api:tasks:read", "api:tasks:cancel",
            "api:stats:read", "api:files:upload",
            "file:read", "file:write"
        )

        add(guestRole,
            "agent:read",
            "api:tasks:read",
            "api:stats:read"
        )
    }

    private fun defaultPermissions(): List<Permission> {
        val now = System.currentTimeMillis()
        return listOf(
            Permission(name = "agent:tools", description = "允许使用工具", category = "agent", createdAt = now),
            Permission(name = "agent:internet", description = "允许访问网络", category = "agent", createdAt = now),
            Permission(name = "agent:read", description = "允许读取文件", category = "agent", createdAt = now),
            Permission(name = "agent:write", description = "允许写入文件", category = "agent", createdAt = now),
            Permission(name = "agent:call_agents", description = "允许调用其他Agent", category = "agent", createdAt = now),
            Permission(name = "api:tasks:create", description = "创建任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:read", description = "读取任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:update", description = "更新任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:delete", description = "删除任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:cancel", description = "取消任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:pause", description = "暂停任务", category = "api", createdAt = now),
            Permission(name = "api:tasks:resume", description = "恢复任务", category = "api", createdAt = now),
            Permission(name = "api:stats:read", description = "读取统计信息", category = "api", createdAt = now),
            Permission(name = "api:files:upload", description = "上传文件", category = "api", createdAt = now),
            Permission(name = "users:manage", description = "管理用户", category = "system", createdAt = now),
            Permission(name = "roles:manage", description = "管理角色", category = "system", createdAt = now),
            Permission(name = "permissions:manage", description = "管理权限", category = "system", createdAt = now),
            Permission(name = "system:admin", description = "系统管理员权�?, category = "system", createdAt = now),
            Permission(name = "system:settings", description = "修改系统设置", category = "system", createdAt = now),
            Permission(name = "system:audit", description = "查看审计日志", category = "system", createdAt = now),
            Permission(name = "file:read", description = "读取文件系统中的文件", category = "file", createdAt = now),
            Permission(name = "file:write", description = "写入文件系统中的文件", category = "file", createdAt = now),
            Permission(name = "file:delete", description = "删除文件系统中的文件", category = "file", createdAt = now)
        )
    }

    fun getRepository(): DatabaseRepository = repo

    suspend fun hasPermission(userId: Long, permissionName: String): Boolean {
        val cacheKey = "$userId:$permissionName"
        val now = System.currentTimeMillis()
        permissionCache[cacheKey]?.let {
            if (now - it.timestamp < CACHE_TTL) return it.result
        }
        val result = repo.hasPermission(userId, permissionName)
        permissionCache[cacheKey] = CacheEntry(result, now)
        return result
    }

    suspend fun checkPermission(userId: Long, permissionName: String): PermissionResult {
        val has = hasPermission(userId, permissionName)
        return if (has) PermissionResult.Granted
        else PermissionResult.Denied("用户缺少 '${permissionName.replaceBeforeLast(':', "")}' 权限")
    }

    suspend fun requirePermission(userId: Long, permissionName: String) {
        val result = checkPermission(userId, permissionName)
        if (result is PermissionResult.Denied) {
            throw PermissionDeniedException(result.reason)
        }
    }

    suspend fun assignRoleToUser(userId: Long, roleName: String, grantedBy: String? = null) {
        val role = repo.getRoleByName(roleName) ?: return
        repo.assignRole(userId, role.id, grantedBy)
        invalidateCacheForUser(userId)
    }

    suspend fun assignRoleToUserById(userId: Long, roleId: Long, grantedBy: String? = null) {
        repo.assignRole(userId, roleId, grantedBy)
        invalidateCacheForUser(userId)
    }

    suspend fun revokeRoleFromUser(userId: Long, roleName: String) {
        val role = repo.getRoleByName(roleName) ?: return
        repo.revokeRole(userId, role.id)
        invalidateCacheForUser(userId)
    }

    suspend fun getPermissionNamesForUser(userId: Long): List<String> =
        repo.getPermissionNamesForUser(userId)

    suspend fun getMaxRoleLevelForUser(userId: Long): Int =
        repo.getMaxRoleLevelForUser(userId) ?: 0

    fun getPermissionsForUser(userId: Long): Flow<List<Permission>> =
        repo.getPermissionsForUser(userId)

    fun getRolesForUser(userId: Long): Flow<List<Role>> =
        repo.getRolesForUser(userId)

    suspend fun getRolesForUserSync(userId: Long): List<Role> =
        repo.getRolesForUserSync(userId)

    fun invalidateCacheForUser(userId: Long) {
        permissionCache.keys.removeAll { it.startsWith("$userId:") }
    }

    fun invalidateAllCache() {
        permissionCache.clear()
    }
}

sealed class PermissionResult {
    data object Granted : PermissionResult()
    data class Denied(val reason: String) : PermissionResult()
}

class PermissionDeniedException(reason: String) : SecurityException(reason)
