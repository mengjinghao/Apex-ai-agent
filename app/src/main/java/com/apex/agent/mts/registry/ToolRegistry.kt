package com.apex.agent.mts.registry

import com.apex.agent.mts.schema.ScoredTool
import com.apex.agent.mts.schema.ToolCategory
import com.apex.agent.mts.schema.ToolSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface ToolRegistryListener {
    fun onToolRegistered(tool: ToolSpec)
    fun onToolUnregistered(toolId: String)
}

class ToolRegistry {
    private val toolsById = ConcurrentHashMap<String, ToolSpec>()
    private val toolsByName = ConcurrentHashMap<String, ToolSpec>()
    private val toolsByCategory = ConcurrentHashMap<String, MutableList<ToolSpec>>()
    private val toolsByTag = ConcurrentHashMap<String, MutableList<ToolSpec>>()
    private val listeners = CopyOnWriteArrayList<ToolRegistryListener>()
    private val semanticIndex = SemanticIndex()

    fun register(tool: ToolSpec) {
        toolsById[tool.id] = tool
        toolsByName[tool.name] = tool
        toolsByCategory.getOrPut(tool.category.id) { mutableListOf() }.add(tool)
        tool.tags.forEach { tag ->
            toolsByTag.getOrPut(tag.lowercase()) { mutableListOf() }.add(tool)
        }
        if (tool.metadata.experimental.not()) {
            semanticIndex.index(tool)
        }
        listeners.forEach { it.onToolRegistered(tool) }
    }

    fun registerBatch(tools: List<ToolSpec>) {
        tools.forEach { register(it) }
    }

    fun unregister(toolId: String) {
        val tool = toolsById.remove(toolId) ?: return
        toolsByName.remove(tool.name)
        toolsByCategory[tool.category.id]?.remove(tool)
        tool.tags.forEach { tag ->
            toolsByTag[tag.lowercase()]?.remove(tool)
        }
        semanticIndex.remove(toolId)
        listeners.forEach { it.onToolUnregistered(toolId) }
    }

    fun getById(id: String): ToolSpec? = toolsById[id]

    fun getByName(name: String): ToolSpec? = toolsByName[name]

    fun getByCategory(categoryId: String): List<ToolSpec> =
        toolsByCategory[categoryId]?.toList() ?: emptyList()

    fun getByTag(tag: String): List<ToolSpec> =
        toolsByTag[tag.lowercase()]?.toList() ?: emptyList()

    fun getAll(): List<ToolSpec> = toolsById.values.toList()

    fun getAllCategories(): List<ToolCategory> =
        toolsByCategory.keys.mapNotNull { id ->
            toolsByCategory[id]?.firstOrNull()?.category
        }.distinct().sortedBy { it.priority }

    fun search(query: String, topK: Int = 5): List<ScoredTool> {
        if (query.isBlank()) return emptyList()
        val exact = getByName(query)
        if (exact != null) {
            return listOf(ScoredTool(exact, 1.0, "Exact name match"))
        }
        return semanticIndex.search(query, toolsById, topK)
    }

    fun searchByKeywords(keywords: List<String>, topK: Int = 5): List<ScoredTool> {
        val query = keywords.joinToString(" ")
        return search(query, topK)
    }

    fun fuzzyFind(query: String): List<ScoredTool> {
        val q = query.lowercase().trim()
        return toolsById.values.mapNotNull { tool ->
            val score = computeFuzzyScore(tool, q)
            if (score > 0.3) ScoredTool(tool, score, "Fuzzy match") else null
        }.sortedByDescending { it.score }.take(10)
    }

    fun addListener(listener: ToolRegistryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ToolRegistryListener) {
        listeners.remove(listener)
    }

    fun size(): Int = toolsById.size

    private fun computeFuzzyScore(tool: ToolSpec, query: String): Double {
        val name = tool.name.lowercase()
        val display = tool.displayName.lowercase()
        val desc = tool.description.lowercase()
        val tags = tool.tags.joinToString(" ").lowercase()

        return when {
            name == query -> 1.0
            name.contains(query) -> 0.9
            display.contains(query) -> 0.8
            desc.contains(query) -> 0.6
            tags.contains(query) -> 0.5
            query.split(" ").any { name.contains(it) } -> 0.4
            query.split(" ").any { desc.contains(it) } -> 0.35
            else -> 0.0
        }
    }
}

private class SemanticIndex {
    private data class IndexEntry(
        val toolId: String,
        val keywords: Set<String>,
        val description: String
    )
    private val entries = mutableListOf<IndexEntry>()

    fun index(tool: ToolSpec) {
        val keywords = buildSet {
            addAll(tool.name.split("_", "-").map { it.lowercase() })
            addAll(tool.tags.map { it.lowercase() })
            addAll(tool.category.id.split("_").map { it.lowercase() })
            add(tool.displayName.lowercase())
        }
        entries.add(IndexEntry(tool.id, keywords, tool.description.lowercase()))
    }

    fun remove(toolId: String) {
        entries.removeAll { it.toolId == toolId }
    }

    fun search(
        query: String,
        toolsById: Map<String, ToolSpec>,
        topK: Int
    ): List<ScoredTool> {
        val queryWords = query.lowercase().split(" ", "_", "-").filter { it.length > 1 }
        if (queryWords.isEmpty()) return emptyList()

        return entries.mapNotNull { entry ->
            val score = computeSimilarity(queryWords, entry)
            if (score > 0.0) {
                val tool = toolsById[entry.toolId]
                if (tool != null) ScoredTool(tool, score, "Semantic match") else null
            } else null
        }.sortedByDescending { it.score }.take(topK)
    }

    private fun computeSimilarity(queryWords: List<String>, entry: IndexEntry): Double {
        var matchCount = 0
        for (qw in queryWords) {
            if (entry.keywords.any { it.contains(qw) || qw.contains(it) }) {
                matchCount++
            } else if (entry.description.contains(qw)) {
                matchCount += 1
            }
        }
        if (matchCount == 0) return 0.0
        return matchCount.toDouble() / queryWords.size
    }
}
