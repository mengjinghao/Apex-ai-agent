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

private val Context.roleCardDataStore by preferencesDataStore("agent_role_cards")

class AgentRoleCardManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentRoleCardManager"
        private val KEY_ROLE_CARDS = stringPreferencesKey("role_cards")
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _roleCards = mutableMapOf<String, AgentRoleCard>()

    val roleCards: List<AgentRoleCard>
        get() = _roleCards.values.toList()

    val enabledCards: List<AgentRoleCard>
        get() = _roleCards.values.filter { it.isEnabled }

    init {
        scope.launch { loadFromDataStore() }
    }

    private suspend fun loadFromDataStore() {
        try {
            val prefs = context.roleCardDataStore.data.first()
            prefs[KEY_ROLE_CARDS]?.let { json ->
                val type = object : TypeToken<List<AgentRoleCard>>() {}.type
                val loadedCards: List<AgentRoleCard> = gson.fromJson(json, type)
                _roleCards.clear()
                loadedCards.forEach { card -> _roleCards[card.id] = card }
            }
            if (_roleCards.isEmpty()) createDefaultRoleCards()
        } catch (e: Exception) {
            createDefaultRoleCards()
        }
    }

    private suspend fun saveToDataStore() {
        context.roleCardDataStore.edit { prefs ->
            prefs[KEY_ROLE_CARDS] = gson.toJson(_roleCards.values.toList())
        }
    }

    private suspend fun createDefaultRoleCards() {
        val defaultCards = AgentRole.values().map { role -> AgentRoleCard.createDefault(role) }
        _roleCards.clear()
        defaultCards.forEach { card -> _roleCards[card.id] = card }
        saveToDataStore()
    }

    fun getCardById(id: String): AgentRoleCard? = _roleCards[id]
    fun getCardByRole(role: AgentRole): AgentRoleCard? = _roleCards.values.find { it.role == role }

    fun getCardsByTags(tags: Set<String>): List<AgentRoleCard> {
        return _roleCards.values.filter { card -> tags.all { tag -> tag in card.tags } }
    }

    fun searchCards(query: String): List<AgentRoleCard> {
        val lowerQuery = query.lowercase()
        return _roleCards.values.filter { card ->
            card.name.lowercase().contains(lowerQuery) ||
            card.description.lowercase().contains(lowerQuery) ||
            card.goal.lowercase().contains(lowerQuery) ||
            card.skills.any { it.lowercase().contains(lowerQuery) }
        }
    }

    suspend fun addCard(card: AgentRoleCard): Boolean {
        if (card.id in _roleCards) return false
        _roleCards[card.id] = card.copy(updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun updateCard(card: AgentRoleCard): Boolean {
        if (card.id !in _roleCards) return false
        _roleCards[card.id] = card.copy(updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun deleteCard(cardId: String): Boolean {
        if (!_roleCards.containsKey(cardId)) return false
        val card = _roleCards[cardId]
        if (card?.isDefault == true) return false
        _roleCards.remove(cardId)
        saveToDataStore()
        return true
    }

    suspend fun toggleCardEnabled(cardId: String, enabled: Boolean): Boolean {
        val card = _roleCards[cardId] ?: return false
        _roleCards[cardId] = card.copy(isEnabled = enabled, updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun setModelCardForRoleCard(roleCardId: String, modelCardId: String): Boolean {
        val card = _roleCards[roleCardId] ?: return false
        _roleCards[roleCardId] = card.copy(defaultModelCardId = modelCardId, updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    suspend fun addSkillToCard(roleCardId: String, skill: String): Boolean {
        val card = _roleCards[roleCardId] ?: return false
        val newSkills = card.skills.toMutableSet().apply { add(skill) }
        _roleCards[roleCardId] = card.copy(skills = newSkills, updated = System.currentTimeMillis())
        saveToDataStore()
        return true
    }

    fun getRecommendedCardsForTask(taskType: String): List<AgentRoleCard> {
        return enabledCards.filter { card ->
            card.skills.any { skill ->
                taskType.lowercase().contains(skill.lowercase()) || skill.lowercase().contains(taskType.lowercase())
            }
        }.sortedByDescending { it.priority }
    }

    fun exportCard(roleCardId: String): String? {
        val card = getCardById(roleCardId) ?: return null
        return card.toJson()
    }

    suspend fun importCard(json: String): Boolean {
        val card = AgentRoleCard.fromJson(json) ?: return false
        return if (card.id in _roleCards) updateCard(card) else addCard(card)
    }

    fun destroy() {
        scope.cancel()
    }

    fun duplicateCard(roleCardId: String, newName: String? = null): AgentRoleCard? {
        val card = getCardById(roleCardId) ?: return null
        return card.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName ?: "${card.name} (副本,
            isDefault = false,
            tags = card.tags.toMutableSet().apply { add("duplicate") },
            created = System.currentTimeMillis(),
            updated = System.currentTimeMillis()
        )
    }
}
