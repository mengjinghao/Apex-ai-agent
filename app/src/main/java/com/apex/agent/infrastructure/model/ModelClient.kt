package com.apex.agent.infrastructure.model

import kotlinx.coroutines.flow.Flow


    suspend fun complete(prompt: String): String

    suspend fun stream(prompt: String): Flow<String>
}
