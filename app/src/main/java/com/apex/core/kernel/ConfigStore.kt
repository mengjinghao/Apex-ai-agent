package com.apex.core.kernel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 配置存储 — 基于 SharedPreferences + StateFlow，提供响应式配置读取。
 */
class ConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apex_config", Context.MODE_PRIVATE)
    private val _flows = mutableMapOf<String, MutableStateFlow<*>>()

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        notifyChange(key)
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        notifyChange(key)
    }

    fun getFloat(key: String, default: Float = 0f): Float {
        return prefs.getFloat(key, default)
    }

    fun setFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        notifyChange(key)
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun observeString(key: String, default: String = ""): StateFlow<String> {
        val existing = _flows[key] as? MutableStateFlow<String>
        if (existing != null) return existing
        val flow = MutableStateFlow(getString(key, default))
        _flows[key] = flow
        return flow
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun observeBoolean(key: String, default: Boolean = false): StateFlow<Boolean> {
        val existing = _flows[key] as? MutableStateFlow<Boolean>
        if (existing != null) return existing
        val flow = MutableStateFlow(getBoolean(key, default))
        _flows[key] = flow
        return flow
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun observeFloat(key: String, default: Float = 0f): StateFlow<Float> {
        val existing = _flows[key] as? MutableStateFlow<Float>
        if (existing != null) return existing
        val flow = MutableStateFlow(getFloat(key, default))
        _flows[key] = flow
        return flow
    }

    @Synchronized
    private fun notifyChange(key: String) {
        when (val flow = _flows[key]) {
            is MutableStateFlow<*> -> {
                when (flow.value) {
                    is String -> (flow as MutableStateFlow<String>).value = getString(key)
                    is Boolean -> (flow as MutableStateFlow<Boolean>).value = getBoolean(key)
                    is Float -> (flow as MutableStateFlow<Float>).value = getFloat(key)
                }
            }
        }
    }
}

/** 配置键常量 */
object ConfigKeys {
    const val API_ENDPOINT = "api_endpoint"
    const val API_KEY = "api_key"
    const val API_MODEL = "api_model"
    const val SYSTEM_PROMPT = "system_prompt"
    const val TEMPERATURE = "temperature"
    const val THEME_MODE = "theme_mode"  // light / dark / system
    const val DYNAMIC_COLOR = "dynamic_color"
}
