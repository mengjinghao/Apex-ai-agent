package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * Chat domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class ChatServiceStartResultData(
    val isConnected: Boolean,
    val connectionTime: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return if (isConnected) {
            "Chat service started and connected successfully"
        } else {
            "Chat service connection failed"
        }
    }
}

/** 新建对话结果数据 */

@Serializable
data class ChatCreationResultData(
    val chatId: String,
    val createdAt: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return "Created new chat\nChat ID: ${chatId}"
    }
}

/** 对话列表结果数据 */

@Serializable
data class ChatListResultData(
    val totalCount: Int,
    val currentChatId: String?,
    val chats: List<ChatInfo>
) : ToolResultData() {
    
    @Serializable
    data class ChatInfo(
        val id: String,
        val title: String,
        val messageCount: Int,
        val createdAt: String,
        val updatedAt: String,
        val isCurrent: Boolean,
        val inputTokens: Int,
        val outputTokens: Int,
        val characterCardName: String? = null
    )
    
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Chat List (${totalCount} total):")
        if (currentChatId != null) {
            sb.appendLine("Current Chat ID: ${currentChatId}")
        }
        sb.appendLine()

        if (chats.isEmpty()) {
            sb.appendLine("No chats")
        } else {
            chats.forEach { chat ->
                val currentMarker = if (chat.isCurrent) " [Current]" else ""
                sb.appendLine("ID: ${chat.id}${currentMarker}")
                sb.appendLine("Title: ${chat.title}")
                sb.appendLine("Message Count: ${chat.messageCount}")
                if (!chat.characterCardName.isNullOrBlank()) {
                    sb.appendLine("Character Card: ${chat.characterCardName}")
                }
                sb.appendLine("Token Statistics: Input ${chat.inputTokens} / Output ${chat.outputTokens}")
                sb.appendLine("Created: ${chat.createdAt}")
                sb.appendLine("Updated: ${chat.updatedAt}")
                sb.appendLine("---")
            }
        }

        return sb.toString().trim()
    }
}

/** 查找对话结果数据 */

@Serializable
data class ChatFindResultData(
    val matchedCount: Int,
    val chat: ChatListResultData.ChatInfo?
) : ToolResultData() {
    override fun toString(): String {
        return if (chat != null) {
            "Found chat (${chat.id}) (matched=${matchedCount})"
        } else {
            "No chat found (matched=${matchedCount})"
        }
    }
}

/** 对话输入状态结果数�*/

@Serializable
data class AgentStatusResultData(
    val chatId: String,
    val state: String,
    val message: String? = null,
    val isIdle: Boolean = false,
    val isProcessing: Boolean = false
) : ToolResultData() {
    override fun toString(): String {
        val detail = message?.takeIf { it.isNotBlank() }?.let { " (${it})" } ?: ""
        return "Chat ${chatId} status: ${state}${detail}"
    }
}

/** 切换对话结果数据 */

@Serializable
data class ChatSwitchResultData(
    val chatId: String,
    val chatTitle: String = "",
    val switchedAt: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return if (chatTitle.isNotBlank()) {
            "Switched to chat: ${chatTitle}\nChat ID: ${chatId}"
        } else {
            "Switched to chat: ${chatId}"
        }
    }
}

/** 更新对话标题结果数据 */

@Serializable
data class ChatTitleUpdateResultData(
    val chatId: String,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return "Updated chat title: ${chatId} -> ${title}"
    }
}

/** 删除对话结果数据 */

@Serializable
data class ChatDeleteResultData(
    val chatId: String,
    val deletedAt: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        return "Deleted chat: ${chatId}"
    }
}


@Serializable
data class ChatMessagesResultData(
    val chatId: String,
    val order: String,
    val limit: Int,
    val messages: List<ChatMessageInfo>
) : ToolResultData() {

    @Serializable
    data class ChatMessageInfo(
        val sender: String,
        val content: String,
        val timestamp: Long,
        val roleName: String = "",
        val provider: String = "",
        val modelName: String = ""
    )

    override fun toString(): String {
        return "Chat messages: ${chatId} (order=${order}, limit=${limit})\nTotal: ${messages.size}"
    }
}

/** 发送消息结果数�*/

@Serializable
data class MessageSendResultData(
    val chatId: String,
    val message: String,
    val aiResponse: String? = null,
    val receivedAt: Long? = null,
    val sentAt: Long = System.currentTimeMillis()
) : ToolResultData() {
    override fun toString(): String {
        val messagePreview = if (message.length > 50) {
            "${message.take(50)}..."
        } else {
            message
        }
        val response = aiResponse
        return if (response.isNullOrBlank()) {
            "Message sent to chat: ${chatId}\nMessage content: ${messagePreview}"
        } else {
            val responsePreview = if (response.length > 200) {
                "${response.take(200)}..."
            } else {
                response
            }
            "Message sent to chat: ${chatId}\nMessage content: ${messagePreview}\nAI Reply: ${responsePreview}"
        }
    }
}

/** 记忆链接结果数据 */

