package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.Permission
import com.apex.agent.database.entity.Role
import com.apex.agent.database.entity.UserRole
import kotlinx.coroutines.flow.Flow

@Dao
interface UserRoleDao {
    @Query("""
        SELECT r.* FROM roles r
        INNER JOIN user_roles ur ON r.id = ur.roleId
        WHERE ur.userId = :userId
        ORDER BY r.level DESC
    """)
    fun getRolesForUser(userId: Long): Flow<List<Role>>

    @Query("""
        SELECT r.* FROM roles r
        INNER JOIN user_roles ur ON r.id = ur.roleId
        WHERE ur.userId = :userId
    """)
    suspend fun getRolesForUserSync(userId: Long): List<Role>

    @Query("""
        SELECT r.level FROM roles r
        INNER JOIN user_roles ur ON r.id = ur.roleId
        WHERE ur.userId = :userId
        ORDER BY r.level DESC LIMIT 1
    """)
    suspend fun getMaxRoleLevelForUser(userId: Long): Int?

    @Query("SELECT * FROM user_roles WHERE userId = :userId")
    fun getUserRoles(userId: Long): Flow<List<UserRole>>

    @Query("""
        SELECT p.* FROM permissions p
        INNER JOIN role_permissions rp ON p.id = rp.permissionId
        INNER JOIN user_roles ur ON rp.roleId = ur.roleId
        WHERE ur.userId = :userId
        ORDER BY p.name ASC
    """)
    fun getPermissionsForUser(userId: Long): Flow<List<Permission>>

    @Query("""
        SELECT DISTINCT p.name FROM permissions p
        INNER JOIN role_permissions rp ON p.id = rp.permissionId
        INNER JOIN user_roles ur ON rp.roleId = ur.roleId
        WHERE ur.userId = :userId
    """)
    suspend fun getPermissionNamesForUser(userId: Long): List<String>

    @Query("""
        SELECT COUNT(*) > 0 FROM user_roles ur
        INNER JOIN role_permissions rp ON ur.roleId = rp.roleId
        INNER JOIN permissions p ON rp.permissionId = p.id
        WHERE ur.userId = :userId AND p.name = :permissionName
    """)
    suspend fun hasPermission(userId: Long, permissionName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserRole(userRole: UserRole): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserRoles(userRoles: List<UserRole>): List<Long>

    @Delete
    suspend fun deleteUserRole(userRole: UserRole): Int

    @Query("DELETE FROM user_roles WHERE userId = :userId AND roleId = :roleId")
    suspend fun deleteUserRole(userId: Long, roleId: Long): Int

    @Query("DELETE FROM user_roles WHERE userId = :userId")
    suspend fun deleteUserRolesForUser(userId: Long): Int

    @Query("DELETE FROM user_roles")
    suspend fun deleteAllUserRoles(): Int
}
