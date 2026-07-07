package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件搜索技能
 * 实现混合搜索引擎、文件索引、快速搜索
 */
class FileSearchSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val fileIndex = ConcurrentHashMap<String, FileEntry>()
    private val invertedIndex = ConcurrentHashMap<String, MutableSet<String>>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "file_search",
            skillName = "文件搜索",
            version = "1.0.0",
            description = "混合搜索引擎，支持文件索引、关键词搜索和模糊匹配",
            author = "Apex Agent",
            tags = listOf("search", "file", "index"),
            priority = 70,
            capabilities = listOf(
                "file_indexing",
                "keyword_search",
                "fuzzy_matching",
                "hybrid_search"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "search"
            
            when (operation) {
                "index" -> {
                    val files = task.input.text?.split("\n") ?: emptyList()
                    var indexedCount = 0
                    
                    files.forEach { filePath ->
                        if (indexFile(filePath)) {
                            indexedCount++
                        }
                    }
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Files indexed:
                            |- Total files: ${files.size}
                            |- Successfully indexed: $indexedCount
                            |- Index size: ${fileIndex.size} files
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = indexedCount
                        )
                    )
                }
                "search" -> {
                    val query = task.metadata["query"] ?: task.input.text ?: ""
                    val limit = task.metadata["limit"]?.toIntOrNull() ?: 10
                    
                    val results = search(query, limit)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Search completed:
                            |- Query: $query
                            |- Results found: ${results.size}
                            ${results.take(5).joinToString("\n") { "- ${it.path} (score: ${it.score})" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = results.size
                        )
                    )
                }
                "fuzzy" -> {
                    val query = task.metadata["query"] ?: task.input.text ?: ""
                    val limit = task.metadata["limit"]?.toIntOrNull() ?: 10
                    
                    val results = fuzzySearch(query, limit)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Fuzzy search completed:
                            |- Query: $query
                            |- Results found: ${results.size}
                            ${results.take(5).joinToString("\n") { "- ${it.path} (similarity: ${it.similarity})" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = results.size
                        )
                    )
                }
                "stats" -> {
                    val stats = getIndexStats()
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Index statistics:
                            |- Total indexed files: ${stats.totalFiles}
                            |- Index terms: ${stats.indexTerms}
                            |- Average terms per file: ${stats.avgTermsPerFile}
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
    
    fun indexFile(filePath: String): Boolean {
        return try {
            val terms = filePath.lowercase()
                .split(Regex("[\\\\/._-]"))
                .filter { it.length > 2 }
            
            val entry = FileEntry(
                path = filePath,
                name = filePath.substringAfterLast("/").substringAfterLast("\\"),
                terms = terms.toSet(),
                indexedAt = System.currentTimeMillis()
            )
            
            fileIndex[filePath] = entry
            
            // 更新倒排索引
            terms.forEach { term ->
                invertedIndex.getOrPut(term) { mutableSetOf() }.add(filePath)
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun search(query: String, limit: Int = 10): List<SearchResult> {
        val queryTerms = query.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
        
        val scores = mutableMapOf<String, Int>()
        
        queryTerms.forEach { term ->
            val matchingFiles = invertedIndex[term] ?: emptySet()
            matchingFiles.forEach { filePath ->
                scores[filePath] = (scores[filePath] ?: 0) + 1
            }
        }
        
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (filePath, score) ->
                fileIndex[filePath]?.let { entry ->
                    SearchResult(
                        path = filePath,
                        name = entry.name,
                        score = score.toFloat(),
                        similarity = score.toFloat() / queryTerms.size
                    )
                }
            }
    }
    
    fun fuzzySearch(query: String, limit: Int = 10): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        fileIndex.values.forEach { entry ->
            val similarity = calculateSimilarity(query.lowercase(), entry.name.lowercase())
            if (similarity > 0.3f) {
                results.add(SearchResult(
                    path = entry.path,
                    name = entry.name,
                    score = similarity * 100,
                    similarity = similarity
                ))
            }
        }
        
        return results
            .sortedByDescending { it.similarity }
            .take(limit)
    }
    
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1f
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        
        val longerLength = longer.length
        return (longerLength - editDistance(longer, shorter)).toFloat() / longerLength
    }
    
    private fun editDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1) { it }
        
        for (i in 1..s1.length) {
            var lastValue = i
            for (j in 1..s2.length) {
                val newValue = if (s1[i - 1] == s2[j - 1]) {
                    costs[j - 1]
                } else {
                    minOf(costs[j - 1], lastValue, costs[j]) + 1
                }
                costs[j - 1] = lastValue
                lastValue = newValue
            }
            costs[s2.length] = lastValue
        }
        
        return costs[s2.length]
    }
    
    fun getIndexStats(): IndexStats {
        val totalTerms = invertedIndex.size
        val avgTerms = if (fileIndex.isNotEmpty()) {
            fileIndex.values.sumOf { it.terms.size } / fileIndex.size
        } else 0
        
        return IndexStats(
            totalFiles = fileIndex.size,
            indexTerms = totalTerms,
            avgTermsPerFile = avgTerms
        )
    }
    
    fun clearIndex() {
        fileIndex.clear()
        invertedIndex.clear()
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        fileIndex.clear()
        invertedIndex.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.76f
    
    data class FileEntry(
        val path: String,
        val name: String,
        val terms: Set<String>,
        val indexedAt: Long
    )
    
    data class SearchResult(
        val path: String,
        val name: String,
        val score: Float,
        val similarity: Float
    )
    
    data class IndexStats(
        val totalFiles: Int,
        val indexTerms: Int,
        val avgTermsPerFile: Int
    )
}