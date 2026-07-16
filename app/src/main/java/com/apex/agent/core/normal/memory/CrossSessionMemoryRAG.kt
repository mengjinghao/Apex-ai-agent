package com.apex.agent.core.normal.memory

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * F5: 跨会话记忆检索（Cross-Session Memory RAG）
 *
 * 用户开启新会话时，自动用当前问题向量检索历史会话相关片段，
 * 作为 <related_history> 注入 prompt。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 共享 Agent 间记忆
 * - 狂暴用 RAGPipelineSkill 做任务级 RAG
 * - 本功能专注**用户对话级 RAG**，是单 Agent 跨会话连续性的核心
 */

/**
 * 记忆片段
 */
data class MemorySnippet(
    val id: String,
    val sessionId: String,
    val content: String,
    val timestamp: Long,
    val role: String,  // user / assistant
    val topic: String = "",
    val importance: Float = 1.0f,
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemorySnippet) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

/**
 * 检索结果
 */
data class RetrievalResult(
    val snippets: List<MemorySnippet>,
    val query: String,
    val totalSearched: Int,
    val searchTimeMs: Long
)

/**
 * 跨会话记忆 RAG
 */
class CrossSessionMemoryRAG(
    private val maxMemories: Int = 10_000,
    private val defaultTopK: Int = 5,
    private val similarityThreshold: Float = 0.3f
) {

    private val memories = ConcurrentHashMap<String, MemorySnippet>()
    private val sessionIndex = ConcurrentHashMap<String, MutableList<String>>()  // sessionId -> memoryIds

    /**
     * 记录一段对话到记忆库
     */
    fun remember(sessionId: String, role: String, content: String, topic: String = "", importance: Float = 1.0f): MemorySnippet {
        val id = "mem_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val embedding = embed(content)
        val snippet = MemorySnippet(
            id = id,
            sessionId = sessionId,
            content = content,
            timestamp = System.currentTimeMillis(),
            role = role,
            topic = topic,
            importance = importance,
            embedding = embedding
        )
        memories[id] = snippet
        sessionIndex.computeIfAbsent(sessionId) { mutableListOf() }.add(id)

        // 限制大小，FIFO 淘汰
        if (memories.size > maxMemories) {
            val oldest = memories.entries.minByOrNull { it.value.timestamp }
            oldest?.let { (id, _) ->
                memories.remove(id)
                sessionIndex.values.forEach { list -> list.remove(id) }
            }
        }
        return snippet
    }

    /**
     * 检索相关记忆
     */
    fun retrieve(query: String, topK: Int = defaultTopK, excludeSessionId: String? = null): RetrievalResult {
        val start = System.currentTimeMillis()
        val queryEmbedding = embed(query)

        val candidates = memories.values
            .filter { excludeSessionId == null || it.sessionId != excludeSessionId }
            .map { snippet ->
                val sim = cosineSimilarity(queryEmbedding, snippet.embedding)
                snippet to sim
            }
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second * it.first.importance }  // 相似度 × 重要性
            .take(topK)
            .map { it.first }

        return RetrievalResult(
            snippets = candidates,
            query = query,
            totalSearched = memories.size,
            searchTimeMs = System.currentTimeMillis() - start
        )
    }

    /**
     * 按会话 ID 检索
     */
    fun getBySession(sessionId: String): List<MemorySnippet> {
        val ids = sessionIndex[sessionId] ?: return emptyList()
        return ids.mapNotNull { memories[it] }.sortedBy { it.timestamp }
    }

    /**
     * 按主题检索
     */
    fun getByTopic(topic: String, limit: Int = 10): List<MemorySnippet> {
        return memories.values
            .filter { it.topic.contains(topic, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /**
     * 生成相关历史 prompt 注入
     */
    fun generateRelatedHistoryPrompt(query: String, excludeSessionId: String? = null): String {
        val result = retrieve(query, topK = 3, excludeSessionId = excludeSessionId)
        if (result.snippets.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[相关历史记忆]")
        result.snippets.forEach { snippet ->
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(snippet.timestamp))
            sb.appendLine("- [$timeStr ${snippet.role}] ${snippet.content.take(200)}")
        }
        return sb.toString()
    }

    /**
     * 删除某个会话的所有记忆
     */
    fun forgetSession(sessionId: String): Int {
        val ids = sessionIndex.remove(sessionId) ?: return 0
        ids.forEach { memories.remove(it) }
        return ids.size
    }

    /**
     * 清空所有记忆
     */
    fun clearAll() {
        memories.clear()
        sessionIndex.clear()
    }

    /**
     * 获取统计
     */
    fun getStats(): MemoryStats {
        return MemoryStats(
            totalMemories = memories.size,
            totalSessions = sessionIndex.size,
            oldestMemory = memories.values.minOfOrNull { it.timestamp },
            newestMemory = memories.values.maxOfOrNull { it.timestamp }
        )
    }


    // ============ 向量化（简化实现：基于词频的稀疏向量）============

    private val vocabulary = ConcurrentHashMap<String, Int>()
    private var vocabSize = 0

    /**
     * 简化嵌入：基于词袋的稀疏向量
     * 生产环境应替换为真正的 embedding 模型
     */
    private fun embed(text: String): FloatArray {
        val tokens = tokenize(text)
        val dim = 256  // 固定维度
        val vec = FloatArray(dim)

        for (token in tokens) {
            val idx = vocabulary.computeIfAbsent(token) { vocabSize++ % dim }
            vec[idx] += 1.0f
        }

        // L2 归一化
        val norm = sqrt(vec.sumOf { it * it.toDouble() }).toFloat()
        if (norm > 0) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n\\r\\t]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .flatMap { token ->
                // 中文按 bigram 切分
                if (token.any { it.code in 0x4e00..0x9fff }) {
                    token.windowed(2, 1).filter { it.length == 2 }
                } else {
                    listOf(token)
                }
            }
    }

    private fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }
}
