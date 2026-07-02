package com.apex.agent.domain.repository

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.agent.model.Agent

interface AgentRepository {
    suspend fun save(agent: Agent): Result<Unit>
    suspend fun update(agent: Agent): Result<Unit>
    suspend fun getById(id: String): Result<Agent>
    suspend fun list(): Result<List<Agent>>
    suspend fun delete(id: String): Result<Unit>
}
