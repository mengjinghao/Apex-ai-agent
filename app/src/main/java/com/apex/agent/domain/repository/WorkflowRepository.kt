package com.apex.agent.domain.repository

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.WorkflowDefinition

interface WorkflowRepository {
    suspend fun save(workflow: WorkflowDefinition): Result<Unit>
    suspend fun update(workflow: WorkflowDefinition): Result<Unit>
    suspend fun getById(id: String): Result<WorkflowDefinition>
    suspend fun list(): Result<List<WorkflowDefinition>>
    suspend fun delete(id: String): Result<Unit>
}
