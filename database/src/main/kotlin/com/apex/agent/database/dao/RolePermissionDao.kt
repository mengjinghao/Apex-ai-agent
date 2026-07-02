package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.Permission
import com.apex.agent.database.entity.RolePermission

@Dao
interface RolePermissionDao {
    @Query("""
        SELECT p.* FROM permissions p
        INNER JOIN role_permissions rp ON p.id = rp.permissionId
        WHERE rp.roleId = :roleId
        ORDER BY p.name ASC
    """)
    suspend fun getPermissionsForRole(roleId: Long): List<Permission>

    @Query("SELECT * FROM role_permissions WHERE roleId = :roleId")
    suspend fun getRolePermissions(roleId: Long): List<RolePermission>

    @Query("""
        SELECT COUNT(*) > 0 FROM role_permissions
        WHERE roleId = :roleId AND permissionId = :permissionId
    """)
    suspend fun hasPermission(roleId: Long, permissionId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRolePermission(rolePermission: RolePermission): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRolePermissions(rolePermissions: List<RolePermission>): List<Long>

    @Delete
    suspend fun deleteRolePermission(rolePermission: RolePermission): Int

    @Query("DELETE FROM role_permissions WHERE roleId = :roleId AND permissionId = :permissionId")
    suspend fun deleteRolePermission(roleId: Long, permissionId: Long): Int

    @Query("DELETE FROM role_permissions WHERE roleId = :roleId")
    suspend fun deletePermissionsForRole(roleId: Long): Int

    @Query("DELETE FROM role_permissions")
    suspend fun deleteAllRolePermissions(): Int
}
