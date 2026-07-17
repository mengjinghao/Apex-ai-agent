package com.apex.ai.data

import android.content.Context

object ApiConfigManager {
    private const val PREFS_NAME = "apex_api_config"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_TEMPERATURE = "temperature"

    fun getApiConfig(context: Context): ApiConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ApiConfig(
            endpoint = prefs.getString(KEY_ENDPOINT, "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat",
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "你是 Apex AI Agent，一个智能助手。请用中文回答。") ?: "你是 Apex AI Agent，一个智能助手。请用中文回答。",
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f).toDouble()
        )
    }

    fun saveApiConfig(context: Context, config: ApiConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_ENDPOINT, config.endpoint)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_MODEL, config.model)
            putString(KEY_SYSTEM_PROMPT, config.systemPrompt)
            putFloat(KEY_TEMPERATURE, config.temperature.toFloat())
        }.apply()
    }
}
