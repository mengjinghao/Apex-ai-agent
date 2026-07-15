package com.apex.agent.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NetworkOptimizer(private val name: String = "network-opt") {
    data class NetworkConfig(
        val maxConnections: Int = 16,
        val connectionTimeoutMs: Long = 10000L,
        val readTimeoutMs: Long = 30000L,
        val writeTimeoutMs: Long = 30000L,
        val retryCount: Int = 3,
        val retryDelayMs: Long = 1000L,
        val enableKeepAlive: Boolean = true,
        val enableCompression: Boolean = true,
        val dnsCacheSize: Int = 100,
        val maxRedirects: Int = 5
    )

    data class ConnectionMetrics(
        val activeConnections: Int,
        val totalConnections: Long,
        val failedConnections: Long,
        val averageLatencyMs: Double,
        val p95LatencyMs: Double,
        val p99LatencyMs: Double,
        val bytesSent: Long,
        val bytesReceived: Long,
        val throughputPerSecond: Double,
        val errorRate: Double
    )

    data class EndpointHealth(
        val url: String,
        val isReachable: Boolean,
        val latencyMs: Long,
        val lastChecked: Long,
        val consecutiveFailures: Int,
        val statusCode: Int
    )
        private val logger = LoggerFactory.getLogger("NetworkOptimizer-$name")
        private var config = NetworkConfig()
        private val activeConnections = AtomicInteger(0)
        private val totalConnections = AtomicLong(0)
        private val failedConnections = AtomicLong(0)
        private val bytesSent = AtomicLong(0)
        private val bytesReceived = AtomicLong(0)
        private val connectionLatencies = ConcurrentLinkedQueue<Long>()
        private val maxLatencySamples = 1000
    private val endpointHealth = ConcurrentHashMap<String, EndpointHealth>()
        private val dnsCache = ConcurrentHashMap<String, List<String>>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun configure(newConfig: NetworkConfig) {
        this.config = newConfig
        logger.info("Network optimizer configured: {}", newConfig)
    }

    suspend fun <T> executeWithRetry(url: String, block: suspend () -> T): T {
        var lastException: Exception? = null
        var delay = config.retryDelayMs

        for (attempt in 0..config.retryCount) {
            try {
                activeConnections.incrementAndGet()
                totalConnections.incrementAndGet()
        val start = System.nanoTime()
        val result = block()
                recordLatency(System.nanoTime() - start)
                recordEndpointSuccess(url)
        return result
            } catch (e: Exception) {
                lastException = e
                failedConnections.incrementAndGet()
                recordEndpointFailure(url)
        if (attempt < config.retryCount) {
                    logger.debug("Retry {}/{} for {}: {}", attempt + 1, config.retryCount, url, e.message)
                    delay(delay)
                    delay = (delay * 2).coerceAtMost(30000L)
                }
            } finally {
                activeConnections.decrementAndGet()
            }
        }
        throw lastException ?: RuntimeException("Network operation failed after ${config.retryCount} retries")
    }
        fun recordBytesSent(bytes: Long) { bytesSent.addAndGet(bytes) }
        fun recordBytesReceived(bytes: Long) { bytesReceived.addAndGet(bytes) }
        fun registerEndpoint(url: String) {
        endpointHealth[url] = EndpointHealth(url, true, 0, System.currentTimeMillis(), 0, 200)
    }
        fun getEndpointHealth(url: String): EndpointHealth? = endpointHealth[url]

    fun getUnhealthyEndpoints(): List<EndpointHealth> {
        return endpointHealth.values.filter { !it.isReachable }
    }
        fun getMetrics(): ConnectionMetrics {
        val sorted = connectionLatencies.sorted()
        return ConnectionMetrics(
            activeConnections = activeConnections.get(),
            totalConnections = totalConnections.get(),
            failedConnections = failedConnections.get(),
            averageLatencyMs = if (sorted.isNotEmpty()) sorted.average() / 1_000_000.0 else 0.0,
            p95LatencyMs = sorted.getOrNull((sorted.size * 0.95).toInt())?.div(1_000_000.0) ?: 0.0,
            p99LatencyMs = sorted.getOrNull((sorted.size * 0.99).toInt())?.div(1_000_000.0) ?: 0.0,
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            throughputPerSecond = bytesReceived.get().toDouble() / (System.currentTimeMillis() / 1000.0).coerceAtLeast(1.0),
            errorRate = if (totalConnections.get() > 0) failedConnections.get().toDouble() / totalConnections.get() else 0.0
        )
    }
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "activeConnections" to activeConnections.get(),
        "totalConnections" to totalConnections.get(),
        "failedConnections" to failedConnections.get(),
        "endpoints" to endpointHealth.size,
        "dnsCacheSize" to dnsCache.size,
        "retryCount" to config.retryCount,
        "timeoutMs" to config.connectionTimeoutMs
    )
        fun shutdown() { scope.cancel() }
        private fun recordLatency(durationNs: Long) {
        connectionLatencies.add(durationNs)
        while (connectionLatencies.size > maxLatencySamples) connectionLatencies.poll()
    }
        private fun recordEndpointSuccess(url: String) {
        endpointHealth.computeIfPresent(url) { _, health ->
            health.copy(isReachable = true, latencyMs = 0, lastChecked = System.currentTimeMillis(), consecutiveFailures = 0)
        }
    }
        private fun recordEndpointFailure(url: String) {
        endpointHealth.computeIfPresent(url) { _, health ->
            health.copy(isReachable = false, lastChecked = System.currentTimeMillis(), consecutiveFailures = health.consecutiveFailures + 1)
        }
    }
}

class DnsOptimizer(private val name: String = "dns-opt") {
    data class DnsRecord(
        val hostname: String,
        val addresses: List<String>,
        val resolvedAt: Long,
        val ttlMs: Long,
        val resolutionTimeMs: Long
    )
        private val cache = ConcurrentHashMap<String, DnsRecord>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val logger = LoggerFactory.getLogger("DnsOptimizer-$name")
        private val totalResolutions = AtomicLong(0)
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)
        private val resolutionTimes = ConcurrentLinkedQueue<Long>()
        private val defaultTtlMs = 300000L
    private val maxCacheSize = 500

    suspend fun resolve(hostname: String): List<String> {
        totalResolutions.incrementAndGet()
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() < cached.resolvedAt + cached.ttlMs) {
            cacheHits.incrementAndGet()
        return cached.addresses
        }

        cacheMisses.incrementAndGet()
        val start = System.nanoTime()
        return try {
            val addresses = performDnsResolution(hostname)
        val elapsed = System.nanoTime() - start
            resolutionTimes.add(elapsed)
            while (resolutionTimes.size > 100) resolutionTimes.poll()

            while (cache.size >= maxCacheSize) {
                val oldest = cache.minByOrNull { it.value.resolvedAt }?.key ?: break
                cache.remove(oldest)
            }
        val record = DnsRecord(
                hostname = hostname,
                addresses = addresses,
                resolvedAt = System.currentTimeMillis(),
                ttlMs = defaultTtlMs,
                resolutionTimeMs = elapsed / 1_000_000
            )
            cache[hostname] = record
            addresses
        } catch (e: Exception) {
            cached?.addresses ?: throw e
        }
    }
        fun invalidate(hostname: String) { cache.remove(hostname) }
        fun clear() { cache.clear() }
        fun getMetrics(): Map<String, Any> {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val total = hits + misses
        val avgResTime = if (resolutionTimes.isNotEmpty()) resolutionTimes.average() / 1_000_000.0 else 0.0
        return mapOf(
            "name" to name,
            "cacheSize" to cache.size,
            "hitRate" to if (total > 0) hits.toDouble() / total else 0.0,
            "avgResolutionTimeMs" to avgResTime,
            "totalResolutions" to totalResolutions.get()
        )
    }
        private suspend fun performDnsResolution(hostname: String): List<String> {
        return try {
            val addr = java.net.InetAddress.getAllByName(hostname)
            addr.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.warn("DNS resolution failed for {}", hostname, e)
        throw e
        }
    }
        fun shutdown() { scope.cancel() }
}

class ConnectionPoolOptimizer(private val name: String = "conn-pool") {
    data class PooledConnection(
        val id: Int,
        val host: String,
        val port: Int,
        val createdAt: Long,
        var lastUsedAt: Long,
        val isActive: Boolean
    )

    data class PoolMetrics(
        val totalConnections: Int,
        val activeConnections: Int,
        val idleConnections: Int,
        val pendingRequests: Int,
        val averageConnectionAgeMs: Double,
        val averageAcquireTimeMs: Double,
        val peakActive: Int,
        val totalCreated: Long,
        val totalDestroyed: Long
    )
        private val logger = LoggerFactory.getLogger("ConnectionPoolOptimizer-$name")
        private val connections = ConcurrentHashMap<Int, PooledConnection>()
        private val idleConnections = ConcurrentLinkedQueue<Int>()
        private val activeSet = ConcurrentHashMap.newKeySet<Int>()
        private val maxPoolSize = 16
    private val minPoolSize = 2
    private val maxIdleTimeMs = 60000L
    private val connectionCounter = AtomicInteger(0)
        private val totalCreated = AtomicLong(0)
        private val totalDestroyed = AtomicLong(0)
        private val pendingRequests = AtomicInteger(0)
        private val acquireTimeNs = AtomicLong(0)
        private val acquireCount = AtomicInteger(0)
        private val peakActive = AtomicInteger(0)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        for (i in 0 until minPoolSize) {
            val id = connectionCounter.incrementAndGet()
        val conn = PooledConnection(id, "pool-$name", 0, System.currentTimeMillis(), System.currentTimeMillis(), false)
            connections[id] = conn
            idleConnections.add(id)
            totalCreated.incrementAndGet()
        }

        scope.launch {
            while (true) {
                delay(30000)
                cleanupIdleConnections()
            }
        }
    }

    suspend fun acquire(): PooledConnection {
        pendingRequests.incrementAndGet()
        val start = System.nanoTime()
        var connId = idleConnections.poll()
        if (connId == null && connections.size < maxPoolSize) {
            connId = connectionCounter.incrementAndGet()
        val conn = PooledConnection(connId, "pool-$name", 0, System.currentTimeMillis(), System.currentTimeMillis(), true)
            connections[connId] = conn
            totalCreated.incrementAndGet()
        }
        if (connId != null) {
            activeSet.add(connId)
            connections.computeIfPresent(connId) { _, c -> c.copy(lastUsedAt = System.currentTimeMillis(), isActive = true) }
            pendingRequests.decrementAndGet()
        val elapsed = System.nanoTime() - start
            acquireTimeNs.addAndGet(elapsed)
            acquireCount.incrementAndGet()
            updatePeakActive()
        return connections[connId]!!
        }

        while (true) {
            connId = idleConnections.poll()
        if (connId != null) {
                activeSet.add(connId)
                connections.computeIfPresent(connId) { _, c -> c.copy(lastUsedAt = System.currentTimeMillis(), isActive = true) }
                pendingRequests.decrementAndGet()
        val elapsed = System.nanoTime() - start
                acquireTimeNs.addAndGet(elapsed)
                acquireCount.incrementAndGet()
                updatePeakActive()
        return connections[connId]!!
            }
        if (connections.size < maxPoolSize) {
                connId = connectionCounter.incrementAndGet()
        val conn = PooledConnection(connId, "pool-$name", 0, System.currentTimeMillis(), System.currentTimeMillis(), true)
                connections[connId] = conn
                totalCreated.incrementAndGet()
                activeSet.add(connId)
                pendingRequests.decrementAndGet()
                acquireTimeNs.addAndGet(System.nanoTime() - start)
                acquireCount.incrementAndGet()
                updatePeakActive()
        return conn
            }
            delay(10)
        }
    }
        fun release(connectionId: Int) {
        activeSet.remove(connectionId)
        connections.computeIfPresent(connectionId) { _, c -> c.copy(lastUsedAt = System.currentTimeMillis(), isActive = false) }
        if (connections.size > minPoolSize && idleConnections.size > minPoolSize) {
            connections.remove(connectionId)
            totalDestroyed.incrementAndGet()
        } else {
            idleConnections.add(connectionId)
        }
    }
        fun getMetrics(): PoolMetrics {
        val active = activeSet.size
        val idle = idleConnections.size
        val allConnections = connections.values.toList()
        val avgAge = if (allConnections.isNotEmpty())
            allConnections.sumOf { System.currentTimeMillis() - it.createdAt }.toDouble() / allConnections.size else 0.0
        val avgAcquireMs = if (acquireCount.get() > 0)
            acquireTimeNs.get().toDouble() / acquireCount.get() / 1_000_000.0 else 0.0
        return PoolMetrics(
            totalConnections = connections.size,
            activeConnections = active,
            idleConnections = idle,
            pendingRequests = pendingRequests.get(),
            averageConnectionAgeMs = avgAge,
            averageAcquireTimeMs = avgAcquireMs,
            peakActive = peakActive.get(),
            totalCreated = totalCreated.get(),
            totalDestroyed = totalDestroyed.get()
        )
    }
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "totalConnections" to connections.size,
        "active" to activeSet.size,
        "idle" to idleConnections.size,
        "peakActive" to peakActive.get(),
        "totalCreated" to totalCreated.get(),
        "totalDestroyed" to totalDestroyed.get(),
        "maxPoolSize" to maxPoolSize,
        "minPoolSize" to minPoolSize
    )
        fun close() {
        scope.cancel()
        connections.clear()
        idleConnections.clear()
        activeSet.clear()
    }
        private fun cleanupIdleConnections() {
        val cutoff = System.currentTimeMillis() - maxIdleTimeMs
        val toRemove = mutableListOf<Int>()
        for (connId in idleConnections) {
            val conn = connections[connId] ?: continue
            if (conn.lastUsedAt < cutoff && idleConnections.size > minPoolSize) {
                toRemove.add(connId)
            }
        }
        for (connId in toRemove) {
            if (idleConnections.remove(connId)) {
                connections.remove(connId)
                totalDestroyed.incrementAndGet()
            }
        }
    }
        private fun updatePeakActive() {
        val current = activeSet.size
        var peak = peakActive.get()
        while (current > peak && !peakActive.compareAndSet(peak, current)) {
            peak = peakActive.get()
        }
    }
}

class RetryWithBackoff(private val name: String = "retry-backoff") {
    data class RetryPolicy(
        val maxRetries: Int = 3,
        val initialDelayMs: Long = 100L,
        val maxDelayMs: Long = 10000L,
        val backoffMultiplier: Double = 2.0,
        val jitterFactor: Double = 0.1
    )
        private val logger = LoggerFactory.getLogger("RetryWithBackoff-$name")
        private var policy = RetryPolicy()
        private val totalAttempts = AtomicLong(0)
        private val totalSuccesses = AtomicLong(0)
        private val totalFailures = AtomicLong(0)
        fun configure(policy: RetryPolicy) { this.policy = policy }

    suspend fun <T> executeWithBackoff(block: suspend () -> T): T {
        var lastError: Exception? = null
        var delay = policy.initialDelayMs

        for (attempt in 0..policy.maxRetries) {
            totalAttempts.incrementAndGet()
            try {
                val result = block()
                totalSuccesses.incrementAndGet()
        return result
            } catch (e: Exception) {
                lastError = e
                if (attempt == policy.maxRetries) {
                    totalFailures.incrementAndGet()
        throw e
                }
        val jitter = delay * policy.jitterFactor * (Math.random() - 0.5)
        val actualDelay = (delay + jitter).toLong().coerceIn(1, policy.maxDelayMs)
                logger.debug("Retry {}/{} after {}ms: {}", attempt + 1, policy.maxRetries, actualDelay, e.message)
                delay(actualDelay)
                delay = (delay * policy.backoffMultiplier).toLong().coerceAtMost(policy.maxDelayMs)
            }
        }
        throw lastError ?: RuntimeException("Retry failed")
    }
        fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "totalAttempts" to totalAttempts.get(),
        "successRate" to if (totalAttempts.get() > 0)
            totalSuccesses.get().toDouble() / totalAttempts.get() else 0.0,
        "totalFailures" to totalFailures.get(),
        "policy" to mapOf(
            "maxRetries" to policy.maxRetries,
            "initialDelayMs" to policy.initialDelayMs,
            "maxDelayMs" to policy.maxDelayMs,
            "backoffMultiplier" to policy.backoffMultiplier
        )
    )

    companion object {
        fun linearBackoff(baseMs: Long, maxMs: Long): RetryPolicy {
            return RetryPolicy(maxRetries = 5, initialDelayMs = baseMs, maxDelayMs = maxMs, backoffMultiplier = 1.0)
        }
        fun exponentialBackoff(baseMs: Long = 100L, maxMs: Long = 30000L): RetryPolicy {
            return RetryPolicy(maxRetries = 5, initialDelayMs = baseMs, maxDelayMs = maxMs, backoffMultiplier = 2.0)
        }
        fun fibonacciBackoff(baseMs: Long = 100L, maxMs: Long = 30000L): RetryPolicy {
            return RetryPolicy(maxRetries = 8, initialDelayMs = baseMs, maxDelayMs = maxMs, backoffMultiplier = 1.618)
        }
        fun noRetry(): RetryPolicy {
            return RetryPolicy(maxRetries = 0)
        }
    }
}

class BandwidthOptimizer(private val name: String = "bandwidth") {
    data class BandwidthStats(
        val currentBps: Double,
        val peakBps: Double,
        val averageBps: Double,
        val totalBytesTransferred: Long,
        val throttledBytes: Long,
        val connectionCount: Int
    )
        private val bytesTransferred = AtomicLong(0)
        private val bytesThrottled = AtomicLong(0)
        private val activeConnections = AtomicInteger(0)
        private val peakBps = AtomicLong(0)
        private val throughputSamples = ConcurrentLinkedQueue<Long>()
        private val maxSamples = 100
    private var maxBandwidthBps: Long = -1L
    private var minBandwidthBps: Long = 0L

    fun setBandwidthLimit(maxBps: Long, minBps: Long = 0L) {
        maxBandwidthBps = maxBps
        minBandwidthBps = minBps
    }
        fun recordTransfer(bytes: Long) {
        bytesTransferred.addAndGet(bytes)
        val now = System.nanoTime()
        throughputSamples.add(now)
        while (throughputSamples.size > maxSamples) throughputSamples.poll()
        updatePeakBps(now)
    }
        fun shouldThrottle(bytes: Long): Boolean {
        if (maxBandwidthBps <= 0) return false
        val currentBps = getCurrentBps()
        return currentBps + bytes > maxBandwidthBps
    }
        fun getCurrentBps(): Double {
        val now = System.nanoTime()
        val windowStart = now - 1_000_000_000L
        val recentCount = throughputSamples.count { it >= windowStart }
        return recentCount.toDouble() * bytesTransferred.get().toDouble() / (now - windowStart + 1) * 1_000_000_000.0
    }
        fun getStats(): BandwidthStats {
        val currentBps = getCurrentBps()
        return BandwidthStats(
            currentBps = currentBps,
            peakBps = peakBps.get().toDouble(),
            averageBps = bytesTransferred.get().toDouble() / (System.currentTimeMillis() / 1000.0).coerceAtLeast(1.0),
            totalBytesTransferred = bytesTransferred.get(),
            throttledBytes = bytesThrottled.get(),
            connectionCount = activeConnections.get()
        )
    }
        private fun updatePeakBps(now: Long) {
        val current = throughputSamples.count { it >= now - 1_000_000_000L }
        var peak = peakBps.get()
        while (current > peak && !peakBps.compareAndSet(peak, current)) {
            peak = peakBps.get()
        }
    }
}

class RequestBatcher<T, R>(
    private val name: String = "request-batcher",
    private val batchSize: Int = 10,
    private val maxWaitMs: Long = 100L,
    private val processor: suspend (List<T>) -> List<R>
) {
    private val pending = ConcurrentLinkedQueue<Pair<String, T>>()
        private val deferreds = ConcurrentHashMap<String, CompletableDeferred<R>>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val processed = AtomicLong(0)
        private val batches = AtomicLong(0)
        private val totalTimeNs = AtomicLong(0)
        private val totalItems = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(maxWaitMs)
                processBatch()
            }
        }
    }

    suspend fun submit(key: String, item: T): R {
        val deferred = CompletableDeferred<R>()
        deferreds[key] = deferred
        pending.add(key to item)
        if (pending.size >= batchSize) {
            scope.launch { processBatch() }
        }
        return deferred.await()
    }

    suspend fun submitAll(items: List<Pair<String, T>>): List<R> {
        val deferredList = items.map { (key, _) ->
            val d = CompletableDeferred<R>()
            deferreds[key] = d
            key to d
        }
        pending.addAll(items)
        if (pending.size >= batchSize) {
            processBatch()
        }
        return deferredList.map { it.second.await() }
    }
        fun shutdown() { scope.cancel() }
        private suspend fun processBatch() {
        if (pending.isEmpty()) return
        val batch = mutableListOf<Pair<String, T>>()
        while (batch.size < batchSize) {
            pending.poll()?.let { batch.add(it) } ?: break
        }
        if (batch.isEmpty()) return

        batches.incrementAndGet()
        totalItems.addAndGet(batch.size.toLong())
        val start = System.nanoTime()

        try {
            val results = processor(batch.map { it.second })
        for ((i, r) in results.withIndex()) {
                deferreds.remove(batch[i].first)?.complete(r)
            }
            processed.addAndGet(batch.size.toLong())
        } catch (e: Exception) {
            for ((key, _) in batch) {
                deferreds.remove(key)?.completeExceptionally(e)
            }
        }

        totalTimeNs.addAndGet(System.nanoTime() - start)
    }
}

class CircuitBreakerV2(private val name: String = "cb-v2") {
    enum class State { CLOSED, OPEN, HALF_OPEN }
    data class Config(
        val failureThreshold: Int = 5,
        val successThreshold: Int = 3,
        val openTimeoutMs: Long = 30000L,
        val halfOpenMaxCalls: Int = 3,
        val rollingWindowMs: Long = 60000L
    )
        private val state = java.util.concurrent.atomic.AtomicReference(State.CLOSED)
        private val failureCount = AtomicInteger(0)
        private val successCount = AtomicInteger(0)
        private val totalCalls = AtomicLong(0)
        private val rejectedCalls = AtomicLong(0)
        private val stateChangeTime = AtomicLong(System.currentTimeMillis())
        private val failures = ConcurrentLinkedQueue<Long>()
        private var config = Config()
        fun configure(cfg: Config) { config = cfg }
        fun <T> call(block: () -> T): T {
        totalCalls.incrementAndGet()
        val currentState = state.get()
        if (currentState == State.OPEN) {
            if (System.currentTimeMillis() - stateChangeTime.get() >= config.openTimeoutMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    stateChangeTime.set(System.currentTimeMillis())
                }
            } else {
                rejectedCalls.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is OPEN")
            }
        }
        if (state.get() == State.HALF_OPEN && totalCalls.get() % (config.halfOpenMaxCalls + 1) != 0L) {
            rejectedCalls.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is HALF_OPEN")
        }
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
        throw e
        }
    }
        fun forceOpen() { state.set(State.OPEN); stateChangeTime.set(System.currentTimeMillis()) }
        fun reset() { state.set(State.CLOSED); failureCount.set(0); successCount.set(0); failures.clear() }
        fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "state" to state.get().toString(),
        "totalCalls" to totalCalls.get(),
        "rejectedCalls" to rejectedCalls.get(),
        "failureCount" to failureCount.get(),
        "successCount" to successCount.get()
    )
        private fun onSuccess() {
        successCount.incrementAndGet()
        if (state.get() == State.HALF_OPEN && successCount.get() >= config.successThreshold) {
            state.set(State.CLOSED)
            failureCount.set(0)
            successCount.set(0)
            stateChangeTime.set(System.currentTimeMillis())
        }
    }
        private fun onFailure() {
        failures.add(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        while (failures.peek() != null && failures.peek() < now - config.rollingWindowMs) failures.poll()
        if (state.get() == State.CLOSED && failures.size >= config.failureThreshold) {
            state.set(State.OPEN)
            failureCount.set(0)
            successCount.set(0)
            stateChangeTime.set(System.currentTimeMillis())
        } else if (state.get() == State.HALF_OPEN) {
            state.set(State.OPEN)
            stateChangeTime.set(System.currentTimeMillis())
        }
    }
        class CircuitBreakerOpenException(message: String) : RuntimeException(message)
}

class AsyncNetworkClient(private val name: String = "async-net") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val clientCount = AtomicInteger(0)
        private val totalRequests = AtomicLong(0)
        private val activeRequests = AtomicInteger(0)
        private val timeoutCount = AtomicLong(0)
        private val errorCount = AtomicLong(0)
        private val requestTimes = ConcurrentLinkedQueue<Long>()

    suspend fun get(url: String, timeoutMs: Long = 30000L): String {
        totalRequests.incrementAndGet()
        activeRequests.incrementAndGet()
        val start = System.nanoTime()
        return try {
            withTimeout(timeoutMs) {
                val result = performHttpGet(url)
                requestTimes.add(System.nanoTime() - start)
                while (requestTimes.size > 500) requestTimes.poll()
                result
            }
        } catch (e: TimeoutCancellationException) {
            timeoutCount.incrementAndGet()
        throw TimeoutException("Request to $url timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            errorCount.incrementAndGet()
        throw e
        } finally {
            activeRequests.decrementAndGet()
        }
    }

    suspend fun post(url: String, body: String, timeoutMs: Long = 30000L): String {
        totalRequests.incrementAndGet()
        activeRequests.incrementAndGet()
        return try {
            withTimeout(timeoutMs) { performHttpPost(url, body) }
        } catch (e: Exception) {
            errorCount.incrementAndGet()
        throw e
        } finally {
            activeRequests.decrementAndGet()
        }
    }
        fun getMetrics(): Map<String, Any> {
        val sorted = requestTimes.sorted()
        return mapOf(
            "name" to name,
            "totalRequests" to totalRequests.get(),
            "activeRequests" to activeRequests.get(),
            "timeouts" to timeoutCount.get(),
            "errors" to errorCount.get(),
            "avgLatencyMs" to if (sorted.isNotEmpty()) sorted.average() / 1_000_000.0 else 0.0,
            "p50Ms" to sorted.getOrNull(sorted.size / 2)?.div(1_000_000.0) ?: 0.0,
            "p95Ms" to sorted.getOrNull((sorted.size * 0.95).toInt())?.div(1_000_000.0) ?: 0.0
        )
    }
        fun shutdown() { scope.cancel() }
        private suspend fun performHttpGet(url: String): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.inputStream.bufferedReader().readText()
        }
    }
        private suspend fun performHttpPost(url: String, body: String): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.outputStream.write(body.toByteArray())
            connection.inputStream.bufferedReader().readText()
        }
    }
        class TimeoutException(message: String) : RuntimeException(message)
}
