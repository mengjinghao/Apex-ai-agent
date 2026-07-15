package com.apex.agent.core.multiagent

import android.os.Parcelable
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class ConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val agentId: String? = null,
    val isError: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
) : Parcelable

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

@Parcelize
data class AgentConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对�?,
    val agentId: String? = null,
    val templateId: String? = null,
    val messages: MutableList<ConversationMessage> = mutableListOf(),
    val startTime: Long = System.currentTimeMillis(),
    val lastActivity: Long = System.currentTimeMillis(),
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap()
) : Parcelable {

    fun addMessage(message: ConversationMessage) {
        messages.add(message)
        lastActivity = System.currentTimeMillis()
    }
        fun toJson(): String = Gson().toJson(this)
        fun getFormattedHistory(): String {
        return messages.joinToString("\n\n") { msg ->
            val rolePrefix = when (msg.role) {
                MessageRole.USER -> "?? 用户"
                MessageRole.ASSISTANT -> "?? 助手"
                MessageRole.SYSTEM -> "?? 系统"
                MessageRole.TOOL -> "?? 工具"
            }
            "${rolePrefix}:\n${msg.content}"
        }
    }

    companion object {
        fun fromJson(json: String): AgentConversation? = try { Gson().fromJson(json, AgentConversation::class.java) } catch (e: Exception) { null }
    }
}
