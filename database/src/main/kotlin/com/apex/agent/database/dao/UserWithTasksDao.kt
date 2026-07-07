package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.UserWithTasks
import kotlinx.coroutines.flow.Flow

@Dao
interface UserWithTasksDao {
    @Transaction
    @Query("SELECT * FROM users WHERE id = :userId")
    fun getUserWithTasks(userId: Long): Flow<UserWithTasks?>

    @Transaction
    @Query("SELECT * FROM users")
    fun getAllUsersWithTasks(): Flow<List<UserWithTasks>>
}
