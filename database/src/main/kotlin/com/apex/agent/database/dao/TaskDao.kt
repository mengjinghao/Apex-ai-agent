package com.apex.agent.database.dao

import androidx.room.*
import com.apex.agent.database.entity.Task
import com.apex.agent.database.entity.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    fun getTaskById(taskId: Long): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE userId = :userId ORDER BY createdAt DESC")
    fun getTasksByUserId(userId: Long): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY createdAt DESC")
    fun getTasksByStatus(status: TaskStatus): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTask(task: Task): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTasks(tasks: List<Task>): List<Long>

    @Update
    fun updateTask(task: Task): Int

    @Delete
    fun deleteTask(task: Task): Int

    @Query("DELETE FROM tasks WHERE id = :taskId")
    fun deleteTaskById(taskId: Long): Int

    @Query("DELETE FROM tasks WHERE userId = :userId")
    fun deleteTasksByUserId(userId: Long): Int

    @Query("DELETE FROM tasks")
    fun deleteAllTasks(): Int

    @Query("UPDATE tasks SET status = :status WHERE id = :taskId")
    fun updateTaskStatus(taskId: Long, status: TaskStatus): Int
}
