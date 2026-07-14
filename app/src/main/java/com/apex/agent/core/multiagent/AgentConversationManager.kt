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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.apex.util.AppLogger

private val Context.conversationDataStore by preferencesDataStore("agent_conversations")

class AgentConversationManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentConversationManager"
        private val KEY_CONVERSATIONS = stringPreferencesKey("conversations")
        private const val MAX_CONVERSATIONS = 100
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _conversations = mutableMapOf<String, AgentConversation>()

    val conversations: List<AgentConversation>
        get() = _conversations.values.filter { !it.isArchived }.sortedByDescending { it.lastActivity }

    val starredConversations: List<AgentConversation>
        get() = conversations.filter { it.isStarred }

    val archivedConversations: List<AgentConversation>
        get() = _conversations.values.filter { it.isArchived }.sortedByDescending { it.lastActivity }

    init {
        scope.launch { loadFromDataStore() }
    }

    private suspend fun loadFromDataStore() {
        try {
            val prefs = context.conversationDataStore.data.first()
            prefs[KEY_CONVERSATIONS]?.let { json ->
                val type = object : TypeToken<List<AgentConversation>>() {}.type
                val loadedConversations: List<AgentConversation> = gson.fromJson(json, type)
                _conversations.clear()
                loadedConversations.forEach { conv -> _conversations[conv.id] = conv }
            }
        } catch (e: Exception) { AppLogger.w(TAG, "Failed to load conversations from DataStore", e) }
    }

    private suspend fun saveToDataStore() {
        val sorted = _conversations.values.sortedByDescending { it.lastActivity }.take(MAX_CONVERSATIONS)
        context.conversationDataStore.edit { prefs ->
            prefs[KEY_CONVERSATIONS] = gson.toJson(sorted)
        }
    }

    fun getConversationById(id: String): AgentConversation? = _conversations[id]
    fun getConversationsByAgent(agentId: String): List<AgentConversation> = conversations.filter { it.agentId == agentId }
    fun getConversationsByTemplate(templateId: String): List<AgentConversation> = conversations.filter { it.templateId == templateId }

    fun searchConversations(query: String): List<AgentConversation> {
        val lowerQuery = query.lowercase()
        return conversations.filter { conv ->
            conv.title.lowercase().contains(lowerQuery) ||
            conv.tags.any { it.lowercase().contains(lowerQuery) } ||
            conv.messages.any { msg -> msg.content.lowercase().contains(lowerQuery) }
        }
    }

    fun createNewConversation(title: String? = null, agentId: String? = null, templateId: String? = null, tags: Set<String> = emptySet()): AgentConversation {
        val conversation = AgentConversation(title = title ?: "新对${conversations.size + 1}", agentId = agentId, templateId = templateId, tags = tags)
        _conversations[conversation.id] = conversation
        scope.launch { saveToDataStore() }
        return conversation
    }

    suspend fun addMessage(conversationId: String, role: MessageRole, content: String, agentId: String? = null): ConversationMessage? {
        val conversation = _conversations[conversationId] ?: return null
        val message = ConversationMessage(conversationId = conversationId, role = role, content = content, agentId = agentId)
        conversation.addMessage(message)
        saveToDataStore()
        return message
    }

    suspend fun updateConversation(conversation: AgentConversation): Boolean {
        if (conversation.id !in _conversations) return false
        _conversations[conversation.id] = conversation
        saveToDataStore()
        return true
    }

    suspend fun deleteConversation(conversationId: String): Boolean {
        if (conversationId !in _conversations) return false
        _conversations.remove(conversationId)
        saveToDataStore()
        return true
    }

    suspend fun toggleStar(conversationId: String): Boolean {
        val conversation = _conversations[conversationId] ?: return false
        conversation.isStarred = !conversation.isStarred
        saveToDataStore()
        return conversation.isStarred
    }

    suspend fun archiveConversation(conversationId: String): Boolean {
        val conversation = _conversations[conversationId] ?: return false
        conversation.isArchived = true
        saveToDataStore()
        return true
    }

    suspend fun unarchiveConversation(conversationId: String): Boolean {
        val conversation = _conversations[conversationId] ?: return false
        conversation.isArchived = false
        saveToDataStore()
        return true
    }

    fun destroy() {
        scope.cancel()
    }

    fun getConversationStats(): ConversationStats {
        val total = _conversations.size
        val active = conversations.size
        val archived = archivedConversations.size
        val starred = starredConversations.size
        val totalMessages = _conversations.values.sumOf { it.messages.size }
        return ConversationStats(totalConversations = total, activeConversations = active, archivedConversations = archived, starredConversations = starred, totalMessages = totalMessages, avgMessagesPerConversation = if (total > 0) totalMessages / total.toFloat() else 0f)
    }

    data class ConversationStats(
        val totalConversations: Int,
        val activeConversations: Int,
        val archivedConversations: Int,
        val starredConversations: Int,
        val totalMessages: Int,
        val avgMessagesPerConversation: Float
    )
}
