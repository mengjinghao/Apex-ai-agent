package com.apex.agent.database.performance

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class QueryPlan(
    val queryType: QueryType,
    val tableName: String,
    val columns: List<String>,
    val whereClause: String? = null,
    val orderBy: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val estimatedCost: Double = 1.0,
    val estimatedRows: Int = 100,
    val indexUsage: List<String> = emptyList(),
    val canUseCache: Boolean = true
)

enum class QueryType {
    SELECT, INSERT, UPDATE, DELETE, SELECT_ONE, COUNT, EXISTS, AGGREGATE, RAW
}

data class QueryResult<T>(
    val data: T?,
    val durationMs: Long,
    val fromCache: Boolean,
    val rowCount: Int,
    val queryPlan: QueryPlan,
    val error: String? = null
)

data class BatchQueryRequest(
    val queries: List<String>,
    val params: List<Map<String, Any>> = emptyList(),
    val transaction: Boolean = false,
    val priority: Int = 0
)

data class BatchQueryResult(
    val results: List<Any?>,
    val totalDurationMs: Long,
    val successCount: Int,
    val failureCount: Int,
    val transactionCommitted: Boolean = false
)

data class QueryCacheEntry(
    val sql: String,
    val params: String,
    val result: Any,
    val timestampMs: Long,
    val ttlMs: Long,
    val hitCount: AtomicInteger = AtomicInteger(1),
    val sizeBytes: Int = 0
)

data class QueryStatistics(
    val totalQueries: Long,
    val cacheHitRate: Double,
    val averageQueryTimeMs: Double,
    val p95QueryTimeMs: Double,
    val p99QueryTimeMs: Double,
    val queriesByType: Map<QueryType, Int>,
    val slowQueries: List<Pair<String, Long>>,
    readonly val cacheSize: Int
) {
    val cacheSizeVal: Int get() = cacheSize
}

data class IndexRecommendation(
    val tableName: String,
    val columns: List<String>,
    val estimatedImprovementPercent: Double,
    val priority: Int,
    val reason: String,
    val createIndexSql: String
)

data class PaginationRequest(
    val page: Int,
    val pageSize: Int,
    val sortColumn: String? = null,
    val sortDirection: SortDirection = SortDirection.ASC
)

enum class SortDirection { ASC, DESC }

data class PaginatedResult<T>(
    val items: List<T>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val durationMs: Long
)

data class QueryPoolConfig(
    val maxPoolSize: Int = 4,
    val enableCache: Boolean = true,
    val cacheTtlMs: Long = 60000L,
    val slowQueryThresholdMs: Long = 500L,
    val maxCachedQueries: Int = 2000,
    val enableQueryPlanAnalysis: Boolean = true,
    val enableAutoIndexRecommendation: Boolean = true
)

class DaoQueryOptimizer private constructor() {

    private val queryCache = ConcurrentHashMap<String, QueryCacheEntry>()
    private val queryTimes = CopyOnWriteArrayList<Pair<String, Long>>()
    private val queryTypeCounts = ConcurrentHashMap<QueryType, AtomicInteger>()
    private val slowQueryLog = CopyOnWriteArrayList<Pair<String, Long>>()
    private val totalQueries = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val config = QueryPoolConfig()
    private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: DaoQueryOptimizer? = null

        fun getInstance(): DaoQueryOptimizer {
            return instance ?: synchronized(this) {
                instance ?: DaoQueryOptimizer().also { instance = it }
            }
        }

        private const val MAX_SLOW_QUERY_LOG = 100
        private const val QUERY_HISTORY_SIZE = 1000
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        if (config.enableCache) {
            coroutineScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(120000L)
                    evictStaleCacheEntries()
                }
            }
        }
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(300000L)
                analyzeQueryPatterns()
            }
        }
    }

    fun analyzeQuery(sql: String, params: Map<String, Any> = emptyMap()): QueryPlan {
        val upper = sql.trim().uppercase()
        val queryType = when {
            upper.startsWith("SELECT") && upper.contains("COUNT(") -> QueryType.COUNT
            upper.startsWith("SELECT") && upper.contains("EXISTS(") -> QueryType.EXISTS
            upper.startsWith("SELECT") && (upper.contains("SUM(") || upper.contains("AVG(") || upper.contains("MAX(") || upper.contains("MIN(")) -> QueryType.AGGREGATE
            upper.startsWith("SELECT") && (upper.contains("LIMIT 1") || upper.contains("TOP 1")) -> QueryType.SELECT_ONE
            upper.startsWith("SELECT") -> QueryType.SELECT
            upper.startsWith("INSERT") -> QueryType.INSERT
            upper.startsWith("UPDATE") -> QueryType.UPDATE
            upper.startsWith("DELETE") -> QueryType.DELETE
            else -> QueryType.RAW
        }
        val tableName = extractTableName(sql)
        val columns = if (queryType == QueryType.SELECT) extractColumns(sql) else emptyList()
        QueryPlan(
            queryType = queryType,
            tableName = tableName,
            columns = columns,
            whereClause = extractWhereClause(sql),
            orderBy = extractOrderBy(sql),
            limit = extractLimit(sql),
            canUseCache = queryType in listOf(QueryType.SELECT, QueryType.SELECT_ONE, QueryType.COUNT, QueryType.EXISTS)
        )
    }

    suspend fun <T> executeQuery(
        sql: String,
        params: Map<String, Any> = emptyMap(),
        executor: suspend (String, Map<String, Any>) -> T
    ): QueryResult<T> {
        val startTime = System.nanoTime()
        totalQueries.incrementAndGet()
        val plan = analyzeQuery(sql, params)
        recordQueryType(plan.queryType)

        val cacheKey = buildCacheKey(sql, params)
        if (config.enableCache && plan.canUseCache) {
            val cached = queryCache[cacheKey]
            if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < config.cacheTtlMs) {
                cached.hitCount.incrementAndGet()
                cacheHits.incrementAndGet()
                @Suppress("UNCHECKED_CAST")
                return QueryResult(cached.result as T, 1, true, 0, plan)
            }
            cacheMisses.incrementAndGet()
        }

        return try {
            val result = executor(sql, params)
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            queryTimes.add(Pair(sql, durationMs))
            if (queryTimes.size > QUERY_HISTORY_SIZE) queryTimes.removeAt(0)

            if (durationMs > config.slowQueryThresholdMs) {
                slowQueryLog.add(Pair(sql, durationMs))
                if (slowQueryLog.size > MAX_SLOW_QUERY_LOG) slowQueryLog.removeAt(0)
            }

            if (config.enableCache && plan.canUseCache) {
                cacheResult(cacheKey, result, durationMs)
            }

            QueryResult(result, durationMs, false, extractRowCount(result), plan)
        } catch (e: Exception) {
            QueryResult(null, (System.nanoTime() - startTime) / 1_000_000, false, 0, plan, e.message)
        }
    }

    suspend fun <T> executeBatch(
        request: BatchQueryRequest,
        executor: suspend (String, Map<String, Any>) -> T
    ): BatchQueryResult {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<Any?>()
        var successCount = 0
        var failureCount = 0

        for ((index, sql) in request.queries.withIndex()) {
            try {
                val params = request.params.getOrElse(index) { emptyMap() }
                val result = executor(sql, params)
                results.add(result)
                successCount++
            } catch (e: Exception) {
                results.add(null)
                failureCount++
                if (request.transaction) break
            }
        }

        BatchQueryResult(
            results = results,
            totalDurationMs = System.currentTimeMillis() - startTime,
            successCount = successCount,
            failureCount = failureCount,
            transactionCommitted = request.transaction && failureCount == 0
        )
    }

    fun <T> paginateQuery(
        query: suspend () -> List<T>,
        request: PaginationRequest
    ): PaginatedResult<T> {
        val startTime = System.currentTimeMillis()
        val allItems = runBlocking { query() }
        val totalCount = allItems.size
        val totalPages = ceil(totalCount.toDouble() / request.pageSize).toInt().coerceAtLeast(1)
        val adjustedPage = request.page.coerceIn(1, totalPages)
        val skip = (adjustedPage - 1) * request.pageSize
        val items = allItems.drop(skip).take(request.pageSize)
        PaginatedResult(
            items = items,
            totalCount = totalCount,
            page = adjustedPage,
            pageSize = request.pageSize,
            totalPages = totalPages,
            hasNext = adjustedPage < totalPages,
            hasPrevious = adjustedPage > 1,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    fun getRecommendIndexes(): List<IndexRecommendation> {
        val recommendations = mutableListOf<IndexRecommendation>()
        val queryPatterns = queryTimes.groupBy { analyzeQuery(it.first).tableName }

        for ((table, queries) in queryPatterns) {
            if (table.isBlank()) continue
            val avgTime = queries.map { it.second }.average()
            if (avgTime > 200 && queries.size > 5) {
                val columns = queries.mapNotNull { analyzeQuery(it.first).columns.firstOrNull() }.distinct()
                if (columns.isNotEmpty()) {
                    val createSql = "CREATE INDEX IF NOT EXISTS idx_${table}_${columns.joinToString("_")} ON $table (${columns.joinToString(", ")})"
                    recommendations.add(IndexRecommendation(
                        tableName = table,
                        columns = columns,
                        estimatedImprovementPercent = min(90.0, avgTime / 10),
                        priority = (avgTime / 100).toInt().coerceIn(1, 10),
                        reason = "Table '$table' queried ${queries.size} times with avg ${"%.0f".format(avgTime)}ms",
                        createIndexSql = createSql
                    ))
                }
            }
        }
        recommendations.sortedByDescending { it.priority }
    }

    fun getStatistics(): QueryStatistics {
        val avgTime = if (queryTimes.isNotEmpty()) queryTimes.map { it.second }.average() else 0.0
        val sorted = queryTimes.map { it.second }.sorted()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val cacheTotal = cacheHits.get() + cacheMisses.get()
        val hitRate = if (cacheTotal > 0) cacheHits.get().toDouble() / cacheTotal else 0.0
        val slowQueries = slowQueryLog.sortedByDescending { it.second }.take(10)
        QueryStatistics(
            totalQueries = totalQueries.get(),
            cacheHitRate = hitRate,
            averageQueryTimeMs = avgTime,
            p95QueryTimeMs = p95,
            p99QueryTimeMs = p99,
            queriesByType = queryTypeCounts.entries.associate { it.key to it.value.get() },
            slowQueries = slowQueries,
            cacheSize = queryCache.size
        )
    }

    fun getSlowQueries(thresholdMs: Long = 200L): List<Pair<String, Long>> {
        queryTimes.filter { it.second >= thresholdMs }.sortedByDescending { it.second }.take(20)
    }

    fun clearCache() { queryCache.clear(); cacheHits.set(0); cacheMisses.set(0) }

    fun clearHistory() { queryTimes.clear(); slowQueryLog.clear() }

    fun invalidateTable(tableName: String) {
        val prefix = "$tableName:"
        queryCache.keys.filter { it.startsWith(prefix) }.forEach { queryCache.remove(it) }
    }

    private fun extractTableName(sql: String): String {
        val regex = Regex("""(?:FROM|INTO|UPDATE|TABLE)\s+["']?(\w+)["']?""", RegexOption.IGNORE_CASE)
        regex.find(sql)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractColumns(sql: String): List<String> {
        val regex = Regex("""SELECT\s+(.+?)\s+FROM""", RegexOption.IGNORE_CASE)
        val selectPart = regex.find(sql)?.groupValues?.getOrNull(1) ?: return emptyList()
        if (selectPart.trim() == "*") return emptyList()
        selectPart.split(",").map { it.trim().substringAfter("AS ").trim().substringAfter("as ").trim().removeSurrounding("\"") }
    }

    private fun extractWhereClause(sql: String): String? {
        val regex = Regex("""WHERE\s+(.+?)(?:ORDER BY|GROUP BY|LIMIT|OFFSET|HAVING|$)""", RegexOption.IGNORE_CASE)
        regex.find(sql)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractOrderBy(sql: String): String? {
        val regex = Regex("""ORDER BY\s+(.+?)(?:LIMIT|OFFSET|$)""", RegexOption.IGNORE_CASE)
        regex.find(sql)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractLimit(sql: String): Int? {
        val regex = Regex("""LIMIT\s+(\d+)""", RegexOption.IGNORE_CASE)
        regex.find(sql)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun buildCacheKey(sql: String, params: Map<String, Any>): String {
        val paramStr = params.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
        "${extractTableName(sql)}:${sql.hashCode()}:$paramStr"
    }

    private fun cacheResult(key: String, result: Any, durationMs: Long) {
        if (queryCache.size >= config.maxCachedQueries) {
            val oldest = queryCache.minByOrNull { it.value.timestampMs }
            if (oldest != null) queryCache.remove(oldest.key)
        }
        queryCache[key] = QueryCacheEntry(
            sql = "", params = key, result = result,
            timestampMs = System.currentTimeMillis(),
            ttlMs = (durationMs * 100).coerceIn(5000L, config.cacheTtlMs),
            sizeBytes = result.toString().toByteArray().size
        )
    }

    private fun recordQueryType(type: QueryType) {
        queryTypeCounts.computeIfAbsent(type) { AtomicInteger(0) }.incrementAndGet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> extractRowCount(result: T): Int {
        when (result) {
            is List<*> -> result.size
            is Map<*, *> -> result.size
            is Int -> result
            is Long -> result.toInt()
            else -> 0
        }
    }

    private fun analyzeQueryPatterns(): List<String> {
        val suggestions = mutableListOf<String>()
        val tableQueries = queryTimes.groupBy { extractTableName(it.first) }
        for ((table, queries) in tableQueries) {
            if (table.isBlank()) continue
            val avgTime = queries.map { it.second }.average()
            if (avgTime > 200) {
                suggestions.add("Table '$table' average query time is ${"%.0f".format(avgTime)}ms - consider adding indexes")
            }
            if (queries.size > 50) {
                suggestions.add("Table '$table' queried ${queries.size} times - consider caching")
            }
        }
        suggestions
    }

    private fun evictStaleCacheEntries() {
        val now = System.currentTimeMillis()
        val toRemove = queryCache.filter { (now - it.value.timestampMs) > it.value.ttlMs }
        toRemove.keys.forEach { queryCache.remove(it) }
    }

    fun updateConfig(update: QueryPoolConfig.() -> QueryPoolConfig): QueryPoolConfig {
        val newConfig = update(config)
        newConfig
    }

    fun resetAll() {
        queryCache.clear()
        queryTimes.clear()
        queryTypeCounts.clear()
        slowQueryLog.clear()
        totalQueries.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
    }
}
