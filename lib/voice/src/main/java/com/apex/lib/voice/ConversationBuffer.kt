package com.apex.lib.voice

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 多轮对话上下文缓冲。
 *
 * - 按 sessionId 维护一个 [ConcurrentLinkedDeque]<[Utterance]>
 * - 支持 [contextWindow] 滑动窗口（仅保留最近 N 条）
 * - 支持 [truncate] 按字符数截断
 * - 支持 [serialize] / [deserialize] 持久化
 *
 * 由 [VoiceEngine] 内部为每个会话维护一个实例。
 */
class ConversationBuffer(
    private val sessionId: String,
    private val contextWindow: Int = 20
) {

    private val deque = ConcurrentLinkedDeque<Utterance>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 追加一条对话。 */
    fun append(utterance: Utterance) {
        val tagged = if (utterance.sessionId == null) utterance.copy(sessionId = sessionId) else utterance
        deque.addLast(tagged)
        // 窗口裁剪：仅保留最近 contextWindow 条
        if (contextWindow > 0) {
            while (deque.size > contextWindow) {
                deque.pollFirst()
            }
        }
    }

    /** 当前缓冲区所有对话（按时间升序）。 */
    fun snapshot(): List<Utterance> = deque.toList()

    /** 当前对话条数。 */
    fun size(): Int = deque.size

    /** 清空缓冲区。 */
    fun clear() = deque.clear()

    /**
     * 按字符数截断：仅保留最后 [maxChars] 字符的对话。
     */
    fun truncate(maxChars: Int) {
        if (maxChars <= 0) return
        var total = 0
        val keep = mutableListOf<Utterance>()
        val desc = deque.descendingIterator()
        while (desc.hasNext()) {
            val u = desc.next()
            total += u.text.length
            if (total > maxChars) break
            keep.add(0, u)
        }
        deque.clear()
        keep.forEach { deque.addLast(it) }
    }

    /**
     * 拼接完整对话文本（USER / ASSISTANT 交替），可附加角色前缀。
     */
    fun render(withRolePrefix: Boolean = true): String {
        if (!withRolePrefix) return deque.joinToString(separator = "\n") { it.text }
        return deque.joinToString(separator = "\n") { "[${it.role.name}] ${it.text}" }
    }

    /**
     * 序列化为 JSON 字符串（可用于持久化 / 跨进程传递）。
     */
    fun serialize(): String {
        val dto = ConversationBufferDto(sessionId, contextWindow, deque.toList())
        return json.encodeToString(dto)
    }

    companion object {
        /**
         * 从 JSON 反序列化为 [ConversationBuffer] 实例。
         */
        fun deserialize(jsonString: String): ConversationBuffer? = try {
            val json = Json { ignoreUnknownKeys = true }
            val dto = json.decodeFromString<ConversationBufferDto>(jsonString)
            val buf = ConversationBuffer(dto.sessionId, dto.contextWindow)
            dto.utterances.forEach { buf.append(it) }
            buf
        } catch (t: Throwable) {
            ApexLog.w(ApexSuite.ApkId.VOICE, "[ConversationBuffer] deserialize failed: ${t.message}")
            null
        }
    }
}

/** 对话缓冲序列化 DTO。 */
@Serializable
private data class ConversationBufferDto(
    val sessionId: String,
    val contextWindow: Int,
    val utterances: List<Utterance>
)
