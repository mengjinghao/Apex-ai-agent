package com.apex.ai.service

import com.apex.ai.data.ApiConfig

object AIServiceFactory {
    fun create(config: ApiConfig): AIService {
        return OpenAIProvider(config)
    }
}
