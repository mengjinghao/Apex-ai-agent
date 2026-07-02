package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.Permission
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionDao {
    @Query("SELECT * FROM permissions ORDER BY name ASC")
    fun getAllPermissions(): Flow<List<Permission>>

    @Query("SELECT * FROM permissions WHERE id = :id")
    fun getPermissionById(id: Long): Flow<Permission?>

    @Query("SELECT * FROM permissions WHERE name = :name")
    suspend fun getPermissionByName(name: String): Permission?

    @Query("SELECT * FROM permissions WHERE category = :category ORDER BY name ASC")
    fun getPermissionsByCategory(category: String): Flow<List<Permission>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermission(permission: Permission): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPermissions(permissions: List<Permission>): List<Long>

    @Update
    suspend fun updatePermission(permission: Permission): Int

    @Delete
    suspend fun deletePermission(permission: Permission): Int

    @Query("DELETE FROM permissions WHERE id = :id")
    suspend fun deletePermissionById(id: Long): Int

    @Query("SELECT COUNT(*) FROM permissions")
    suspend fun count(): Int

    @Query("DELETE FROM permissions")
    suspend fun deleteAllPermissions(): Int
}
