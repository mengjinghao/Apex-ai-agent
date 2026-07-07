package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.Role
import kotlinx.coroutines.flow.Flow

@Dao
interface RoleDao {
    @Query("SELECT * FROM roles ORDER BY level ASC")
    fun getAllRoles(): Flow<List<Role>>

    @Query("SELECT * FROM roles WHERE id = :id")
    fun getRoleById(id: Long): Flow<Role?>

    @Query("SELECT * FROM roles WHERE name = :name")
    suspend fun getRoleByName(name: String): Role?

    @Query("SELECT * FROM roles WHERE level <= :maxLevel ORDER BY level ASC")
    fun getRolesByMaxLevel(maxLevel: Int): Flow<List<Role>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRole(role: Role): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoles(roles: List<Role>): List<Long>

    @Update
    suspend fun updateRole(role: Role): Int

    @Delete
    suspend fun deleteRole(role: Role): Int

    @Query("DELETE FROM roles WHERE id = :id AND isSystem = 0")
    suspend fun deleteNonSystemRoleById(id: Long): Int

    @Query("SELECT COUNT(*) FROM roles")
    suspend fun count(): Int

    @Query("DELETE FROM roles")
    suspend fun deleteAllRoles(): Int
}
