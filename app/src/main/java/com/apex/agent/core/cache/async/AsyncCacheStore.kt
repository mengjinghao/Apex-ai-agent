package com.apex.agent.core.cache.async

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.core.cache.CacheStats
import com.apex.agent.core.cache.ICacheStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.Flow
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.MutableStateFlow
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.asStateFlow
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.map
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AsyncCacheStore<V>(
    private val delegate: ICacheStore<String, V>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val asyncTimeoutMs: Long = 5000L
) : ICacheStore<String, V> {
    private val logger = LoggerFactory.getLogger(AsyncCacheStore::class.java)
    private val pendingOperations = AtomicLong(0)
    private val failedOperations = AtomicLong(0)
    private val _operationFlow = MutableStateFlow<CacheOperation>(CacheOperation.Idle)
    val operationFlow = _operationFlow.asStateFlow()

    private sealed class CacheOperation {
        object Idle : CacheOperation()
        data class Get(val key: String) : CacheOperation()
        data class Put(val key: String) : CacheOperation()
        data class Remove(val key: String) : CacheOperation()
        data class Batch(val count: Int) : CacheOperation()
    }

    suspend fun getAsync(key: String): V? {
        pendingOperations.incrementAndGet()
        _operationFlow.value = CacheOperation.Get(key)
        return withTimeoutOrNull(asyncTimeoutMs) {
            withContext(Dispatchers.Default) {
                delegate.get(key)?.value
            }
        } ?: run {
            failedOperations.incrementAndGet()
            logger.warn("Async get timeout for key: {}", key)
            null
        }.also { pendingOperations.decrementAndGet() }
    }

    suspend fun putAsync(key: String, value: V, ttl: Long = -1L) {
        pendingOperations.incrementAndGet()
        _operationFlow.value = CacheOperation.Put(key)
        try {
            withTimeout(asyncTimeoutMs) {
                withContext(Dispatchers.Default) {
                    delegate.put(CacheEntry(key, value, ttl = ttl))
                }
            }
        } catch (e: Exception) {
            failedOperations.incrementAndGet()
            logger.warn("Async put failed for key: {}", key, e)
        } finally {
            pendingOperations.decrementAndGet()
        }
    }

    suspend fun removeAsync(key: String): Boolean {
        pendingOperations.incrementAndGet()
        _operationFlow.value = CacheOperation.Remove(key)
        return try {
            withTimeout(asyncTimeoutMs) {
                withContext(Dispatchers.Default) {
                    delegate.remove(key)
                }
            }
        } catch (e: Exception) {
            failedOperations.incrementAndGet()
            false
        } finally {
            pendingOperations.decrementAndGet()
        }
    }

    suspend fun getOrPutAsync(key: String, loader: suspend () -> V, ttl: Long = -1L): V {
        val existing = getAsync(key)
        if (existing != null) return existing
        val value = loader()
        putAsync(key, value, ttl)
        return value
    }

    suspend fun getAllAsync(keys: Collection<String>): Map<String, V> {
        return coroutineScope {
            keys.map { key -> async { key to getAsync(key) } }
                .awaitAll()
                .filter { it.second != null }
                .associate { it.first to it.second!! }
        }
    }

    fun getPendingCount(): Long = pendingOperations.get()
    fun getFailedCount(): Long = failedOperations.get()

    override fun get(key: String): CacheEntry<V>? = runBlocking(Dispatchers.IO) { getAsync(key)?.let { CacheEntry(key, it) } }
    override fun put(entry: CacheEntry<V>) { runBlocking(Dispatchers.IO) { putAsync(entry.key, entry.value, entry.ttl) } }
    override fun remove(key: String): Boolean = runBlocking(Dispatchers.IO) { removeAsync(key) }
    override fun clear() { delegate.clear() }
    override fun contains(key: String): Boolean = delegate.contains(key)
    override fun size(): Int = delegate.size()
    override fun stats(): CacheStats = delegate.stats()
    override fun evict(policy: CachePolicy): List<String> = delegate.evict(policy)
    override fun warmUp(keys: Collection<String>): Int {
        return runBlocking(Dispatchers.IO) {
            var loaded = 0
            for (key in keys) {
                if (getAsync(key) != null) loaded++
            }
            loaded
        }
    }

    fun getOperationFlow(): Flow<CacheOperation> = _operationFlow.asSharedFlow().map { it }
}

class CacheLoader<V>(
    private val name: String = "cache-loader",
    private val loader: suspend (String) -> V?,
    private val asyncStore: AsyncCacheStore<V>,
    private val ttl: Long = -1L,
    private val preloadKeys: Set<String> = emptySet(),
    private val refreshIntervalMs: Long = -1L
) {
    private val logger = LoggerFactory.getLogger("CacheLoader-$name")
    private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (preloadKeys.isNotEmpty()) {
            scope.launch { preload() }
        }
        if (refreshIntervalMs > 0) {
            scope.launch {
                while (true) {
                    delay(refreshIntervalMs)
                    refresh()
                }
            }
        }
    }

    suspend fun get(key: String): V? {
        val cached = asyncStore.getAsync(key)
        if (cached != null) return cached
        if (!loadingKeys.add(key)) return null
        return try {
            val value = loader(key)
            if (value != null) {
                asyncStore.putAsync(key, value, ttl)
            }
            value
        } finally {
            loadingKeys.remove(key)
        }
    }

    suspend fun getOrThrow(key: String): V {
        return get(key) ?: throw CacheLoaderException("Failed to load cache entry: $key")
    }

    suspend fun refresh() {
        val allKeys = asyncStore.getAllKeys()
        for (key in allKeys) {
            try {
                val value = loader(key)
                if (value != null) {
                    asyncStore.putAsync(key, value, ttl)
                }
            } catch (e: Exception) {
                logger.warn("Refresh failed for key: {}", key, e)
            }
        }
    }

    suspend fun preload() {
        logger.info("Preloading {} keys for {}", preloadKeys.size, name)
        var loaded = 0
        for (key in preloadKeys) {
            try {
                val value = loader(key)
                if (value != null) {
                    asyncStore.putAsync(key, value, ttl)
                    loaded++
                }
            } catch (e: Exception) {
                logger.warn("Preload failed for key: {}", key, e)
            }
        }
        logger.info("Preloaded {}/{} keys for {}", loaded, preloadKeys.size, name)
    }

    class CacheLoaderException(message: String) : RuntimeException(message)

    private fun AsyncCacheStore<V>.getAllKeys(): List<String> {
        return emptyList()
    }
}

class BatchCacheLoader<K, V>(
    private val name: String = "batch-cache-loader",
    private val batchLoader: suspend (List<K>) -> Map<K, V>,
    private val asyncStore: AsyncCacheStore<V>,
    private val batchSize: Int = 100,
    private val ttl: Long = -1L
) {
    private val logger = LoggerFactory.getLogger("BatchCacheLoader-$name")
    private val pendingKeys = ConcurrentHashMap<K, CompletableDeferred<V?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Any()

    init {
        scope.launch {
            while (true) {
                delay(100)
                processBatch()
            }
        }
    }

    suspend fun get(key: K): V? {
        val cached = asyncStore.getAsync(key.toString())
        if (cached != null) return cached
        val deferred = CompletableDeferred<V?>()
        synchronized(mutex) {
            pendingKeys[key] = deferred
        }
        return deferred.await()
    }

    private suspend fun processBatch() {
        val batch: Map<K, CompletableDeferred<V?>>
        synchronized(mutex) {
            if (pendingKeys.isEmpty()) return
            val keys = pendingKeys.keys.take(batchSize)
            batch = keys.associateWith { pendingKeys.remove(it)!! }
        }
        if (batch.isEmpty()) return

        try {
            val results = batchLoader(batch.keys.toList())
            for ((key, deferred) in batch) {
                val value = results[key]
                if (value != null) {
                    asyncStore.putAsync(key.toString(), value, ttl)
                }
                deferred.complete(value)
            }
        } catch (e: Exception) {
            logger.warn("Batch load failed for {} keys", batch.size, e)
            for ((_, deferred) in batch) {
                deferred.completeExceptionally(e)
            }
        }
    }

    fun shutdown() { scope.cancel() }
}

class AsyncCacheManager<V>(
    private val name: String = "async-cache",
    private val maxMemoryEntries: Int = 1000,
    private val maxDiskEntries: Int = 10000,
    private val ttlMs: Long = -1L
) {
    private val memoryCache = ConcurrentHashMap<String, CacheEntry<V>>()
    private val accessOrder = ConcurrentHashMap<String, Long>()
    private val pendingOps = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)

    suspend fun get(key: String): V? {
        val entry = memoryCache[key] ?: run { missCount.incrementAndGet(); return null }
        if (entry.isExpired()) {
            memoryCache.remove(key)
            accessOrder.remove(key)
            evictionCount.incrementAndGet()
            missCount.incrementAndGet()
            return null
        }
        accessOrder[key] = System.nanoTime()
        hitCount.incrementAndGet()
        return entry.value
    }

    suspend fun set(key: String, value: V, ttl: Long = ttlMs) {
        checkMemoryLimit()
        memoryCache[key] = CacheEntry(key, value, ttl = ttl)
        accessOrder[key] = System.nanoTime()
    }

    suspend fun remove(key: String) {
        memoryCache.remove(key)
        accessOrder.remove(key)
    }

    suspend fun clear() {
        memoryCache.clear()
        accessOrder.clear()
    }

    fun getHitRate(): Double {
        val hits = hitCount.get()
        val misses = missCount.get()
        val total = hits + misses
        return if (total > 0) hits.toDouble() / total else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "size" to memoryCache.size,
        "hitRate" to getHitRate(),
        "evictions" to evictionCount.get(),
        "pendingOps" to pendingOps.get()
    )

    private fun checkMemoryLimit() {
        while (memoryCache.size >= maxMemoryEntries) {
            val oldest = accessOrder.minByOrNull { it.value }?.key ?: break
            memoryCache.remove(oldest)
            accessOrder.remove(oldest)
            evictionCount.incrementAndGet()
        }
    }
}

class CacheStatsCollector(private val name: String = "cache-stats") {
    private val getLatencies = ConcurrentLinkedQueue<Long>()
    private val putLatencies = ConcurrentLinkedQueue<Long>()
    private val getCount = AtomicLong(0)
    private val putCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val maxSamples = 1000

    data class Stats(
        val name: String,
        val totalGets: Long,
        val totalPuts: Long,
        val hitRate: Double,
        val missRate: Double,
        val avgGetLatencyUs: Double,
        val avgPutLatencyUs: Double,
        val p50GetLatencyUs: Double,
        val p99GetLatencyUs: Double,
        val errorCount: Long,
        val errorRate: Double
    )

    fun recordGet(startNs: Long, hit: Boolean) {
        val elapsedUs = (System.nanoTime() - startNs) / 1000
        getLatencies.add(elapsedUs)
        if (getLatencies.size > maxSamples) getLatencies.poll()
        getCount.incrementAndGet()
        if (hit) hitCount.incrementAndGet() else missCount.incrementAndGet()
    }

    fun recordPut(startNs: Long) {
        val elapsedUs = (System.nanoTime() - startNs) / 1000
        putLatencies.add(elapsedUs)
        if (putLatencies.size > maxSamples) putLatencies.poll()
        putCount.incrementAndGet()
    }

    fun recordError() { errorCount.incrementAndGet() }

    fun getStats(): Stats {
        val gets = getCount.get()
        val puts = putCount.get()
        val totalOps = gets + puts
        val errs = errorCount.get()
        val hits = hitCount.get()
        val misses = missCount.get()

        val getSorted = getLatencies.sorted()
        val putSorted = putLatencies.sorted()

        return Stats(
            name = name,
            totalGets = gets,
            totalPuts = puts,
            hitRate = if (gets > 0) hits.toDouble() / (hits + misses) else 0.0,
            missRate = if (gets > 0) misses.toDouble() / (hits + misses) else 0.0,
            avgGetLatencyUs = if (getSorted.isNotEmpty()) getSorted.average() else 0.0,
            avgPutLatencyUs = if (putSorted.isNotEmpty()) putSorted.average() else 0.0,
            p50GetLatencyUs = getSorted.getOrNull(getSorted.size / 2) ?: 0.0,
            p99GetLatencyUs = getSorted.getOrNull((getSorted.size * 0.99).toInt()) ?: 0.0,
            errorCount = errs,
            errorRate = if (totalOps > 0) errs.toDouble() / totalOps else 0.0
        )
    }

    fun reset() {
        getLatencies.clear()
        putLatencies.clear()
        getCount.set(0)
        putCount.set(0)
        missCount.set(0)
        hitCount.set(0)
        errorCount.set(0)
    }
}

class PredictiveCache<V>(
    private val name: String = "predictive-cache",
    private val maxEntries: Int = 1000,
    private val ttlMs: Long = 60000L
) {
    private data class PredictionMetadata(
        var accessCount: Long = 0,
        var lastAccessTime: Long = 0,
        var averageIntervalMs: Double = 0.0,
        var nextPredictedAccess: Long = 0
    )

    private val logger = LoggerFactory.getLogger("PredictiveCache-$name")
    private val cache = ConcurrentHashMap<String, CacheEntry<V>>()
    private val metadata = ConcurrentHashMap<String, PredictionMetadata>()
    private val accessHistory = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val preloadedCount = AtomicLong(0)
    private val predictionHit = AtomicLong(0)
    private val predictionMiss = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val maxHistorySize = 20

    init {
        scope.launch {
            while (true) {
                delay(10000)
                predictAndPreload()
            }
        }
    }

    suspend fun get(key: String): V? {
        val entry = cache[key]
        updateMetadata(key, hit = entry != null && !entry.isExpired())
        if (entry != null && !entry.isExpired()) {
            predictionHit.incrementAndGet()
            return entry.value
        }
        predictionMiss.incrementAndGet()
        return null
    }

    suspend fun set(key: String, value: V) {
        while (cache.size >= maxEntries) { evictOne() }
        cache[key] = CacheEntry(key, value, ttl = ttlMs)
        metadata.getOrPut(key) { PredictionMetadata() }
    }

    suspend fun remove(key: String) {
        cache.remove(key)
        metadata.remove(key)
        accessHistory.remove(key)
    }

    fun recordAccess(key: String) {
        val now = System.currentTimeMillis()
        val history = accessHistory.getOrPut(key) { ConcurrentLinkedQueue() }
        history.add(now)
        if (history.size > maxHistorySize) history.poll()

        val meta = metadata.getOrPut(key) { PredictionMetadata() }
        meta.accessCount++
        meta.lastAccessTime = now

        if (history.size >= 2) {
            val sorted = history.sorted()
            val intervals = sorted.zipWithNext { a, b -> (b - a).toDouble() }
            meta.averageIntervalMs = intervals.average()
            meta.nextPredictedAccess = now + meta.averageIntervalMs.toLong()
        }
    }

    fun getPredictedAccessTime(key: String): Long {
        return metadata[key]?.nextPredictedAccess ?: Long.MAX_VALUE
    }

    fun shouldPrefetch(key: String): Boolean {
        val meta = metadata[key] ?: return false
        val now = System.currentTimeMillis()
        return meta.nextPredictedAccess in (now - 1000)..(now + 10000)
    }

    fun getPredictionAccuracy(): Double {
        val hits = predictionHit.get()
        val misses = predictionMiss.get()
        val total = hits + misses
        return if (total > 0) hits.toDouble() / total else 0.0
    }

    fun getHotKeys(limit: Int = 10): List<String> {
        return metadata.entries
            .sortedByDescending { it.value.accessCount }
            .take(limit)
            .map { it.key }
    }

    fun getColdKeys(threshold: Long = 3): List<String> {
        return metadata.entries
            .filter { it.value.accessCount <= threshold }
            .map { it.key }
    }

    suspend fun preloadKeys(keys: List<String>, loader: suspend (String) -> V?) {
        var loaded = 0
        for (key in keys) {
            if (!cache.containsKey(key)) {
                val value = loader(key)
                if (value != null) {
                    set(key, value)
                    loaded++
                }
            }
        }
        if (loaded > 0) {
            preloadedCount.addAndGet(loaded.toLong())
            logger.debug("Preloaded {} keys into predictive cache {}", loaded, name)
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "size" to cache.size,
        "maxEntries" to maxEntries,
        "preloaded" to preloadedCount.get(),
        "predictionAccuracy" to getPredictionAccuracy(),
        "hotKeys" to getHotKeys(5)
    )

    private suspend fun predictAndPreload() {
        val now = System.currentTimeMillis()
        val toPreload = metadata.filter { (_, meta) ->
            meta.nextPredictedAccess in (now - 500)..(now + 5000) && !cache.containsKey(it.key)
        }.keys
        if (toPreload.isNotEmpty()) {
            logger.debug("Predictive preload: {} keys predicted for access", toPreload.size)
        }
    }

    private fun evictOne() {
        val coldest = metadata.minByOrNull { it.value.accessCount }?.key ?: return
        cache.remove(coldest)
        metadata.remove(coldest)
    }

    private fun updateMetadata(key: String, hit: Boolean) {
        val now = System.currentTimeMillis()
        val meta = metadata.getOrPut(key) { PredictionMetadata() }
        meta.accessCount++
        meta.lastAccessTime = now

        val history = accessHistory.getOrPut(key) { ConcurrentLinkedQueue() }
        history.add(now)
        if (history.size > maxHistorySize) history.poll()
        if (history.size >= 2) {
            val sorted = history.sorted()
            val intervals = sorted.zipWithNext { a, b -> (b - a).toDouble() }
            meta.averageIntervalMs = intervals.average()
            meta.nextPredictedAccess = now + meta.averageIntervalMs.toLong()
        }
    }
}

class StreamingCache<V>(
    private val name: String = "streaming-cache",
    private val chunkSize: Int = 8192,
    private val maxChunksPerKey: Int = 128
) {
    private val logger = LoggerFactory.getLogger("StreamingCache-$name")
    private val chunkStore = ConcurrentHashMap<String, List<CacheEntry<ByteArray>>>()
    private val streamPositions = ConcurrentHashMap<String, AtomicLong>()
    private val totalBytesCached = AtomicLong(0)
    private val totalChunksCached = AtomicLong(0)

    data class StreamInfo(
        val key: String,
        val totalChunks: Int,
        val totalBytes: Long,
        val isComplete: Boolean
    )

    fun putChunk(key: String, chunkIndex: Int, data: ByteArray, ttl: Long = -1L) {
        val entry = CacheEntry("$key:$chunkIndex", data, ttl = ttl)
        chunkStore.compute(key) { _, existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            while (list.size <= chunkIndex) {
                list.add(CacheEntry("$key:${list.size}", ByteArray(0), ttl = -1L))
            }
            list[chunkIndex] = entry
            list
        }
        totalChunksCached.incrementAndGet()
        totalBytesCached.addAndGet(data.size.toLong())
        checkMemoryLimit(key)
    }

    fun getChunk(key: String, chunkIndex: Int): ByteArray? {
        val chunks = chunkStore[key] ?: return null
        if (chunkIndex >= chunks.size) return null
        val entry = chunks[chunkIndex]
        if (entry.isExpired()) return null
        return if (entry.value.size > 0) entry.value else null
    }

    fun append(key: String, data: ByteArray, ttl: Long = -1L) {
        val position = streamPositions.getOrPut(key) { AtomicLong(0) }
        val chunkIndex = (position.get() / chunkSize).toInt()
        if (chunkIndex >= maxChunksPerKey) return
        putChunk(key, chunkIndex, data, ttl)
        position.addAndGet(data.size.toLong())
    }

    fun getStream(key: String): Sequence<ByteArray> = sequence {
        var index = 0
        while (true) {
            val chunk = getChunk(key, index) ?: break
            if (chunk.isEmpty()) break
            yield(chunk)
            index++
        }
    }

    fun readFully(key: String): ByteArray {
        val chunks = chunkStore[key] ?: return ByteArray(0)
        val buffers = mutableListOf<ByteArray>()
        var totalSize = 0
        for (entry in chunks) {
            if (entry.value.size > 0 && !entry.isExpired()) {
                buffers.add(entry.value)
                totalSize += entry.value.size
            }
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (buf in buffers) {
            buf.copyInto(result, offset)
            offset += buf.size
        }
        return result
    }

    fun markComplete(key: String) {
        streamPositions.computeIfAbsent(key) { AtomicLong(0) }
    }

    fun getStreamInfo(key: String): StreamInfo? {
        val chunks = chunkStore[key] ?: return null
        val validChunks = chunks.filter { it.value.size > 0 && !it.isExpired() }
        return StreamInfo(
            key = key,
            totalChunks = validChunks.size,
            totalBytes = validChunks.sumOf { it.value.size.toLong() },
            isComplete = validChunks.isNotEmpty()
        )
    }

    fun removeStream(key: String) {
        chunkStore.remove(key)
        streamPositions.remove(key)
    }

    fun clear() {
        chunkStore.clear()
        streamPositions.clear()
        totalBytesCached.set(0)
        totalChunksCached.set(0)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "streams" to chunkStore.size,
        "totalChunks" to totalChunksCached.get(),
        "totalBytes" to totalBytesCached.get(),
        "chunkSize" to chunkSize
    )

    private fun checkMemoryLimit(key: String) {
        val chunks = chunkStore[key] ?: return
        while (chunks.size > maxChunksPerKey) {
            val oldest = chunks.firstOrNull() ?: break
            totalBytesCached.addAndGet(-oldest.value.size.toLong())
            totalChunksCached.decrementAndGet()
            chunkStore.compute(key) { _, list ->
                list?.drop(1)
            }
        }
    }
}

class CacheWarmer<V>(
    private val name: String = "cache-warmer",
    private val asyncStore: AsyncCacheStore<V>,
    private val loader: suspend (String) -> V?,
    private val warmupConcurrency: Int = 4,
    private val warmupIntervalMs: Long = -1L
) {
    private val logger = LoggerFactory.getLogger("CacheWarmer-$name")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmCount = AtomicLong(0)
    private val failCount = AtomicLong(0)
    private val isWarming = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun warmUp(keys: Collection<String>) {
        if (!isWarming.compareAndSet(false, true)) return
        logger.info("Warming up cache {} with {} keys", name, keys.size)

        val semaphore = kotlinx.coroutines.semaphore.Semaphore(warmupConcurrency)
        coroutineScope {
            keys.map { key ->
                async {
                    semaphore.acquire()
                    try {
                        val value = loader(key)
                        if (value != null) {
                            asyncStore.putAsync(key, value)
                            warmCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        failCount.incrementAndGet()
                        logger.warn("Warmup failed for key: {}", key, e)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        logger.info("Warmup complete for {}: {}/{} loaded, {} failed", name, warmCount.get(), keys.size, failCount.get())
        isWarming.set(false)
    }

    suspend fun warmUpByPattern(keys: Sequence<String>) {
        warmUp(keys.toList())
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "warmed" to warmCount.get(),
        "failed" to failCount.get(),
        "isWarming" to isWarming.get()
    )

    fun shutdown() { scope.cancel() }
}

class MultiLevelCache<V>(
    private val name: String = "multi-level",
    private val l1Cache: AsyncCacheStore<V>,
    private val l2Cache: AsyncCacheStore<V>? = null,
    private val l3Cache: AsyncCacheStore<V>? = null
) {
    private val logger = LoggerFactory.getLogger("MultiLevelCache-$name")
    private val l1Hits = AtomicLong(0)
    private val l2Hits = AtomicLong(0)
    private val l3Hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val totalTimeNs = AtomicLong(0)
    private val totalRequests = AtomicLong(0)

    suspend fun get(key: String): V? {
        val start = System.nanoTime()
        totalRequests.incrementAndGet()

        val l1 = l1Cache.getAsync(key)
        if (l1 != null) { l1Hits.incrementAndGet(); recordTime(start); return l1 }

        val l2 = l2Cache?.getAsync(key)
        if (l2 != null) {
            l2Hits.incrementAndGet()
            l1Cache.putAsync(key, l2)
            recordTime(start)
            return l2
        }

        val l3 = l3Cache?.getAsync(key)
        if (l3 != null) {
            l3Hits.incrementAndGet()
            l1Cache.putAsync(key, l3)
            l2Cache?.putAsync(key, l3)
            recordTime(start)
            return l3
        }

        misses.incrementAndGet()
        recordTime(start)
        return null
    }

    suspend fun set(key: String, value: V) {
        l1Cache.putAsync(key, value)
        l2Cache?.putAsync(key, value)
        l3Cache?.putAsync(key, value)
    }

    suspend fun evict(key: String) {
        l1Cache.removeAsync(key)
        l2Cache?.removeAsync(key)
        l3Cache?.removeAsync(key)
    }

    fun getHitRate(): Double {
        val total = l1Hits.get() + l2Hits.get() + l3Hits.get() + misses.get()
        return if (total > 0) (l1Hits.get() + l2Hits.get() + l3Hits.get()).toDouble() / total else 0.0
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "l1Hits" to l1Hits.get(),
        "l2Hits" to l2Hits.get(),
        "l3Hits" to l3Hits.get(),
        "misses" to misses.get(),
        "hitRate" to getHitRate(),
        "totalTimeAvgMs" to if (totalRequests.get() > 0) totalTimeNs.get().toDouble() / totalRequests.get() / 1_000_000.0 else 0.0
    )

    private fun recordTime(start: Long) {
        totalTimeNs.addAndGet(System.nanoTime() - start)
    }
}

class TtlCache<V>(
    private val name: String = "ttl-cache",
    private val defaultTtlMs: Long = 60000L,
    private val maxSize: Int = 10000,
    private val cleanupIntervalMs: Long = 10000L
) {
    private data class TimedEntry<V>(
        val value: V,
        val expiryTime: Long
    )

    private val cache = ConcurrentHashMap<String, TimedEntry<V>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                cleanup()
            }
        }
    }

    fun get(key: String): V? {
        val entry = cache[key] ?: run { misses.incrementAndGet(); return null }
        if (System.currentTimeMillis() > entry.expiryTime) {
            cache.remove(key)
            misses.incrementAndGet()
            return null
        }
        hits.incrementAndGet()
        return entry.value
    }

    fun set(key: String, value: V, ttlMs: Long = defaultTtlMs) {
        while (cache.size >= maxSize) {
            val oldest = cache.minByOrNull { it.value.expiryTime }?.key ?: break
            cache.remove(oldest)
        }
        cache[key] = TimedEntry(value, System.currentTimeMillis() + ttlMs)
    }

    fun remove(key: String) { cache.remove(key) }
    fun clear() { cache.clear() }
    fun size(): Int = cache.size

    fun getHitRate(): Double {
        val total = hits.get() + misses.get()
        return if (total > 0) hits.get().toDouble() / total else 0.0
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val expired = cache.filter { it.value.expiryTime <= now }.keys
        expired.forEach { cache.remove(it) }
    }

    fun shutdown() { scope.cancel() }
}
