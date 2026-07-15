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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.apex.agent.core.tools.defaultTool.standard.name

private val Context.modelCardDataStore by preferencesDataStore("agent_model_cards")

class AgentModelCardManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentModelCardManager"
        private val KEY_MODEL_CARDS = stringPreferencesKey("model_cards")
        private val KEY_ROLE_MAPPINGS = stringPreferencesKey("role_mappings")
    }
        private val gson = Gson()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _modelCards = mutableListOf<ModelCard>()
        private val _roleCardMap = mutableMapOf<AgentRole, ModelCard>()
        val modelCards: List<ModelCard>
        get() = _modelCards.toList()
        val activeCards: List<ModelCard>
        get() = _modelCards.filter { it.isActive }

    init {
        scope.launch { loadFromDataStore() }
    }
        private suspend fun loadFromDataStore() {
        try {
            val prefs = context.modelCardDataStore.data.first()

            prefs[KEY_MODEL_CARDS]?.let { json ->
                val type = object : TypeToken<List<ModelCard>>() {}.type
                val loadedCards: List<ModelCard> = gson.fromJson(json, type)
                _modelCards.clear()
                _modelCards.addAll(loadedCards)
            }

            prefs[KEY_ROLE_MAPPINGS]?.let { json ->
                val type = object : TypeToken<Map<String, String>>() {}.type
                val mappings: Map<String, String> = gson.fromJson(json, type)
                _roleCardMap.clear()
                mappings.forEach { (roleName, cardId) ->
                    val role = try { AgentRole.valueOf(roleName) } catch (e: Exception) { null }
        val card = _modelCards.find { it.id == cardId }
        if (role != null && card != null) {
                        _roleCardMap[role] = card
                    }
                }
            }
        if (_modelCards.isEmpty()) {
                createDefaultCards()
            }
        } catch (e: Exception) {
            createDefaultCards()
        }
    }
        private suspend fun saveToDataStore() {
        context.modelCardDataStore.edit { prefs ->
            prefs[KEY_MODEL_CARDS] = gson.toJson(_modelCards)
        val roleMap = _roleCardMap.mapKeys { it.key.name to it.value.id }
            prefs[KEY_ROLE_MAPPINGS] = gson.toJson(roleMap)
        }
    }
        private suspend fun createDefaultCards() {
        val defaultCards = listOf(
            ModelCard(name = "全能助手", description = "通用多用途AI助手", provider = ModelProvider.DEEPSEEK, roles = setOf(AgentRole.COORDINATOR, AgentRole.MONITOR), temperature = 0.7f, isActive = true),
            ModelCard(name = "研究专家", description = "专业研究Agent的专用模, provider = ModelProvider.OPENAI, roles = setOf(AgentRole.RESEARCHER), temperature = 0.5f, isActive = true),
            ModelCard(name = "编程大师", description = "代码生成和优化专, provider = ModelProvider.GOOGLE, roles = setOf(AgentRole.DEVELOPER), temperature = 0.8f, isActive = true),
            ModelCard(name = "创意设计, description = "设计和创意任, provider = ModelProvider.CUSTOM, roles = setOf(AgentRole.DESIGNER), temperature = 0.9f, isActive = true),
            ModelCard(name = "快速响, description = "快速响应简单问, provider = ModelProvider.LOCAL, roles = setOf(AgentRole.EXECUTOR), temperature = 0.6f, isActive = true)
        )

        _modelCards.addAll(defaultCards)
        defaultCards.forEach { card ->
            card.roles.firstOrNull()?.let { role -> _roleCardMap[role] = card }
        }
        saveToDataStore()
    }
        fun getCardById(id: String): ModelCard? = _modelCards.find { it.id == id }
        fun getCardsForRole(role: AgentRole): List<ModelCard> = _modelCards.filter { role in it.roles }
        fun getCardForRole(role: AgentRole): ModelCard? = _roleCardMap[role] ?: getCardsForRole(role).firstOrNull()

    suspend fun setCardForRole(role: AgentRole, cardId: String): Boolean {
        val card = getCardById(cardId) ?: return false
        if (role in card.roles) {
            _roleCardMap[role] = card
            saveToDataStore()
        return true
        }
        return false
    }

    suspend fun updateCard(card: ModelCard): Boolean {
        val index = _modelCards.indexOfFirst { it.id == card.id }
        if (index != -1) {
            _modelCards[index] = card
            saveToDataStore()
        return true
        }
        return false
    }

    suspend fun deleteCard(cardId: String): Boolean {
        val removed = _modelCards.removeAll { it.id == cardId }
        if (removed) {
            _roleCardMap.values.removeIf { it.id == cardId }
            saveToDataStore()
        }
        return removed
    }
        fun destroy() {
        scope.cancel()
    }

    suspend fun assignCardToRole(cardId: String, role: AgentRole): Boolean {
        val card = getCardById(cardId) ?: return false
        val updatedRoles = card.roles.toMutableSet().apply { add(role) }
        val updatedCard = card.copy(roles = updatedRoles)
        val success = updateCard(updatedCard)
        if (success && _roleCardMap[role] == null) {
            setCardForRole(role, cardId)
        }
        return success
    }
}
