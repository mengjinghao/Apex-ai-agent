package com.apex.agent.domain.repository

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.Task

interface TaskRepository {
    suspend fun save(task: Task): Result<Unit>
    suspend fun update(task: Task): Result<Unit>
    suspend fun getById(id: String): Result<Task>
    suspend fun list(): Result<List<Task>>
    suspend fun delete(id: String): Result<Unit>
}
