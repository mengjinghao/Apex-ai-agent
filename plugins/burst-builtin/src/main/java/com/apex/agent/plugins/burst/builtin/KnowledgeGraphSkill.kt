package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 知识图谱技能
 * 实现实体关系管理、BFS搜索、路径查找、聚类分析
 */
class KnowledgeGraphSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val entities = ConcurrentHashMap<String, GraphEntity>()
    private val relations = ConcurrentHashMap<String, GraphRelation>()
    private val entitiesByType = ConcurrentHashMap<EntityType, MutableList<String>>()
    private val adjacencyList = ConcurrentHashMap<String, MutableList<Pair<String, RelationType>>>()
    private val reverseAdjacencyList = ConcurrentHashMap<String, MutableList<Pair<String, RelationType>>>()
    private val mutex = Mutex()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "knowledge_graph",
            skillName = "知识图谱",
            version = "1.0.0",
            description = "结构化知识管理和推理，支持实体关系图谱构建和语义搜索",
            author = "Apex Agent",
            tags = listOf("knowledge", "graph", "reasoning"),
            priority = 85,
            capabilities = listOf(
                "entity_management",
                "relation_inference",
                "path_finding",
                "cluster_analysis"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            val input = task.input.text ?: task.description
            
            // 从输入构建知识图谱
            val entityIds = buildFromText(input)
            
            // 执行查询
            val query = task.metadata["query"] ?: "knowledge"
            val results = queryGraph(query)
            
            // 获取统计信息
            val stats = getGraphStats()
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Knowledge graph analysis completed:
                    |- Entities created: ${entityIds.size}
                    |- Query results: ${results.size}
                    |- Total entities: ${stats.totalEntities}
                    |- Total relations: ${stats.totalRelations}
                    |- Average connections: ${stats.averageConnections}
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = entityIds.size
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    suspend fun addEntity(entity: GraphEntity): String = mutex.withLock {
        entities[entity.id] = entity
        entitiesByType.getOrPut(entity.type) { mutableListOf() }.add(entity.id)
        adjacencyList.getOrPut(entity.id) { mutableListOf() }
        reverseAdjacencyList.getOrPut(entity.id) { mutableListOf() }
        entity.id
    }
    
    suspend fun addRelation(relation: GraphRelation): String = mutex.withLock {
        if (!entities.containsKey(relation.sourceId) || !entities.containsKey(relation.targetId)) {
            throw IllegalArgumentException("Source or target entity not found")
        }
        relations[relation.id] = relation
        
        adjacencyList.getOrPut(relation.sourceId) { mutableListOf() }
            .add(Pair(relation.targetId, relation.type))
        reverseAdjacencyList.getOrPut(relation.targetId) { mutableListOf() }
            .add(Pair(relation.sourceId, relation.type))
        
        relation.id
    }
    
    fun buildFromText(text: String): List<String> = runBlocking(Dispatchers.IO) {
        val entityIds = mutableListOf<String>()
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        
        sentences.forEach { sentence ->
            val trimmed = sentence.trim()
            if (trimmed.length > 10) {
                val words = trimmed.split(Regex("\\s+"))
                val detectedType = detectEntityType(trimmed, words)
                val entity = GraphEntity(
                    type = detectedType,
                    name = trimmed.take(50),
                    description = trimmed
                )
                val id = addEntity(entity)
                entityIds.add(id)
            }
        }
        
        // 建立句子间关系
        for (i in 0 until entityIds.size - 1) {
            val sourceEntity = entities[entityIds[i]]
            val targetEntity = entities[entityIds[i + 1]]
            if (sourceEntity != null && targetEntity != null) {
                val relationType = inferRelationBetween(sourceEntity, targetEntity)
                try {
                    addRelation(GraphRelation(
                        sourceId = entityIds[i],
                        targetId = entityIds[i + 1],
                        type = relationType,
                        weight = 0.5f
                    ))
                } catch (_: IllegalArgumentException) { }
            }
        }
        
        entityIds
    }
    
    private fun detectEntityType(text: String, words: List<String>): EntityType {
        val lowerText = text.lowercase()
        return when {
            words.any { it.first().isUpperCase() } && text.contains("fun ") -> EntityType.CODE
            words.any { it in listOf("task", "process", "execute", "implement") } -> EntityType.TASK
            words.any { it in listOf("pattern", "template", "architecture") } -> EntityType.PATTERN
            words.any { it in listOf("learned", "experience", "knowledge") } -> EntityType.EXPERIENCE
            else -> EntityType.CONCEPT
        }
    }
    
    private fun inferRelationBetween(source: GraphEntity, target: GraphEntity): RelationType {
        return when {
            source.type == EntityType.CODE && target.type == EntityType.CODE -> RelationType.DEPENDS_ON
            source.type == EntityType.PATTERN && target.type == EntityType.CONCEPT -> RelationType.PART_OF
            source.type == EntityType.TASK && target.type == EntityType.CODE -> RelationType.USES
            source.type == EntityType.EXPERIENCE && target.type == EntityType.TASK -> RelationType.LEARNED_FROM
            else -> RelationType.SIMILAR_TO
        }
    }
    
    fun query(queryString: String): List<GraphEntity> {
        val lowerQuery = queryString.lowercase()
        return entities.values.filter { entity ->
            entity.name.lowercase().contains(lowerQuery) ||
            entity.description.lowercase().contains(lowerQuery) ||
            entity.properties.values.any { it.lowercase().contains(lowerQuery) }
        }
    }
    
    fun queryGraph(queryString: String): List<GraphEntity> = query(queryString)
    
    fun findPath(startId: String, endId: String): List<GraphEntity>? {
        if (!entities.containsKey(startId) || !entities.containsKey(endId)) {
            return null
        }
        
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<GraphEntity>>>()
        queue.add(Pair(startId, listOfNotNull(entities[startId])))
        
        while (queue.isNotEmpty()) {
            val (current, path) = queue.poll() ?: continue
            if (current == endId) {
                return path
            }
            if (visited.contains(current)) continue
            visited.add(current)
            
            adjacencyList[current]?.forEach { (neighbor, _) ->
                if (!visited.contains(neighbor)) {
                    entities[neighbor]?.let { entity ->
                        queue.add(Pair(neighbor, path + entity))
                    }
                }
            }
        }
        return null
    }
    
    fun findClusters(): List<List<GraphEntity>> {
        val visited = mutableSetOf<String>()
        val clusters = mutableListOf<List<GraphEntity>>()
        
        entities.keys.forEach { entityId ->
            if (!visited.contains(entityId)) {
                val cluster = bfsCluster(entityId, visited)
                if (cluster.size > 1) {
                    clusters.add(cluster)
                }
            }
        }
        
        return clusters
    }
    
    private fun bfsCluster(startId: String, visited: MutableSet<String>): List<GraphEntity> {
        val cluster = mutableListOf<GraphEntity>()
        val queue = ArrayDeque<String>()
        queue.add(startId)
        
        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: continue
            if (visited.contains(current)) continue
            visited.add(current)
            
            entities[current]?.let { cluster.add(it) }
            
            adjacencyList[current]?.forEach { (neighbor, _) ->
                if (!visited.contains(neighbor)) queue.add(neighbor)
            }
            reverseAdjacencyList[current]?.forEach { (neighbor, _) ->
                if (!visited.contains(neighbor)) queue.add(neighbor)
            }
        }
        return cluster
    }
    
    fun getCentralEntities(limit: Int): List<GraphEntity> {
        val centralityScores = mutableMapOf<String, Int>()
        
        entities.keys.forEach { id ->
            centralityScores[id] = (adjacencyList[id]?.size ?: 0) + (reverseAdjacencyList[id]?.size ?: 0)
        }
        
        return centralityScores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { entities[it.key] }
    }
    
    fun getGraphStats(): GraphStats {
        val entityTypeCounts = EntityType.entries.associateWith { type ->
            entitiesByType[type]?.size ?: 0
        }
        val relationTypeCounts = RelationType.entries.associateWith { type ->
            relations.values.count { it.type == type }
        }
        
        val totalConnections = relations.size
        val avgConnections = if (entities.isNotEmpty()) {
            totalConnections.toFloat() / entities.size
        } else 0f
        
        var maxConnections = 0
        entities.keys.forEach { id ->
            val connections = (adjacencyList[id]?.size ?: 0) + (reverseAdjacencyList[id]?.size ?: 0)
            if (connections > maxConnections) maxConnections = connections
        }
        
        return GraphStats(
            totalEntities = entities.size,
            totalRelations = relations.size,
            entityTypeCounts = entityTypeCounts,
            relationTypeCounts = relationTypeCounts,
            averageConnections = avgConnections,
            maxConnections = maxConnections
        )
    }
    
    fun clear() {
        entities.clear()
        relations.clear()
        entitiesByType.clear()
        adjacencyList.clear()
        reverseAdjacencyList.clear()
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.85f
    
    // 实体类型
    enum class EntityType {
        CONCEPT, CODE, TASK, PATTERN, EXPERIENCE
    }
    
    // 关系类型
    enum class RelationType {
        DEPENDS_ON, SIMILAR_TO, PART_OF, USES, LEARNED_FROM
    }
    
    // 图谱实体
    data class GraphEntity(
        val id: String = UUID.randomUUID().toString(),
        val type: EntityType,
        val name: String,
        val description: String = "",
        val properties: Map<String, String> = emptyMap(),
        val createdAt: Long = System.currentTimeMillis()
    )
    
    // 图谱关系
    data class GraphRelation(
        val id: String = UUID.randomUUID().toString(),
        val sourceId: String,
        val targetId: String,
        val type: RelationType,
        val weight: Float = 1.0f,
        val properties: Map<String, String> = emptyMap()
    )
    
    // 图谱统计
    data class GraphStats(
        val totalEntities: Int,
        val totalRelations: Int,
        val entityTypeCounts: Map<EntityType, Int>,
        val relationTypeCounts: Map<RelationType, Int>,
        val averageConnections: Float,
        val maxConnections: Int
    )
}
