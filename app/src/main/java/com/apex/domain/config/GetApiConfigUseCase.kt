package com.apex.domain.config

import com.apex.core.kernel.ApexKernel
import com.apex.core.kernel.ConfigKeys
import com.apex.core.model.ApiConfig

/** 获取 API 配置 */
class GetApiConfigUseCase {
    fun execute(): ApiConfig {
        val store = ApexKernel.configStore
        return ApiConfig(
            endpoint = store.getString(ConfigKeys.API_ENDPOINT, ApiConfig.DEFAULT_ENDPOINT),
            apiKey = store.getString(ConfigKeys.API_KEY, ""),
            model = store.getString(ConfigKeys.API_MODEL, ApiConfig.DEFAULT_MODEL),
            systemPrompt = store.getString(ConfigKeys.SYSTEM_PROMPT, ApiConfig.DEFAULT_SYSTEM_PROMPT),
            temperature = store.getFloat(ConfigKeys.TEMPERATURE, 0.7f).toDouble()
        )
    }
}
