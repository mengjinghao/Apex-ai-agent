package com.apex.agent.infrastructure.model

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Singleton
class AnthropicClient constructor() : ModelClient {

    override suspend fun complete(prompt: String): String {
        return ""
    }

    override suspend fun stream(prompt: String): Flow<String> {
        return emptyFlow()
    }
}
