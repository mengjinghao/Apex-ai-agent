package com.apex.agent.ui.screens.chat

import android.content.Context
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 聊天消息持久化 — 每个会话的消息保存到独立 JSON 文件。
 *
 * 存储结构：
 *   <app_data>/apex-chat-messages/<sessionId>.json
 */
class ChatMessageStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "apex-chat-messages").apply { mkdirs() }

    /** 保存会话消息。 */
    fun save(sessionId: String, messages: List<PersistedMessage>) {
        try {
            File(dir, "$sessionId.json").writeText(json.encodeToString(messages))
        } catch (t: Throwable) {
            ApexLog.w("chat", "[MessageStore] save failed: ${t.message}")
        }
    }

    /** 加载会话消息。 */
    fun load(sessionId: String): List<PersistedMessage> {
        return try {
            val file = File(dir, "$sessionId.json")
            if (!file.exists()) return emptyList()
            json.decodeFromString(file.readText())
        } catch (_: Throwable) { emptyList() }
    }

    /** 删除会话消息。 */
    fun delete(sessionId: String): Boolean {
        return File(dir, "$sessionId.json").delete()
    }

    /** 清空所有消息。 */
    fun clearAll(): Int {
        val files = dir.listFiles() ?: return 0
        var count = 0
        files.forEach { if (it.delete()) count++ }
        return count
    }

    /** 获取会话消息数。 */
    fun count(sessionId: String): Int {
        return load(sessionId).size
    }
}

/**
 * 持久化消息（与 ChatMessage 中的 Bubble 对应）。
 */
@Serializable
data class PersistedMessage(
    val isUser: Boolean,
    val timestamp: Long,
    val bubbles: List<PersistedBubble>
)

@Serializable
sealed class PersistedBubble {
    @Serializable

    @Serializable

    @Serializable

    @Serializable
