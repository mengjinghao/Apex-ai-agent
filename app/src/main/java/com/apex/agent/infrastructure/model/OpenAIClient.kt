package com.apex.agent.infrastructure.model

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Singleton
class OpenAIClient constructor() : ModelClient {

    companion object {
        private const val TAG = "OpenAIClient"
    }

    override suspend fun complete(prompt: String): String {
        Log.w(TAG, "Stub implementation returning empty string")
        return ""
    }

    override suspend fun stream(prompt: String): Flow<String> {
        Log.w(TAG, "Stub implementation returning emptyFlow()")
        return emptyFlow()
    }
}
