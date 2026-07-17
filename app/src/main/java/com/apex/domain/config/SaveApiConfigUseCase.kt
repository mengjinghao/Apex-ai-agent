package com.apex.domain.config

import com.apex.core.kernel.ApexKernel
import com.apex.core.kernel.ConfigKeys
import com.apex.core.model.ApiConfig

/** 保存 API 配置 */
class SaveApiConfigUseCase {
    fun execute(config: ApiConfig) {
        val store = ApexKernel.configStore
        store.setString(ConfigKeys.API_ENDPOINT, config.endpoint)
        store.setString(ConfigKeys.API_KEY, config.apiKey)
        store.setString(ConfigKeys.API_MODEL, config.model)
        store.setString(ConfigKeys.SYSTEM_PROMPT, config.systemPrompt)
        store.setFloat(ConfigKeys.TEMPERATURE, config.temperature.toFloat())
    }
}
