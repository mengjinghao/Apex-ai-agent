package com.apex.agent.ui.screens.chat

import android.content.Context
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天会话数据模型。
 */
@Serializable
data class ChatSession(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var lastMessage: String = "",
    var messageCount: Int = 0,
    var model: String = "DeepSeek · deepseek-chat",
    var pinned: Boolean = false
)

/**
 * 会话管理器 — 持久化到本地 JSON。
 *
 * 功能：
 * - 新建会话（自动生成标题）
 * - 列出所有会话（按更新时间降序，置顶优先）
 * - 切换会话
 * - 删除会话
 * - 重命名会话
 * - 更新最后消息（用于列表预览）
 * - 置顶/取消置顶
 * - 搜索会话
 * - 清空所有会话
 */
class ChatSessionManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "apex-chat-sessions.json")
    private val messageStore = ChatMessageStore(context)
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    init { load() }

    /** 新建会话。 */
    fun create(title: String = "新对话"): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-${now.toString(36)}-${(0..5).joinToString("") { ('a'..'z').random().toString() }}",
            title = if (title.isBlank()) "新对话" else title,
            createdAt = now,
            updatedAt = now
        )
        sessions[session.id] = session
        persist()
        ApexLog.d("chat", "[SessionManager] created: ${session.id}")
        return session
    }

    /** 获取会话。 */
    fun get(sessionId: String): ChatSession? = sessions[sessionId]

    /** 列出所有会话（置顶优先 + 按更新时间降序）。 */
    fun listAll(): List<ChatSession> {
        return sessions.values
            .sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })
    }

    /** 搜索会话（标题 + 最后消息）。 */
    fun search(query: String): List<ChatSession> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return listAll()
        return listAll().filter {
            it.title.lowercase().contains(q) || it.lastMessage.lowercase().contains(q)
        }
    }

    /** 更新会话标题。 */
    fun rename(sessionId: String, title: String): Boolean {
        val s = sessions[sessionId] ?: return false
        s.title = if (title.isBlank()) "未命名" else title
        s.updatedAt = System.currentTimeMillis()
        persist()
        return true
    }

    /** 更新最后消息（用于列表预览）。 */
    fun updateLastMessage(sessionId: String, lastMessage: String, messageCount: Int) {
        val s = sessions[sessionId] ?: return
        s.lastMessage = lastMessage.take(100)
        s.messageCount = messageCount
        s.updatedAt = System.currentTimeMillis()
        // 如果标题是默认的"新对话"，自动用最后消息生成标题
        if (s.title == "新对话" && lastMessage.isNotBlank()) {
            s.title = lastMessage.take(20).let { if (lastMessage.length > 20) "$it..." else it }
        }
        persist()
    }

    /** 置顶/取消置顶。 */
    fun togglePin(sessionId: String): Boolean {
        val s = sessions[sessionId] ?: return false
        s.pinned = !s.pinned
        persist()
        return s.pinned
    }

    /** 删除会话（同步删除消息）。 */
    fun delete(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        if (removed) {
            messageStore.delete(sessionId)
            persist()
        }
        return removed
    }

    /** 清空所有会话（同步删除所有消息）。 */
    fun clearAll(): Int {
        val count = sessions.size
        sessions.clear()
        messageStore.clearAll()
        persist()
        return count
    }

    /** 会话总数。 */
    fun count(): Int = sessions.size

    // ===== 消息持久化 =====

    /** 保存会话消息。 */
    fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
        val persisted = messages.map { msg ->
            PersistedMessage(
                isUser = msg.isUser,
                timestamp = msg.timestamp,
                bubbles = msg.bubbles.map { it.toPersisted() }
            )
        }
        messageStore.save(sessionId, persisted)
    }

    /** 加载会话消息。 */
    fun loadMessages(sessionId: String): List<PersistedMessage> {
        return messageStore.load(sessionId)
    }

    /** 持久化 Bubble 转换。 */
    private fun Bubble.toPersisted(): PersistedBubble = when (this) {
        is Bubble.Thinking -> PersistedBubble.Thinking(text)
        is Bubble.Text -> PersistedBubble.Text(text)
        is Bubble.Command -> PersistedBubble.Command(command, status.name, output)
        is Bubble.Search -> PersistedBubble.Search(query, results, status)
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(sessions.values.toList()))
        } catch (t: Throwable) {
            ApexLog.w("chat", "[SessionManager] persist failed: ${t.message}")
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val data = json.decodeFromString<List<ChatSession>>(file.readText())
            data.forEach { sessions[it.id] = it }
            ApexLog.d("chat", "[SessionManager] loaded ${sessions.size} sessions")
        } catch (_: Throwable) {}
    }
}
