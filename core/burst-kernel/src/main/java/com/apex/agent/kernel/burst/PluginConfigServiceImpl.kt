package com.apex.agent.kernel.burst

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.apex.agent.plugins.burst.base.ConfigFieldDef
import com.apex.agent.plugins.burst.base.ConfigSchema
import com.apex.agent.plugins.burst.base.ConfigSection
import com.apex.agent.plugins.burst.base.ConfigType
import com.apex.agent.plugins.burst.base.ConfigValidation
import com.apex.agent.plugins.burst.base.IPluginConfigService
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PluginConfigServiceImpl(private val context: Context) : IPluginConfigService {
    companion object {
        private const val TAG = "PluginConfigService"
    }

    private val prefsMap = ConcurrentHashMap<String, SharedPreferences>()
    private val schemas = ConcurrentHashMap<String, ConfigSchema>()
    private val changeListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String, String, String) -> Unit>>()

    private fun prefs(skillId: String): SharedPreferences {
        return prefsMap.getOrPut(skillId) {
            context.getSharedPreferences("plugin_config_${skillId}", Context.MODE_PRIVATE)
        }
    }

    private fun editor(skillId: String): SharedPreferences.Editor {
        return prefs(skillId).edit()
    }

    override fun getString(skillId: String, key: String, default: String): String {
        return prefs(skillId).getString(key, default) ?: default
    }

    override fun setString(skillId: String, key: String, value: String) {
        val old = prefs(skillId).getString(key, null)
        editor(skillId).putString(key, value).apply()
        notifyChange(skillId, key, old ?: "", value)
    }

    override fun getInt(skillId: String, key: String, defaultVal: Int): Int {
        return try { prefs(skillId).getString(key, defaultVal.toString())?.toIntOrNull() ?: defaultVal } catch (e: Exception) { Log.w(TAG, "getInt fallback to default", e); defaultVal }
    }

    override fun setInt(skillId: String, key: String, value: Int) {
        setString(skillId, key, value.toString())
    }

    override fun getFloat(skillId: String, key: String, defaultVal: Float): Float {
        return try { prefs(skillId).getString(key, defaultVal.toString())?.toFloatOrNull() ?: defaultVal } catch (e: Exception) { Log.w(TAG, "getFloat fallback to default", e); defaultVal }
    }

    override fun setFloat(skillId: String, key: String, value: Float) {
        setString(skillId, key, value.toString())
    }

    override fun getBoolean(skillId: String, key: String, defaultVal: Boolean): Boolean {
        return prefs(skillId).getBoolean(key, defaultVal)
    }

    override fun setBoolean(skillId: String, key: String, value: Boolean) {
        val old = prefs(skillId).getBoolean(key, false)
        val oldStr = old.toString()
        editor(skillId).putBoolean(key, value).apply()
        notifyChange(skillId, key, oldStr, value.toString())
    }

    override fun getStringList(skillId: String, key: String, defaultVal: List<String>): List<String> {
        val json = prefs(skillId).getString(key, null) ?: return defaultVal
        return try {
            JSONObject("{\"list\":$json}").getJSONArray("list").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) { Log.w(TAG, "getStringList parse failed", e); defaultVal }
    }

    override fun setStringList(skillId: String, key: String, value: List<String>) {
        val json = JSONObject().put("list", value).toString()
        val jsonStr = JSONObject(json).getJSONArray("list").toString()
        val old = prefs(skillId).getString(key, null)
        editor(skillId).putString(key, jsonStr).apply()
        notifyChange(skillId, key, old ?: "", jsonStr)
    }

    override fun getAll(skillId: String): Map<String, String> {
        return prefs(skillId).all.mapValues { (_, v) -> v?.toString() ?: "" }
    }

    override fun clear(skillId: String) {
        editor(skillId).clear().apply()
    }

    override fun clearKey(skillId: String, key: String) {
        val old = prefs(skillId).getString(key, null)
        editor(skillId).remove(key).apply()
        notifyChange(skillId, key, old ?: "", "")
    }

    override fun registerSchema(skillId: String, schema: ConfigSchema) {
        schemas[skillId] = schema
    }

    override fun getSchema(skillId: String): ConfigSchema? = schemas[skillId]

    override fun getAllSchemas(): Map<String, ConfigSchema> = schemas.toMap()

    override fun validate(skillId: String): ConfigValidation {
        val schema = schemas[skillId] ?: return ConfigValidation(true)
        val errors = mutableMapOf<String, String>()
        val allFields = schema.sections.flatMap { it.fields }

        for (field in allFields) {
            if (field.required) {
                val value = getString(skillId, field.key)
                if (value.isBlank()) {
                    errors[field.key] = "${field.label} is required"
                }
            }
            when (field.type) {
                ConfigType.INT -> {
                    val raw = getString(skillId, field.key, field.defaultValue)
                    val num = raw.toIntOrNull()
                    if (num == null) errors[field.key] = "${field.label} must be an integer"
                    else if (num < field.min.toInt()) errors[field.key] = "${field.label} min is ${field.min.toInt()}"
                    else if (num > field.max.toInt()) errors[field.key] = "${field.label} max is ${field.max.toInt()}"
                }
                ConfigType.FLOAT -> {
                    val raw = getString(skillId, field.key, field.defaultValue)
                    val num = raw.toFloatOrNull()
                    if (num == null) errors[field.key] = "${field.label} must be a number"
                    else if (num < field.min.toFloat()) errors[field.key] = "${field.label} min is ${field.min}"
                    else if (num > field.max.toFloat()) errors[field.key] = "${field.label} max is ${field.max}"
                }
                ConfigType.ENUM -> {
                    val raw = getString(skillId, field.key, field.defaultValue)
                    if (raw.isNotBlank() && field.options.isNotEmpty() && raw !in field.options) {
                        errors[field.key] = "${field.label} must be one of: ${field.options.joinToString(", ")}"
                    }
                }
                else -> {}
            }
        }

        return ConfigValidation(errors.isEmpty(), errors)
    }

    override fun export(skillId: String): String {
        val data = JSONObject()
        val all = getAll(skillId)
        all.forEach { (k, v) -> data.put(k, v) }
        val result = JSONObject()
        result.put("pluginId", skillId)
        result.put("config", data)
        return result.toString(2)
    }

    override fun import(skillId: String, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val config = root.getJSONObject("config")
            val p = prefs(skillId)
            val e = p.edit()
            config.keys().asSequence().forEach { key ->
                val value = config.getString(key)
                e.putString(key, value)
            }
            e.apply()
            true
        } catch (e: Exception) { Log.e(TAG, "import config failed for $skillId", e); false }
    }

    override fun addChangeListener(skillId: String, listener: (String, String, String) -> Unit) {
        changeListeners.computeIfAbsent(skillId) { CopyOnWriteArrayList() }.add(listener)
    }

    override fun removeChangeListener(skillId: String, listener: (String, String, String) -> Unit) {
        changeListeners[skillId]?.remove(listener)
    }

    private fun notifyChange(skillId: String, key: String, oldValue: String, newValue: String) {
        changeListeners[skillId]?.forEach { listener ->
            try { listener(key, oldValue, newValue) } catch (e: Exception) { Log.e(TAG, "notifyChange listener error for $skillId", e) }
        }
    }
}
