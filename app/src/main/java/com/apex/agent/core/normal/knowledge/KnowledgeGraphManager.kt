package com.apex.agent.core.normal.knowledge

import java.util.concurrent.ConcurrentHashMap

/**
 * F22: 知识图谱构建与查询
 *
 * 从对话中自动构建个人知识图谱：
 * - 实体抽取（人/物/概念/事件）
 * - 关系抽取（A 是 B 的...）
 * - 图谱查询（最短路径/邻居/关联）
 * - 知识推理
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 共享 Agent 间知识
 * - 狂暴不构建长期知识
 * - 本功能是**用户个人知识库**，是单 Agent 长期记忆的高级形态
 */

/**
 * 知识节点
 */
data class KnowledgeNode(
    val id: String,
    val name: String,
    val type: EntityType,
    val aliases: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
    val firstMentionedAt: Long = System.currentTimeMillis(),
    val mentionCount: Int = 1,
    val confidence: Float = 1.0f
)

enum class EntityType {
    PERSON,       // 人
    ORGANIZATION, // 组织
    LOCATION,     // 地点
    CONCEPT,      // 概念
    TECHNOLOGY,   // 技术
    PRODUCT,      // 产品
    EVENT,        // 事件
    DATE,         // 日期
    DOCUMENT,     // 文档
    PROJECT,      // 项目
    SKILL,        // 技能
    OTHER
}

/**
 * 知识边（关系）
 */
data class KnowledgeEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: RelationType,
    val weight: Float = 1.0f,
    val properties: Map<String, String> = emptyMap(),
    val firstMentionedAt: Long = System.currentTimeMillis(),
    val mentionCount: Int = 1
)

enum class RelationType {
    IS_A,              // 是一种
    PART_OF,           // ...的一部分
    WORKS_AT,          // 在...工作
    KNOWS,             // 认识
    CREATED,           // 创建了
    USES,              // 使用
    DEPENDS_ON,        // 依赖
    RELATED_TO,        // 相关
    LOCATED_IN,        // 位于
    HAPPENED_ON,       // 发生于
    MEMBER_OF,         // ...的成员
    OWNER_OF,          // 拥有
    SIMILAR_TO,        // 类似于
    PARENT_OF,         // ...的父级
    CUSTOM             // 自定义
}

/**
 * 知识图谱
 */
data class KnowledgeGraph(
    val nodes: Map<String, KnowledgeNode>,
    val edges: List<KnowledgeEdge>,
    val stats: GraphStats
) {
    data class GraphStats(
        val totalNodes: Int,
        val totalEdges: Int,
        val nodesByType: Map<EntityType, Int>,
        val edgesByRelation: Map<RelationType, Int>,
        val avgConnections: Float
    )
}

/**
 * 图谱查询结果
 */
data class GraphQueryResult(
    val query: String,
    val matchedNodes: List<KnowledgeNode>,
    val matchedEdges: List<KnowledgeEdge>,
    val paths: List<GraphPath>
)

data class GraphPath(
    val nodes: List<KnowledgeNode>,
    val edges: List<KnowledgeEdge>,
    val totalWeight: Float
)

/**
 * 知识图谱管理器
 */
class KnowledgeGraphManager {

    private val nodes = ConcurrentHashMap<String, KnowledgeNode>()
    private val edges = ConcurrentHashMap<String, KnowledgeEdge>()
    private val nameToId = ConcurrentHashMap<String, String>()  // name/alias -> id
    private val adjacency = ConcurrentHashMap<String, MutableSet<String>>()  // nodeId -> edgeIds

    /**
     * 从文本中抽取知识
     */
    fun extractFromText(text: String, chatId: String? = null): ExtractionResult {
        val extractedNodes = mutableListOf<KnowledgeNode>()
        val extractedEdges = mutableListOf<KnowledgeEdge>()

        // 1. 实体抽取（基于规则）
        val entities = extractEntities(text)
        for ((name, type) in entities) {
            val node = addOrUpdateNode(name, type)
            extractedNodes.add(node)
        }

        // 2. 关系抽取（基于模式）
        val relations = extractRelations(text, entities)
        for ((source, relation, target) in relations) {
            val sourceNode = findOrCreateNode(source)
            val targetNode = findOrCreateNode(target)
            val edge = addOrUpdateEdge(sourceNode.id, targetNode.id, relation)
            extractedEdges.add(edge)
        }

        return ExtractionResult(
            text = text,
            extractedNodes = extractedNodes,
            extractedEdges = extractedEdges,
            chatId = chatId
        )
    }

    /**
     * 批量抽取
     */
    fun extractFromMessages(messages: List<Pair<String, String>>): List<ExtractionResult> {
        return messages.map { (role, content) -> extractFromText(content) }
    }

    /**
     * 添加或更新节点
     */
    fun addOrUpdateNode(name: String, type: EntityType, properties: Map<String, String> = emptyMap()): KnowledgeNode {
        val existingId = nameToId[name.lowercase()]
        if (existingId != null) {
            val existing = nodes[existingId]!!
            val updated = existing.copy(
                mentionCount = existing.mentionCount + 1,
                properties = existing.properties + properties,
                confidence = (existing.confidence + 0.1f).coerceAtMost(1f)
            )
            nodes[existingId] = updated
            return updated
        }

        val id = "node_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val node = KnowledgeNode(
            id = id,
            name = name,
            type = type,
            properties = properties
        )
        nodes[id] = node
        nameToId[name.lowercase()] = id
        return node
    }

    /**
     * 添加或更新边
     */
    fun addOrUpdateEdge(sourceId: String, targetId: String, relation: RelationType): KnowledgeEdge {
        // 查找是否已存在
        val existing = edges.values.find {
            it.sourceId == sourceId && it.targetId == targetId && it.relation == relation
        }
        if (existing != null) {
            val updated = existing.copy(
                mentionCount = existing.mentionCount + 1,
                weight = existing.weight + 0.1f
            )
            edges[existing.id] = updated
            return updated
        }

        val id = "edge_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val edge = KnowledgeEdge(id, sourceId, targetId, relation)
        edges[id] = edge
        adjacency.computeIfAbsent(sourceId) { mutableSetOf() }.add(id)
        adjacency.computeIfAbsent(targetId) { mutableSetOf() }.add(id)
        return edge
    }

    /**
     * 查询节点
     */
    fun findNode(name: String): KnowledgeNode? {
        val id = nameToId[name.lowercase()] ?: return null
        return nodes[id]
    }

    /**
     * 搜索节点
     */
    fun searchNodes(query: String, type: EntityType? = null): List<KnowledgeNode> {
        val q = query.lowercase()
        return nodes.values
            .filter { node ->
                (node.name.contains(query, ignoreCase = true) ||
                 node.aliases.any { it.contains(query, ignoreCase = true) }) &&
                (type == null || node.type == type)
            }
            .sortedByDescending { it.mentionCount }
            .toList()
    }

    /**
     * 获取节点的所有关系
     */
    fun getRelations(nodeId: String): List<KnowledgeEdge> {
        val edgeIds = adjacency[nodeId] ?: return emptyList()
        return edgeIds.mapNotNull { edges[it] }
    }

    /**
     * 查找两个节点间的路径（BFS）
     */
    fun findPath(fromId: String, toId: String, maxDepth: Int = 5): GraphPath? {
        if (fromId == toId) {
            val node = nodes[fromId] ?: return null
            return GraphPath(listOf(node), emptyList(), 0f)
        }

        val visited = mutableSetOf(fromId)
        val queue = ArrayDeque<PathSearchState>()
        queue.add(PathSearchState(fromId, listOf(fromId), emptyList(), 0f))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            if (state.path.size > maxDepth) continue

            val edgeIds = adjacency[state.current] ?: continue
            for (edgeId in edgeIds) {
                val edge = edges[edgeId] ?: continue
                val nextNode = if (edge.sourceId == state.current) edge.targetId else edge.sourceId
                if (nextNode in visited) continue

                val newPath = state.path + nextNode
                val newEdges = state.edges + edge
                val newWeight = state.totalWeight + edge.weight

                if (nextNode == toId) {
                    val pathNodes = newPath.mapNotNull { nodes[it] }
                    return GraphPath(pathNodes, newEdges, newWeight)
                }

                visited.add(nextNode)
                queue.add(PathSearchState(nextNode, newPath, newEdges, newWeight))
            }
        }
        return null
    }

    /**
     * 获取子图
     */
    fun getSubgraph(nodeId: String, depth: Int = 2): KnowledgeGraph {
        val visited = mutableSetOf<String>()
        val subNodes = mutableMapOf<String, KnowledgeNode>()
        val subEdges = mutableListOf<KnowledgeEdge>()

        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(nodeId to 0)
        visited.add(nodeId)

        while (queue.isNotEmpty()) {
            val (current, d) = queue.removeFirst()
            if (d > depth) continue

            nodes[current]?.let { subNodes[current] = it }
            val edgeIds = adjacency[current] ?: continue
            for (edgeId in edgeIds) {
                val edge = edges[edgeId] ?: continue
                if (edge.id !in subEdges.map { it.id }) subEdges.add(edge)
                val nextNode = if (edge.sourceId == current) edge.targetId else edge.sourceId
                if (nextNode !in visited) {
                    visited.add(nextNode)
                    queue.add(nextNode to d + 1)
                }
            }
        }

        return KnowledgeGraph(subNodes, subEdges, computeStats(subNodes.values.toList(), subEdges))
    }

    /**
     * 获取完整图谱
     */
    fun getFullGraph(): KnowledgeGraph {
        return KnowledgeGraph(
            nodes = nodes.toMap(),
            edges = edges.values.toList(),
            stats = computeStats(nodes.values.toList(), edges.values.toList())
        )
    }

    /**
     * 生成知识图谱 prompt 注入
     */
    fun generateKnowledgePrompt(query: String): String {
        val relevantNodes = searchNodes(query).take(5)
        if (relevantNodes.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[相关知识]")
        for (node in relevantNodes) {
            sb.appendLine("- ${node.name} (${node.type})")
            val relations = getRelations(node.id).take(3)
            for (rel in relations) {
                val otherId = if (rel.sourceId == node.id) rel.targetId else rel.sourceId
                val other = nodes[otherId]
                if (other != null) {
                    val direction = if (rel.sourceId == node.id) "→" else "←"
                    sb.appendLine("  $direction ${rel.relation} → ${other.name}")
                }
            }
        }
        return sb.toString()
    }

    // ============ 实体抽取 ============

    private fun extractEntities(text: String): List<Pair<String, EntityType>> {
        val entities = mutableListOf<Pair<String, EntityType>>()

        // 人名（简化：大写英文姓名 或 中文2-3字+说/表示）
        Regex("([A-Z][a-z]+ [A-Z][a-z]+)").findAll(text)
            .map { it.value to EntityType.PERSON }
            .toList().let { entities.addAll(it) }

        Regex("([\\u4e00-\\u9fa5]{2,3})(?:说|表示|认为|提出|建议|告诉)").findAll(text)
            .map { it.groupValues[1] to EntityType.PERSON }
            .toList().let { entities.addAll(it) }

        // 组织（公司/团队后缀）
        Regex("([\\u4e00-\\u9fa5A-Za-z]+(?:公司|团队|组织|集团|实验室|机构|Inc|Corp|LLC|Ltd))").findAll(text)
            .map { it.value to EntityType.ORGANIZATION }
            .toList().let { entities.addAll(it) }

        // 技术名词
        Regex("\\b(Python|Kotlin|Java|JavaScript|TypeScript|React|Vue|Angular|Android|iOS|Docker|Kubernetes|TensorFlow|PyTorch|GPT|BERT)\\b").findAll(text)
            .map { it.value to EntityType.TECHNOLOGY }
            .toList().let { entities.addAll(it) }

        // 日期
        Regex("(\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}-\\d{2}-\\d{2}|明天|后天|今天|昨天)").findAll(text)
            .map { it.value to EntityType.DATE }
            .toList().let { entities.addAll(it) }

        // 地点（简化：含"市"/"省"/"国家"后缀）
        Regex("([\\u4e00-\\u9fa5]{2,5}(?:市|省|国家|区|县))").findAll(text)
            .map { it.value to EntityType.LOCATION }
            .toList().let { entities.addAll(it) }

        return entities.distinctBy { it.first }
    }

    private fun extractRelations(text: String, entities: List<Pair<String, EntityType>>): List<Triple<String, RelationType, String>> {
        val relations = mutableListOf<Triple<String, RelationType, String>>()

        // 模式匹配
        val patterns = mapOf(
            RelationType.WORKS_AT to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:在|就职于|工作于)\\s*([\\u4e00-\\u9fa5A-Za-z]+公司|团队|组织)"),
            RelationType.CREATED to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:创建了|发明了|开发了|写了)\\s*([\\u4e00-\\u9fa5A-Za-z]+)"),
            RelationType.USES to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:使用|用|采用)\\s*([\\u4e00-\\u9fa5A-Za-z]+)"),
            RelationType.IS_A to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:是一种|是一个|是)\\s*([\\u4e00-\\u9fa5A-Za-z]+)"),
            RelationType.PART_OF to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:属于|是|的一部分)\\s*([\\u4e00-\\u9fa5A-Za-z]+)"),
            RelationType.LOCATED_IN to Regex("([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:位于|在)\\s*([\\u4e00-\\u9fa5A-Za-z]+)")
        )

        for ((relation, regex) in patterns) {
            regex.findAll(text).forEach { match ->
                val source = match.groupValues[1]
                val target = match.groupValues[2]
                if (source.isNotBlank() && target.isNotBlank()) {
                    relations.add(Triple(source, relation, target))
                }
            }
        }

        return relations
    }

    private fun findOrCreateNode(name: String): KnowledgeNode {
        val id = nameToId[name.lowercase()]
        if (id != null) return nodes[id]!!
        return addOrUpdateNode(name, EntityType.OTHER)
    }

    private fun computeStats(nodeList: List<KnowledgeNode>, edgeList: List<KnowledgeEdge>): KnowledgeGraph.GraphStats {
        return KnowledgeGraph.GraphStats(
            totalNodes = nodeList.size,
            totalEdges = edgeList.size,
            nodesByType = nodeList.groupingBy { it.type }.eachCount(),
            edgesByRelation = edgeList.groupingBy { it.relation }.eachCount(),
            avgConnections = if (nodeList.isNotEmpty()) edgeList.size.toFloat() * 2 / nodeList.size else 0f
        )
    }

    private data class PathSearchState(
        val current: String,
        val path: List<String>,
        val edges: List<KnowledgeEdge>,
        val totalWeight: Float
    )

    data class ExtractionResult(
        val text: String,
        val extractedNodes: List<KnowledgeNode>,
        val extractedEdges: List<KnowledgeEdge>,
        val chatId: String?
    )
}
