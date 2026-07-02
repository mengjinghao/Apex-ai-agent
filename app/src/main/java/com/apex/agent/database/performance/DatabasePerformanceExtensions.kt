package com.apex.agent.database.performance

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DatabaseBatchProcessor(
    private val name: String = "db-batch",
    private val batchSize: Int = 50,
    private val flushIntervalMs: Long = 500L,
    private val maxQueueSize: Int = 1000
) {
    data class BatchWriteMetrics(
        val totalQueued: Long,
        val totalWritten: Long,
        val totalBatches: Long,
        val totalFailed: Long,
        val averageBatchTimeMs: Double,
        val currentQueueSize: Int,
        val throughputPerSecond: Double
    )

    private val logger = LoggerFactory.getLogger("DatabaseBatchProcessor-$name")
    private val writeQueue = ConcurrentLinkedQueue<DatabaseWrite>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerJob: Job
    private val queued = AtomicLong(0)
    private val written = AtomicLong(0)
    private val batches = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val batchTimeNs = AtomicLong(0)
    private val throughputCounter = AtomicLong(0)

    private sealed class DatabaseWrite {
        data class Insert(val table: String, val data: Map<String, Any?>) : DatabaseWrite()
        data class Update(val table: String, val id: Long, val data: Map<String, Any?>) : DatabaseWrite()
        data class Delete(val table: String, val id: Long) : DatabaseWrite()
        data class BulkInsert(val table: String, val rows: List<Map<String, Any?>>) : DatabaseWrite()
    }

    interface DatabaseWriteHandler {
        suspend fun handleInsert(table: String, data: Map<String, Any?>)
        suspend fun handleUpdate(table: String, id: Long, data: Map<String, Any?>)
        suspend fun handleDelete(table: String, id: Long)
        suspend fun handleBulkInsert(table: String, rows: List<Map<String, Any?>>)
    }

    init {
        writerJob = scope.launch {
            while (true) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    suspend fun insert(table: String, data: Map<String, Any?>) {
        if (writeQueue.size >= maxQueueSize) {
            flush()
        }
        writeQueue.add(DatabaseWrite.Insert(table, data))
        queued.incrementAndGet()
        if (writeQueue.size >= batchSize) {
            flush()
        }
    }

    suspend fun update(table: String, id: Long, data: Map<String, Any?>) {
        writeQueue.add(DatabaseWrite.Update(table, id, data))
        queued.incrementAndGet()
    }

    suspend fun delete(table: String, id: Long) {
        writeQueue.add(DatabaseWrite.Delete(table, id))
        queued.incrementAndGet()
    }

    suspend fun bulkInsert(table: String, rows: List<Map<String, Any?>>) {
        writeQueue.add(DatabaseWrite.BulkInsert(table, rows))
        queued.addAndGet(rows.size.toLong())
    }

    suspend fun flush() {
        if (writeQueue.isEmpty()) return
        val batch = mutableListOf<DatabaseWrite>()
        while (batch.size < batchSize) {
            writeQueue.poll()?.let { batch.add(it) } ?: break
        }
        if (batch.isEmpty()) return
        batches.incrementAndGet()
        val start = System.nanoTime()
        // In a real implementation, this would call the actual database handler
        batchTimeNs.addAndGet(System.nanoTime() - start)
        written.addAndGet(batch.size.toLong())
        throughputCounter.addAndGet(batch.size)
    }

    fun getMetrics(): BatchWriteMetrics {
        val b = batches.get()
        return BatchWriteMetrics(
            totalQueued = queued.get(),
            totalWritten = written.get(),
            totalBatches = b,
            totalFailed = failed.get(),
            averageBatchTimeMs = if (b > 0) batchTimeNs.get().toDouble() / b / 1_000_000.0 else 0.0,
            currentQueueSize = writeQueue.size,
            throughputPerSecond = throughputCounter.get().toDouble() / (if (batches.get() > 0) batches.get() * flushIntervalMs / 1000.0 else 1.0)
        )
    }

    fun shutdown() {
        scope.launch { flush() }
        writerJob.cancel()
        scope.cancel()
    }
}

class QueryCache(
    private val name: String = "query-cache",
    private val maxEntries: Int = 500,
    private val defaultTtlMs: Long = 30000L
) {
    data class CachedQuery(
        val sql: String,
        val params: List<Any?>,
        val result: List<Map<String, Any?>>,
        val cachedAt: Long,
        val expiresAt: Long,
        val hitCount: Long
    )

    private val cache = ConcurrentHashMap<String, CachedQuery>()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)

    fun get(sql: String, params: List<Any?> = emptyList()): List<Map<String, Any?>>? {
        val key = buildKey(sql, params)
        val entry = cache[key] ?: run { missCount.incrementAndGet(); return null }
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            evictionCount.incrementAndGet()
            missCount.incrementAndGet()
            return null
        }
        hitCount.incrementAndGet()
        cache.computeIfPresent(key) { _, existing ->
            existing.copy(hitCount = existing.hitCount + 1)
        }
        return entry.result
    }

    fun put(sql: String, params: List<Any?>, result: List<Map<String, Any?>>, ttlMs: Long = defaultTtlMs) {
        while (cache.size >= maxEntries) {
            val oldest = cache.minByOrNull { it.value.hitCount }?.key ?: break
            cache.remove(oldest)
            evictionCount.incrementAndGet()
        }
        val now = System.currentTimeMillis()
        cache[buildKey(sql, params)] = CachedQuery(sql, params, result, now, now + ttlMs, 0)
    }

    fun invalidate(sql: String, params: List<Any?> = emptyList()) {
        cache.remove(buildKey(sql, params))
    }

    fun invalidateTable(table: String) {
        val pattern = table.lowercase()
        cache.entries.removeAll { it.value.sql.lowercase().contains(pattern) }
    }

    fun clear() {
        cache.clear()
        hitCount.set(0)
        missCount.set(0)
        evictionCount.set(0)
    }

    fun getHitRate(): Double {
        val total = hitCount.get() + missCount.get()
        return if (total > 0) hitCount.get().toDouble() / total else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "size" to cache.size,
        "maxEntries" to maxEntries,
        "hitRate" to getHitRate(),
        "evictions" to evictionCount.get()
    )

    private fun buildKey(sql: String, params: List<Any?>): String {
        return "$sql|${params.joinToString(",") { it?.toString() ?: "null" }}"
    }
}

class PaginatedQuery<T>(
    private val query: suspend (offset: Int, limit: Int) -> List<T>,
    private val totalCount: suspend () -> Int,
    private val defaultPageSize: Int = 20
) {
    data class Page<T>(
        val items: List<T>,
        val pageIndex: Int,
        val pageSize: Int,
        val totalItems: Int,
        val totalPages: Int,
        val hasNext: Boolean,
        val hasPrevious: Boolean
    )

    suspend fun getPage(pageIndex: Int, pageSize: Int = defaultPageSize): Page<T> {
        val total = totalCount()
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
        val offset = pageIndex * pageSize
        val items = query(offset, pageSize)
        return Page(
            items = items,
            pageIndex = pageIndex,
            pageSize = pageSize,
            totalItems = total,
            totalPages = totalPages,
            hasNext = pageIndex < totalPages - 1,
            hasPrevious = pageIndex > 0
        )
    }

    fun flow(pageSize: Int = defaultPageSize): Flow<Page<T>> = flow {
        val total = totalCount()
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
        for (i in 0 until totalPages) {
            emit(getPage(i, pageSize))
        }
    }

    suspend fun getAll(): List<T> {
        val total = totalCount()
        if (total == 0) return emptyList()
        val allItems = mutableListOf<T>()
        var offset = 0
        while (offset < total) {
            allItems.addAll(query(offset, defaultPageSize))
            offset += defaultPageSize
        }
        return allItems
    }
}

class BulkOperationTracker(private val name: String) {
    data class BulkOpMetrics(
        val totalOperations: Long,
        val totalItems: Long,
        val totalTimeMs: Long,
        val averageItemTimeMs: Double,
        val throughputPerSecond: Double,
        val errorCount: Long
    )

    private val totalOps = AtomicLong(0)
    private val totalItems = AtomicLong(0)
    private val totalTimeNs = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    fun <T> measure(items: List<T>, operation: (List<T>) -> Unit): Long {
        val start = System.nanoTime()
        try {
            operation(items)
            val elapsed = System.nanoTime() - start
            totalOps.incrementAndGet()
            totalItems.addAndGet(items.size.toLong())
            totalTimeNs.addAndGet(elapsed)
            return elapsed
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            throw e
        }
    }

    suspend fun <T> measureSuspend(items: List<T>, operation: suspend (List<T>) -> Unit): Long {
        val start = System.nanoTime()
        try {
            operation(items)
            val elapsed = System.nanoTime() - start
            totalOps.incrementAndGet()
            totalItems.addAndGet(items.size.toLong())
            totalTimeNs.addAndGet(elapsed)
            return elapsed
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            throw e
        }
    }

    fun getMetrics(): BulkOpMetrics {
        val ops = totalOps.get()
        val items = totalItems.get()
        val timeNs = totalTimeNs.get()
        return BulkOpMetrics(
            totalOperations = ops,
            totalItems = items,
            totalTimeMs = if (ops > 0) timeNs / 1_000_000 / ops else 0,
            averageItemTimeMs = if (items > 0) timeNs.toDouble() / items / 1_000_000.0 else 0.0,
            throughputPerSecond = if (timeNs > 0) items.toDouble() / timeNs * 1_000_000_000.0 else 0.0,
            errorCount = errorCount.get()
        )
    }
}

class DatabaseConnectionPool(
    private val name: String = "db-pool",
    private val minConnections: Int = 2,
    private val maxConnections: Int = 10,
    private val connectionTimeoutMs: Long = 5000L,
    private val maxLifetimeMs: Long = 600000L,
    private val validationIntervalMs: Long = 30000L
) {
    data class ConnectionPoolMetrics(
        val totalConnections: Int,
        val activeConnections: Int,
        val idleConnections: Int,
        val pendingRequests: Int,
        val totalCreated: Long,
        val totalDestroyed: Long,
        val totalAcquired: Long,
        val totalReleased: Long,
        val totalTimeout: Long,
        val averageAcquireTimeMs: Double,
        val peakActive: Int
    )

    private val logger = LoggerFactory.getLogger("DBPool-$name")
    private val active = ConcurrentHashMap.newKeySet<Int>()
    private val idle = ConcurrentLinkedQueue<Int>()
    private val pendingRequests = AtomicInteger(0)
    private val totalCreated = AtomicLong(0)
    private val totalDestroyed = AtomicLong(0)
    private val totalAcquired = AtomicLong(0)
    private val totalReleased = AtomicLong(0)
    private val totalTimeout = AtomicLong(0)
    private val peakActive = AtomicInteger(0)
    private val acquireTimeNs = AtomicLong(0)
    private val acquireCount = AtomicLong(0)
    private val connectionCounter = AtomicInteger(0)
    private val currentConnections = AtomicInteger(0)

    fun acquire(): Int {
        pendingRequests.incrementAndGet()
        val start = System.nanoTime()

        var conn = idle.poll()
        if (conn != null) {
            active.add(conn)
            totalAcquired.incrementAndGet()
            pendingRequests.decrementAndGet()
            acquireTimeNs.addAndGet(System.nanoTime() - start)
            acquireCount.incrementAndGet()
            updatePeakActive()
            return conn
        }

        val current = currentConnections.get()
        if (current < maxConnections && currentConnections.compareAndSet(current, current + 1)) {
            conn = connectionCounter.incrementAndGet()
            active.add(conn)
            totalCreated.incrementAndGet()
            totalAcquired.incrementAndGet()
            pendingRequests.decrementAndGet()
            acquireTimeNs.addAndGet(System.nanoTime() - start)
            acquireCount.incrementAndGet()
            updatePeakActive()
            return conn
        }

        val deadline = System.currentTimeMillis() + connectionTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            conn = idle.poll()
            if (conn != null) {
                active.add(conn)
                totalAcquired.incrementAndGet()
                pendingRequests.decrementAndGet()
                acquireTimeNs.addAndGet(System.nanoTime() - start)
                acquireCount.incrementAndGet()
                updatePeakActive()
                return conn
            }
            Thread.sleep(10)
        }

        totalTimeout.incrementAndGet()
        pendingRequests.decrementAndGet()
        throw ConnectionPoolTimeoutException("Connection pool $name timed out after ${connectionTimeoutMs}ms")
    }

    fun release(connectionId: Int) {
        active.remove(connectionId)
        if (currentConnections.get() > minConnections && idle.size > minConnections) {
            currentConnections.decrementAndGet()
            totalDestroyed.incrementAndGet()
        } else {
            idle.add(connectionId)
        }
        totalReleased.incrementAndGet()
    }

    fun getMetrics(): ConnectionPoolMetrics {
        return ConnectionPoolMetrics(
            totalConnections = currentConnections.get(),
            activeConnections = active.size,
            idleConnections = idle.size,
            pendingRequests = pendingRequests.get(),
            totalCreated = totalCreated.get(),
            totalDestroyed = totalDestroyed.get(),
            totalAcquired = totalAcquired.get(),
            totalReleased = totalReleased.get(),
            totalTimeout = totalTimeout.get(),
            averageAcquireTimeMs = if (acquireCount.get() > 0)
                acquireTimeNs.get().toDouble() / acquireCount.get() / 1_000_000.0 else 0.0,
            peakActive = peakActive.get()
        )
    }

    fun close() {
        active.clear()
        idle.clear()
        currentConnections.set(0)
        logger.info("Connection pool $name closed")
    }

    private fun updatePeakActive() {
        val current = active.size
        var peak = peakActive.get()
        while (current > peak && !peakActive.compareAndSet(peak, current)) {
            peak = peakActive.get()
        }
    }

    class ConnectionPoolTimeoutException(message: String) : RuntimeException(message)
}

class DatabaseIndexManager(private val name: String = "index-manager") {
    data class IndexDefinition(
        val tableName: String,
        val indexName: String,
        val columns: List<String>,
        val unique: Boolean = false,
        val ifNotExists: Boolean = true
    )

    private val indexes = ConcurrentHashMap<String, IndexDefinition>()
    private val indexStats = ConcurrentHashMap<String, IndexStats>()

    data class IndexStats(
        val indexName: String,
        val tableName: String,
        val sizeBytes: Long,
        val rowCount: Long,
        val uniqueValues: Long,
        val lastUsed: Long,
        val usageCount: Long
    )

    fun registerIndex(index: IndexDefinition) {
        indexes[index.indexName] = index
    }

    fun getIndex(table: String, column: String): IndexDefinition? {
        return indexes.values.find { it.tableName == table && column in it.columns }
    }

    fun getTableIndexes(table: String): List<IndexDefinition> {
        return indexes.values.filter { it.tableName == table }
    }

    fun recordIndexUsage(indexName: String) {
        indexStats.computeIfPresent(indexName) { _, stats ->
            stats.copy(lastUsed = System.currentTimeMillis(), usageCount = stats.usageCount + 1)
        }
    }

    fun getUnusedIndexes(thresholdMs: Long = 604800000L): List<String> {
        val cutoff = System.currentTimeMillis() - thresholdMs
        return indexStats.filter { it.value.lastUsed < cutoff }.keys.toList()
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "totalIndexes" to indexes.size,
        "trackedIndexes" to indexStats.size
    )
}

class DatabaseMigrationManager(
    private val name: String = "migration-manager"
) {
    data class Migration(
        val version: Int,
        val description: String,
        val upSql: String,
        val downSql: String? = null,
        val checksum: String = ""
    )

    private val migrations = sortedSetOf<Migration>(compareBy { it.version })
    private val appliedMigrations = ConcurrentHashMap<Int, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = LoggerFactory.getLogger("MigrationManager-$name")

    fun registerMigration(migration: Migration) {
        migrations.add(migration)
    }

    fun registerMigrations(migrations: List<Migration>) {
        this.migrations.addAll(migrations)
    }

    suspend fun getPendingMigrations(currentVersion: Int): List<Migration> {
        return migrations.filter { it.version > currentVersion }
    }

    suspend fun getAppliedVersions(): List<Int> = appliedMigrations.keys.sorted()

    fun getMigrationHistory(): Map<Int, Long> = appliedMigrations.toMap()

    suspend fun hasPendingMigrations(currentVersion: Int): Boolean {
        return migrations.any { it.version > currentVersion }
    }

    fun validateMigrations(): List<String> {
        val errors = mutableListOf<String>()
        val versions = migrations.map { it.version }
        if (versions != (1..versions.size).toList()) {
            errors.add("Migration versions are not sequential: $versions")
        }
        val duplicateVersions = versions.groupBy { it }.filter { it.value.size > 1 }
        if (duplicateVersions.isNotEmpty()) {
            errors.add("Duplicate migration versions: ${duplicateVersions.keys}")
        }
        return errors
    }

    fun shutdown() { scope.cancel() }
}

class DatabaseShardManager(
    private val name: String = "shard-manager",
    private val shardCount: Int = 4
) {
    data class ShardInfo(
        val shardId: Int,
        val connectionString: String,
        val isActive: Boolean,
        val load: Double,
        val rowCount: Long
    )

    private val shards = ConcurrentHashMap<Int, ShardInfo>()
    private val shardKeyRanges = ConcurrentHashMap<Int, LongRange>()

    init {
        val shardSize = Long.MAX_VALUE / shardCount
        for (i in 0 until shardCount) {
            val start = i * shardSize
            val end = if (i == shardCount - 1) Long.MAX_VALUE else (i + 1) * shardSize - 1
            shardKeyRanges[i] = start..end
            shards[i] = ShardInfo(i, "shard-$i", true, 0.0, 0)
        }
    }

    fun getShardForKey(key: Long): Int {
        return (key % shardCount).toInt().coerceIn(0, shardCount - 1)
    }

    fun getShardIdByHash(key: String): Int {
        return (key.hashCode().let { it and Int.MAX_VALUE } % shardCount).coerceIn(0, shardCount - 1)
    }

    fun getActiveShards(): List<ShardInfo> = shards.values.filter { it.isActive }

    fun getShardInfo(shardId: Int): ShardInfo? = shards[shardId]

    fun markShardInactive(shardId: Int) {
        shards.computeIfPresent(shardId) { _, info -> info.copy(isActive = false) }
    }

    fun markShardActive(shardId: Int) {
        shards.computeIfPresent(shardId) { _, info -> info.copy(isActive = true) }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "shardCount" to shardCount,
        "activeShards" to getActiveShards().size
    )
}

class DatabaseReadReplicaManager(
    private val name: String = "read-replica",
    private val replicaUrls: List<String> = emptyList()
) {
    private val replicas = ConcurrentHashMap<String, ReplicaInfo>()
    private val currentIndex = AtomicInteger(0)
    private val logger = LoggerFactory.getLogger("ReadReplica-$name")

    data class ReplicaInfo(
        val url: String,
        val isHealthy: Boolean,
        val latencyMs: Long,
        val lastChecked: Long
    )

    init {
        replicaUrls.forEachIndexed { index, url ->
            replicas[url] = ReplicaInfo(url, true, 0, System.currentTimeMillis())
        }
    }

    fun getNextReplica(): String? {
        val healthy = replicas.values.filter { it.isHealthy }
        if (healthy.isEmpty()) return null
        val index = currentIndex.getAndIncrement() % healthy.size
        return healthy[index].url
    }

    fun markUnhealthy(url: String) {
        replicas.computeIfPresent(url) { _, info -> info.copy(isHealthy = false, lastChecked = System.currentTimeMillis()) }
        logger.warn("Read replica marked unhealthy: {}", url)
    }

    fun markHealthy(url: String, latencyMs: Long = 0) {
        replicas.computeIfPresent(url) { _, info ->
            info.copy(isHealthy = true, latencyMs = latencyMs, lastChecked = System.currentTimeMillis())
        }
    }

    fun getHealthyReplicas(): List<ReplicaInfo> = replicas.values.filter { it.isHealthy }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "totalReplicas" to replicas.size,
        "healthyReplicas" to getHealthyReplicas().size
    )
}
