package com.apex.agent.core.multiagent

import android.content.Context
import android.util.Base64
import com.apex.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cosineSimilarity

class KnowledgeGraphManager(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeGraphManager"
        private const val GRAPH_DB_NAME = "knowledge_graph_db"
        private const val VECTOR_INDEX_NAME = "vector_index_db"
        private const val MAX_EMBEDDING_DIM = 384
        private const val SIMILARITY_THRESHOLD = 0.75
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val nodes = ConcurrentHashMap<String, KnowledgeNode>()
    private val edges = ConcurrentHashMap<String, MutableList<KnowledgeEdge>>()
    private val vectorIndex = ConcurrentHashMap<String, floatArray>()
    private val agentMemories = ConcurrentHashMap<String, MutableMap<String, LongTermMemory>>()

    private val _graphStats = MutableStateFlow(GraphStats())
    val graphStats: StateFlow<GraphStats> = _graphStats

    private val prefs = context.getSharedPreferences(GRAPH_DB_NAME, Context.MODE_PRIVATE)

    init {
        loadFromDisk()
    }

    data class KnowledgeNode(
        val id: String,
        val type: NodeType,
        val content: String,
        val embedding: floatArray,
        val properties: MutableMap<String, String> = mutableMapOf(),
        var confidence: Float = 1.0f,
        val createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis(),
        val createdBy: String = "system"
    ) {
        enum class NodeType {
            AGENT, TASK, CONCEPT, FACT, RULE, PATTERN, MEMORY, TOOL, USER, SESSION
        }

        override fun equals(other: Any): Boolean {
            if (this === other) return true
            if (other !is KnowledgeNode) return false
            return id == other.id
        }

        override fun hashCode(): Int = id.hashCode()
    }

    data class KnowledgeEdge(
        val id: String,
        val sourceId: String,
        val targetId: String,
        val relationType: RelationType,
        val weight: Float = 1.0f,
        val properties: MutableMap<String, String> = mutableMapOf(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        enum class RelationType {
            KNOWS, PARTICIPATED_IN, RESULTED_IN, SUBTASK_OF, SIMILAR_TO,
            DEPENDS_ON, ENHANCES, CONFLICTS_WITH, DERIVED_FROM, INSTANCE_OF
        }
    }

    data class GraphStats(
        val totalNodes: Int = 0,
        val totalEdges: Int = 0,
        val nodeTypes: Map<String, Int> = emptyMap(),
        val avgConnections: Float = 0f
    )

    data class SearchResult(
        val node: KnowledgeNode,
        val similarity: Float,
        val path: List<String> = emptyList()
    )

    data class LongTermMemory(
        val sessionId: String,
        val agentId: String,
        val keyInsights: MutableList<String> = mutableListOf(),
        val learnedPatterns: MutableList<LearnedPattern> = mutableListOf(),
        val performanceHistory: MutableList<PerformanceRecord> = mutableListOf(),
        val preferences: MutableMap<String, Any> = mutableMapOf(),
        val lastAccessTime: Long = System.currentTimeMillis(),
        val accessCount: Int = 0
    )

    data class LearnedPattern(
        val patternId: String,
        val description: String,
        val occurrences: Int = 1,
        val successRate: Float = 1.0f,
        val context: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PerformanceRecord(
        val taskId: String,
        val taskType: String,
        val success: Boolean,
        val quality: Float,
        val duration: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addNode(node: KnowledgeNode): Boolean {
        return try {
            nodes[node.id] = node
            vectorIndex[node.id] = node.embedding
            saveToDisk()
            updateStats()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "addNode failed", e)
            false
        }
    }

    fun addEdge(edge: KnowledgeEdge): Boolean {
        return try {
            val edgeList = edges.getOrPut(edge.sourceId) { mutableListOf() }
            edgeList.removeAll { it.id == edge.id }
            edgeList.add(edge)
            saveToDisk()
            updateStats()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "addEdge failed", e)
            false
        }
    }

    fun getNode(nodeId: String): KnowledgeNode? = nodes[nodeId]

    fun getNeighbors(nodeId: String, maxDepth: Int = 1): List<Pair<KnowledgeNode, KnowledgeEdge>> {
        val result = mutableListOf<Pair<KnowledgeNode, KnowledgeEdge>>()
        val visited = mutableSetOf<String>()

        fun traverse(currentId: String, depth: Int) {
            if (depth > maxDepth || visited.contains(currentId)) return
            visited.add(currentId)

            edges[currentId]?.forEach { edge ->
                nodes[edge.targetId]?.let { neighbor ->
                    result.add(neighbor to edge)
                    traverse(edge.targetId, depth + 1)
                }
            }
        }

        traverse(nodeId, 0)
        return result
    }

    fun semanticSearch(query: String, topK: Int = 10): List<SearchResult> {
        val queryEmbedding = generateEmbedding(query)

        return nodes.values
            .map { node ->
                val similarity = cosineSimilarity(queryEmbedding, node.embedding)
                SearchResult(node, similarity)
            }
            .filter { it.similarity >= SIMILARITY_THRESHOLD }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    fun findPath(startId: String, endId: String): List<String>? {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<String>>>()

        queue.add(startId to listOf(startId))

        while (queue.isNotEmpty()) {
            val (current, path) = queue.poll()

            if (current == endId) return path

            if (visited.contains(current)) continue
            visited.add(current)

            edges[current]?.forEach { edge ->
                if (!visited.contains(edge.targetId)) {
                    queue.add(edge.targetId to (path + edge.targetId))
                }
            }
        }

        return null
    }

    fun storeAgentMemory(agentId: String, sessionId: String, memory: LongTermMemory) {
        val agentMap = agentMemories.getOrPut(agentId) { mutableMapOf() }
        agentMap[sessionId] = memory
        saveToDisk()
    }

    fun getAgentMemory(agentId: String, sessionId: String): LongTermMemory? {
        val agentMap = agentMemories[agentId] ?: return null
        val memory = agentMap[sessionId]
        memory?.let {
            agentMap[sessionId] = it.copy(lastAccessTime = System.currentTimeMillis(), accessCount = it.accessCount + 1)
        }
        return memory
    }

    fun extractInsights(taskId: String, result: String, context: Map<String, Any>) {
        scope.launch {
            val insight = extractKeyInsight(result)
            if (insight.isNotBlank()) {
                val pattern = LearnedPattern(
                    patternId = UUID.randomUUID().toString(),
                    description = insight,
                    context = context.toString(),
                    occurrences = 1
                )

                val agentId = context["agentId"] as? String ?: return@launch
                val sessionId = context["sessionId"] as? String ?: return@launch

                agentMemories[agentId]?.get(sessionId)?.let { memory ->
                    memory.learnedPatterns.add(pattern)
                    memory.keyInsights.add(insight)

                    val newSuccess = context["success"] as? Boolean ?: true
                    val quality = (context["quality"] as? Float) ?: 0.5f
                    val duration = (context["duration"] as? Long) ?: 0L

                    memory.performanceHistory.add(PerformanceRecord(
                        taskId = taskId,
                        taskType = context["taskType"] as? String ?: "unknown",
                        success = newSuccess,
                        quality = quality,
                        duration = duration
                    ))

                    memory.accessCount++
                }
            }
        }
    }

    fun inferNewRelations() {
        scope.launch {
            val conceptNodes = nodes.values.filter { it.type == KnowledgeNode.NodeType.CONCEPT }

            conceptNodes.forEach { concept1 ->
                conceptNodes.forEach { concept2 ->
                    if (concept1.id != concept2.id) {
                        val similarity = cosineSimilarity(concept1.embedding, concept2.embedding)
                        if (similarity > 0.8f && !hasRelation(concept1.id, concept2.id)) {
                            addEdge(KnowledgeEdge(
                                id = UUID.randomUUID().toString(),
                                sourceId = concept1.id,
                                targetId = concept2.id,
                                relationType = KnowledgeEdge.RelationType.SIMILAR_TO,
                                weight = similarity
                            ))
                        }
                    }
                }
            }
        }
    }

    fun mergeDuplicateNodes(candidateIds: List<String>) {
        if (candidateIds.size < 2) return

        val primaryNode = nodes[candidateIds.first()] ?: return
        val allNeighbors = mutableSetOf<KnowledgeNode>()
        val allEdges = mutableListOf<KnowledgeEdge>()

        candidateIds.drop(1).forEach { nodeId ->
            nodes[nodeId]?.let { node ->
                allNeighbors.addAll(getNeighbors(nodeId).map { it.first })
            }
            edges.remove(nodeId)?.let { nodeEdges ->
                allEdges.addAll(nodeEdges.map { it.copy(sourceId = primaryNode.id) })
            }
            nodes.remove(nodeId)
        }

        allNeighbors.forEach { neighbor ->
            if (!hasRelation(primaryNode.id, neighbor.id)) {
                addEdge(KnowledgeEdge(
                    id = UUID.randomUUID().toString(),
                    sourceId = primaryNode.id,
                    targetId = neighbor.id,
                    relationType = KnowledgeEdge.RelationType.SIMILAR_TO
                ))
            }
        }

        saveToDisk()
        updateStats()
    }

    private fun hasRelation(sourceId: String, targetId: String): Boolean {
        return edges[sourceId]?.any { it.targetId == targetId } == true
    }

    private fun generateEmbedding(text: String): floatArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val embedding = floatArrayOf()

        for (i in 0 until minOf(hash.size, MAX_EMBEDDING_DIM)) {
            val dimValue = (hash[i].toInt() and 0xFF) / 255.0f * 2.0f - 1.0f
            embedding.plus(dimValue)
        }

        val result = FloatArray(MAX_EMBEDDING_DIM)
        for (i in hash.indices) {
            result[i % MAX_EMBEDDING_DIM] += (hash[i].toInt() and 0xFF) / 255.0f * 0.1f
        }

        return normalize(result)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val magnitude = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return if (magnitude > 0) vector.map { it / magnitude }.toFloatArray() else vector
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val maxLen = maxOf(a.size, b.size)
        val aPadded = a.copyOf(maxLen)
        val bPadded = b.copyOf(maxLen)

        var dotProduct = 0.0
        var magnitudeA = 0.0
        var magnitudeB = 0.0

        for (i in 0 until maxLen) {
            dotProduct += aPadded[i] * bPadded[i]
            magnitudeA += aPadded[i] * aPadded[i]
            magnitudeB += bPadded[i] * bPadded[i]
        }

        val denom = kotlin.math.sqrt(magnitudeA) * kotlin.math.sqrt(magnitudeB)
        return if (denom > 0) (dotProduct / denom).toFloat() else 0f
    }

    private fun extractKeyInsight(text: String): String {
        val importantKeywords = listOf("发现", "学习", "优化", "改进", "成功", "失败", "规律", "模式", "解决方案")
        val sentences = text.split("。|！|？|\n".toRegex())

        return sentences
            .map { it.trim() }
            .filter { it.length in 10..200 }
            .maxByOrNull { sentence ->
                importantKeywords.count { sentence.contains(it) }
            } ?: ""
    }

    private fun updateStats() {
        val nodeTypeCount = nodes.values.groupingBy { it.type.name }.eachCount()
        val totalEdges = edges.values.sumOf { it.size }

        _graphStats.value = GraphStats(
            totalNodes = nodes.size,
            totalEdges = totalEdges,
            nodeTypes = nodeTypeCount,
            avgConnections = if (nodes.isNotEmpty()) totalEdges.toFloat() / nodes.size else 0f
        )
    }

    private fun saveToDisk() {
        scope.launch {
            try {
                val nodeJson = gson.toJson(nodes.values.toList())
                val edgeJson = gson.toJson(edges.mapValues { it.value.toList() })
                val memoryJson = gson.toJson(agentMemories.mapValues { it.value.toList() })

                prefs.edit()
                    .putString("nodes", Base64.encodeToString(nodeJson.toByteArray(), Base64.DEFAULT))
                    .putString("edges", Base64.encodeToString(edgeJson.toByteArray(), Base64.DEFAULT))
                    .putString("memories", Base64.encodeToString(memoryJson.toByteArray(), Base64.DEFAULT))
                    .apply()
            } catch (e: Exception) {
                AppLogger.e(TAG, "saveToDisk failed", e)
            }
        }
    }

    private fun loadFromDisk() {
        try {
            val nodeJson = prefs.getString("nodes", null)
            val edgeJson = prefs.getString("edges", null)
            val memoryJson = prefs.getString("memories", null)

            nodeJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<List<KnowledgeNode>>() {}.type
                val nodeList: List<KnowledgeNode> = gson.fromJson(json, type)
                nodeList.forEach { node -> nodes[node.id] = node }
            }

            edgeJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<Map<String, List<KnowledgeEdge>>>() {}.type
                val edgeMap: Map<String, List<KnowledgeEdge>> = gson.fromJson(json, type)
                edgeMap.forEach { (k, v) -> edges[k] = v.toMutableList() }
            }

            memoryJson?.let {
                val json = String(Base64.decode(it, Base64.DEFAULT))
                val type = object : TypeToken<Map<String, Map<String, LongTermMemory>>>() {}.type
                val memoryMap: Map<String, Map<String, LongTermMemory>> = gson.fromJson(json, type)
                memoryMap.forEach { (k, v) -> agentMemories[k] = v.toMutableMap() }
            }

            nodes.values.forEach { vectorIndex[it.id] = it.embedding }
            updateStats()
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadFromDisk failed", e)
        }
    }

    fun clearKnowledge() {
        nodes.clear()
        edges.clear()
        vectorIndex.clear()
        agentMemories.clear()
        prefs.edit().clear().apply()
        updateStats()
    }

    fun exportGraph(): String {
        return gson.toJson(mapOf(
            "nodes" to nodes.values.toList(),
            "edges" to edges.mapValues { it.value.toList() },
            "stats" to _graphStats.value
        ))
    }

    fun shutdown() {
        scope.cancel()
    }
}

class VectorStore(private val dimension: Int = 384) {

    private val vectors = ConcurrentHashMap<String, floatArray>()
    private val metadata = ConcurrentHashMap<String, MutableMap<String, Any>>()

    fun insert(id: String, vector: FloatArray, meta: Map<String, Any> = emptyMap()) {
        vectors[id] = normalize(vector)
        metadata[id] = meta.toMutableMap()
    }

    fun search(query: floatArray, topK: Int = 10): List<Pair<String, Float>> {
        val normalizedQuery = normalize(query)

        return vectors.map { (id, vector) ->
            id to cosineSim(normalizedQuery, vector)
        }
            .sortedByDescending { it.second }
            .take(topK)
    }

    fun get(id: String): floatArray? = vectors[id]

    fun getMetadata(id: String): Map<String, Any>? = metadata[id]

    fun delete(id: String) {
        vectors.remove(id)
        metadata.remove(id)
    }

    fun size(): Int = vectors.size

    private fun normalize(v: FloatArray): FloatArray {
        val mag = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (mag > 0) v.map { it / mag }.toFloatArray() else v
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(magA) * kotlin.math.sqrt(magB)
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }
}
