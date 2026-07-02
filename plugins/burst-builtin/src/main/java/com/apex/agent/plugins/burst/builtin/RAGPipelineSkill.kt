package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * RAG管道技能
 * 实现检索增强生成、向量存储、嵌入生成
 */
class RAGPipelineSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _ragState = MutableStateFlow(RAGState())
    val ragState: StateFlow<RAGState> = _ragState.asStateFlow()
    
    private val vectorStore = ConcurrentHashMap<String, MutableList<VectorEntry>>()
    private val collections = ConcurrentHashMap<String, CollectionInfo>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "rag_pipeline",
            skillName = "RAG管道",
            version = "1.0.0",
            description = "检索增强生成管道，支持向量存储、嵌入生成和智能检索",
            author = "Apex Agent",
            tags = listOf("rag", "vector", "retrieval"),
            priority = 85,
            capabilities = listOf(
                "vector_storage",
                "embedding_generation",
                "semantic_search",
                "context_building"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "query"
            
            when (operation) {
                "query" -> {
                    val query = task.input.text ?: task.description
                    val collectionName = task.metadata["collection"] ?: "default"
                    val topK = task.metadata["topK"]?.toIntOrNull() ?: 5
                    
                    val result = query(query, collectionName, topK)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = result.success,
                        output = """
                            |RAG query completed:
                            |- Query: ${query.take(50)}...
                            |- Collection: $collectionName
                            |- Retrieved: ${result.retrievedCount} documents
                            |- Sources: ${result.sources.size}
                            |- Retrieval time: ${result.retrievalTimeMs}ms
                            |- Generation time: ${result.generationTimeMs}ms
                            ${if (!result.success) "- Error: ${result.errorMessage}" else ""}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = result.retrievedCount
                        )
                    )
                }
                "add" -> {
                    val text = task.input.text ?: ""
                    val collectionName = task.metadata["collection"] ?: "default"
                    
                    val embedding = generateEmbedding(text)
                    val id = insert(collectionName, text, embedding)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Document added to RAG:
                            |- Collection: $collectionName
                            |- Document ID: $id
                            |- Text length: ${text.length}
                            |- Embedding dimensions: ${embedding.size}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                else -> {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "Unknown operation: $operation"
                    )
                }
            }
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    suspend fun query(
        query: String,
        collectionName: String,
        topK: Int = 5
    ): RAGResult = withContext(Dispatchers.Default) {
        _ragState.value = RAGState(status = RAGStatus.RETRIEVING)
        
        try {
            val startTime = System.currentTimeMillis()
            
            val queryEmbedding = generateEmbedding(query)
            val embeddingTime = System.currentTimeMillis() - startTime
            
            val retrieved = searchSimilar(collectionName, queryEmbedding, topK)
            val retrievalTime = System.currentTimeMillis() - startTime - embeddingTime
            
            if (retrieved.isEmpty()) {
                _ragState.value = RAGState(status = RAGStatus.NO_RESULTS)
                return@withContext RAGResult(
                    success = true,
                    answer = "没有找到相关信息",
                    sources = emptyList(),
                    retrievedCount = 0,
                    retrievalTimeMs = retrievalTime
                )
            }
            
            _ragState.value = RAGState(
                status = RAGStatus.GENERATING,
                retrievedCount = retrieved.size
            )
            
            val context = buildContext(retrieved)
            val answer = generateAnswer(query, context)
            val generationTime = System.currentTimeMillis() - startTime - retrievalTime
            
            _ragState.value = RAGState(
                status = RAGStatus.COMPLETED,
                retrievedCount = retrieved.size,
                totalTimeMs = System.currentTimeMillis() - startTime
            )
            
            RAGResult(
                success = true,
                answer = answer,
                sources = retrieved.map {
                    Source(
                        id = it.id,
                        text = it.text.take(100) + "...",
                        similarity = it.similarity
                    )
                },
                retrievedCount = retrieved.size,
                retrievalTimeMs = retrievalTime,
                generationTimeMs = generationTime
            )
        } catch (e: Exception) {
            _ragState.value = RAGState(status = RAGStatus.ERROR)
            RAGResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    fun insert(collectionName: String, text: String, embedding: List<Float>): Long {
        val collection = vectorStore.getOrPut(collectionName) { mutableListOf() }
        val id = collection.size.toLong()
        
        collection.add(VectorEntry(
            id = id,
            text = text,
            embedding = embedding,
            metadata = emptyMap()
        ))
        
        // 更新集合信息
        collections[collectionName] = CollectionInfo(
            name = collectionName,
            documentCount = collection.size,
            lastUpdated = System.currentTimeMillis()
        )
        
        return id
    }
    
    private fun generateEmbedding(text: String): List<Float> {
        // 模拟嵌入生成：实际应该调用嵌入模型
        val dimensions = 128
        return List(dimensions) { (text.hashCode() % 100 + it) / 100f }
    }
    
    private fun searchSimilar(
        collectionName: String,
        queryEmbedding: List<Float>,
        topK: Int
    ): List<VectorEntry> {
        val collection = vectorStore[collectionName] ?: return emptyList()
        
        return collection
            .map { entry ->
                val similarity = calculateSimilarity(queryEmbedding, entry.embedding)
                entry.copy(similarity = similarity)
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }
    
    private fun calculateSimilarity(embedding1: List<Float>, embedding2: List<Float>): Float {
        if (embedding1.size != embedding2.size) return 0f
        
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }
        
        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
        } else 0f
    }
    
    private fun buildContext(entries: List<VectorEntry>): String {
        return entries.joinToString("\n\n") { it.text }
    }
    
    private fun generateAnswer(query: String, context: String): String {
        // 模拟答案生成：实际应该调用LLM
        return "基于检索到的信息，回答如下：$query\n\n相关上下文：${context.take(200)}..."
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        vectorStore.clear()
        collections.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.86f
    
    data class VectorEntry(
        val id: Long,
        val text: String,
        val embedding: List<Float>,
        val metadata: Map<String, String>,
        val similarity: Float = 0f
    )
    
    data class CollectionInfo(
        val name: String,
        val documentCount: Int,
        val lastUpdated: Long
    )
    
    data class RAGState(
        val status: RAGStatus = RAGStatus.IDLE,
        val retrievedCount: Int = 0,
        val totalTimeMs: Long = 0
    )
    
    enum class RAGStatus {
        IDLE, RETRIEVING, GENERATING, COMPLETED, NO_RESULTS, ERROR
    }
    
    data class RAGResult(
        val success: Boolean,
        val answer: String = "",
        val sources: List<Source> = emptyList(),
        val retrievedCount: Int = 0,
        val retrievalTimeMs: Long = 0,
        val generationTimeMs: Long = 0,
        val errorMessage: String? = null
    )
    
    data class Source(
        val id: Long,
        val text: String,
        val similarity: Float
    )
}