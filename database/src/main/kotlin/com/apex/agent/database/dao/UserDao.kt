package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY createdAt DESC")
    fun getAllUsers(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE id = :userId")
    fun getUserById(userId: Long): Flow<User?>

    @Query("SELECT * FROM users WHERE email = :email")
    fun getUserByEmail(email: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertUser(user: User): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertUsers(users: List<User>): List<Long>

    @Update
    fun updateUser(user: User): Int

    @Delete
    fun deleteUser(user: User): Int

    @Query("DELETE FROM users WHERE id = :userId")
    fun deleteUserById(userId: Long): Int

    @Query("DELETE FROM users")
    fun deleteAllUsers(): Int
}
