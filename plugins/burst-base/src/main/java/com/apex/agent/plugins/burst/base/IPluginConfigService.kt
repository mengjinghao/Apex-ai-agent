package com.apex.agent.plugins.burst.base

interface IPluginConfigService {
    fun getString(skillId: String, key: String, default: String = ""): String
    fun setString(skillId: String, key: String, value: String)
    fun getInt(skillId: String, key: String, default: Int = 0): Int
    fun setInt(skillId: String, key: String, value: Int)
    fun getFloat(skillId: String, key: String, default: Float = 0f): Float
    fun setFloat(skillId: String, key: String, value: Float)
    fun getBoolean(skillId: String, key: String, default: Boolean = false): Boolean
    fun setBoolean(skillId: String, key: String, value: Boolean)
    fun getStringList(skillId: String, key: String, default: List<String> = emptyList()): List<String>
    fun setStringList(skillId: String, key: String, value: List<String>)
    fun getAll(skillId: String): Map<String, String>
    fun clear(skillId: String)
    fun clearKey(skillId: String, key: String)

    fun registerSchema(skillId: String, schema: ConfigSchema)
    fun getSchema(skillId: String): ConfigSchema?
    fun getAllSchemas(): Map<String, ConfigSchema>

    fun validate(skillId: String): ConfigValidation
    fun export(skillId: String): String
    fun import(skillId: String, json: String): Boolean

    fun addChangeListener(skillId: String, listener: (key: String, oldValue: String, newValue: String) -> Unit)
    fun removeChangeListener(skillId: String, listener: (key: String, oldValue: String, newValue: String) -> Unit)
}
