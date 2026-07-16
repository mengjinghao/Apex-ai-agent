package com.apex.agent.core.multiagent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

private val Context.templateDataStore by preferencesDataStore("agent_templates")

class AgentTemplateManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentTemplateManager"
        private val KEY_TEMPLATES = stringPreferencesKey("agent_templates")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val _templates = mutableMapOf<String, AgentTemplate>()

    val templates: List<AgentTemplate>
        get() = _templates.values.toList()

    val defaultTemplates: List<AgentTemplate>
        get() = _templates.values.filter { it.isDefault }

    val categories: List<String>
        get() = _templates.values.map { it.category }.distinct()

    init {
        scope.launch { loadFromDataStore() }
    }
    
    fun cleanup() {
        scope.cancel()
    }

    private suspend fun loadFromDataStore() {
        try {
            val prefs = context.templateDataStore.data.first()
            prefs[KEY_TEMPLATES]?.let { json ->
                val type = object : TypeToken<List<AgentTemplate>>() {}.type
                val loadedTemplates: List<AgentTemplate> = gson.fromJson(json, type)
                _templates.clear()
                loadedTemplates.forEach { template -> _templates[template.id] = template }
            }
            if (_templates.isEmpty()) createDefaultTemplates()
        } catch (e: Exception) {
            createDefaultTemplates()
        }
    }

    private suspend fun saveToDataStore() {
        context.templateDataStore.edit { prefs ->
            prefs[KEY_TEMPLATES] = gson.toJson(_templates.values.toList())
        }
    }

    private suspend fun createDefaultTemplates() {
        val defaults = AgentTemplate.getDefaultTemplates()
        _templates.clear()
        defaults.forEach { template -> _templates[template.id] = template }
        saveToDataStore()
    }

    fun getTemplateById(id: String): AgentTemplate? = _templates[id]
    fun getTemplatesByCategory(category: String): List<AgentTemplate> = _templates.values.filter { it.category == category }

    fun searchTemplates(query: String): List<AgentTemplate> {
        val lowerQuery = query.lowercase()
        return _templates.values.filter { template ->
            template.name.lowercase().contains(lowerQuery) ||
            template.description.lowercase().contains(lowerQuery) ||
            template.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    fun getMostUsedTemplates(limit: Int = 5): List<AgentTemplate> = _templates.values.sortedByDescending { it.usageCount }.take(limit)
    fun getHighestRatedTemplates(limit: Int = 5): List<AgentTemplate> = _templates.values.sortedByDescending { it.rating }.take(limit)

    suspend fun addTemplate(template: AgentTemplate): Boolean {
        if (template.id in _templates) return false
        _templates[template.id] = template.copy(updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun updateTemplate(template: AgentTemplate): Boolean {
        if (template.id !in _templates) return false
        _templates[template.id] = template.copy(updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun deleteTemplate(templateId: String): Boolean {
        val template = _templates[templateId] ?: return false
        if (template.isDefault) return false
        _templates.remove(templateId)
        saveToDataStore()
        return true
    }

    suspend fun incrementUsage(templateId: String) {
        val template = _templates[templateId] ?: return
        _templates[templateId] = template.copy(usageCount = template.usageCount + 1, updated = System.currentTimeMillis())
        saveToDataStore()
    }

    suspend fun rateTemplate(templateId: String, rating: Float) {
        val template = _templates[templateId] ?: return
        val newRating = if (template.usageCount > 0) {
            ((template.rating * template.usageCount) + rating) / (template.usageCount + 1)
        } else {
            rating
        }
        _templates[templateId] = template.copy(rating = newRating, updated = System.currentTimeMillis())
        saveToDataStore()
    }

    fun duplicateTemplate(templateId: String, newName: String? = null): AgentTemplate? {
        val original = _templates[templateId] ?: return null
        return original.copy(id = UUID.randomUUID().toString(), name = newName ?: "${original.name} (副本�?, isDefault = false, tags = original.tags.toMutableSet().apply { add("duplicate") }, usageCount = 0, rating = 0f, created = System.currentTimeMillis(), updated = System.currentTimeMillis())
    }

    suspend fun exportTemplate(templateId: String): String? {
        val template = _templates[templateId] ?: return null
        return template.toJson()
    }

    suspend fun importTemplate(json: String): Boolean {
        val template = AgentTemplate.fromJson(json) ?: return false
        return if (template.id in _templates) updateTemplate(template) else addTemplate(template)
    }
}
