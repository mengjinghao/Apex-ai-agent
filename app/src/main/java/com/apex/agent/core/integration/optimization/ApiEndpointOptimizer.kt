package com.apex.agent.core.integration.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class ApiEndpointProfile(
    val path: String,
    val method: HttpMethod,
    val averageLatencyMs: Long = 100L,
    val p95LatencyMs: Long = 300L,
    val requestRate: Double = 1.0,
    val timeoutMs: Long = 10000L,
    val retryCount: Int = 0,
    val cacheable: Boolean = true,
    val cacheTtlMs: Long = 60000L,
    val rateLimitPerSecond: Int = 10,
    val payloadSizeBytes: Int = 1024
)

enum class HttpMethod { GET, POST, PUT, DELETE, PATCH, HEAD }

data class ApiOptimizationSuggestion(
    val path: String,
    val method: HttpMethod,
    val suggestionType: ApiSuggestionType,
    val description: String,
    val expectedImprovementPercent: Double,
    val priority: Int
)

enum class ApiSuggestionType {
    ADD_CACHE, INCREASE_CACHE_TTL, ENABLE_COMPRESSION, ADD_RATE_LIMIT,
    ENABLE_KEEPALIVE, BATCH_REQUESTS, REDUCE_TIMEOUT, INCREASE_RETRY_COUNT,
    ADD_CIRCUIT_BREAKER, ENABLE_PREFETCHING
}

data class ApiMetrics(
    val path: String,
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val averageLatencyMs: Double,
    val p95LatencyMs: Double,
    val p99LatencyMs: Double,
    val errorRate: Double,
    val requestRatePerSecond: Double,
    val cacheHitRate: Double,
    val averagePayloadSize: Int
)

data class CircuitBreakerState(
    val name: String,
    val state: CircuitState,
    val failureCount: Int,
    val successCount: Int,
    val lastFailureTimeMs: Long,
    val lastSuccessTimeMs: Long,
    val openSinceMs: Long? = null
)

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

data class RateLimitStatus(
    val path: String,
    val currentRequestsPerSecond: Double,
    val limitPerSecond: Int,
    val isLimited: Boolean,
    val remainingTokens: Int,
    val resetTimeMs: Long
)

data class IntegrationHealth(
    val name: String,
    val isAvailable: Boolean,
    val lastCheckMs: Long,
    val latencyMs: Long,
    val consecutiveFailures: Int,
    val errorMessage: String? = null
)

data class ApiPoolConfig(
    val maxConnectionsPerRoute: Int = 5,
    val maxTotalConnections: Int = 20,
    val keepAliveMs: Long = 300000L,
    val connectionTimeoutMs: Long = 5000L,
    val readTimeoutMs: Long = 10000L,
    val enableCompression: Boolean = true,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 2,
    val circuitBreakerThreshold: Int = 5,
    val circuitBreakerHalfOpenMaxRequests: Int = 3,
    val rateLimitDefault: Int = 30
)

class ApiEndpointOptimizer private constructor() {

    private val endpointProfiles = ConcurrentHashMap<String, ApiEndpointProfile>()
    private val requestCounts = ConcurrentHashMap<String, AtomicLong>()
    private val successCounts = ConcurrentHashMap<String, AtomicLong>()
    private val failureCounts = ConcurrentHashMap<String, AtomicLong>()
    private val latencySamples = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val rateLimitBuckets = ConcurrentHashMap<String, TokenBucket>()
    private val cacheHits = ConcurrentHashMap<String, AtomicLong>()
    private val cacheMisses = ConcurrentHashMap<String, AtomicLong>()
    private val config = ApiPoolConfig()
    private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: ApiEndpointOptimizer? = null

        fun getInstance(): ApiEndpointOptimizer {
            return instance ?: synchronized(this) {
                instance ?: ApiEndpointOptimizer().also { instance = it }
            }
        }

        private const val LATENCY_HISTORY_SIZE = 200
        private const val METRICS_WINDOW_MS = 60000L
        private const val DEFAULT_RATE_LIMIT = 30
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        registerCommonEndpoints()
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(30000L)
                analyzeAndSuggest()
            }
        }
    }

    private fun registerCommonEndpoints() {
        registerEndpoint(ApiEndpointProfile("/api/chat", HttpMethod.POST, 2000L, 5000L, 2.0, 30000L, cacheable = false))
        registerEndpoint(ApiEndpointProfile("/api/search", HttpMethod.GET, 500L, 1500L, 5.0, 10000L, cacheable = true, cacheTtlMs = 120000L))
        registerEndpoint(ApiEndpointProfile("/api/analyze", HttpMethod.POST, 3000L, 8000L, 1.0, 45000L, cacheable = false))
        registerEndpoint(ApiEndpointProfile("/api/config", HttpMethod.GET, 100L, 300L, 10.0, 5000L, cacheable = true, cacheTtlMs = 300000L))
        registerEndpoint(ApiEndpointProfile("/api/feedback", HttpMethod.POST, 200L, 500L, 5.0, 10000L, cacheable = false))
        registerEndpoint(ApiEndpointProfile("/api/auth", HttpMethod.POST, 500L, 1000L, 3.0, 15000L, cacheable = false, rateLimitPerSecond = 5))
        registerEndpoint(ApiEndpointProfile("/api/files", HttpMethod.GET, 1000L, 3000L, 2.0, 20000L, cacheable = true, cacheTtlMs = 60000L))
        registerEndpoint(ApiEndpointProfile("/api/models", HttpMethod.GET, 300L, 800L, 5.0, 10000L, cacheable = true, cacheTtlMs = 600000L))
        registerEndpoint(ApiEndpointProfile("/api/tools", HttpMethod.GET, 200L, 500L, 8.0, 5000L, cacheable = true, cacheTtlMs = 120000L))
        registerEndpoint(ApiEndpointProfile("/api/health", HttpMethod.GET, 50L, 100L, 20.0, 3000L, cacheable = false))
    }

    fun registerEndpoint(profile: ApiEndpointProfile) {
        val key = endpointKey(profile.path, profile.method)
        endpointProfiles[key] = profile
        requestCounts[key] = AtomicLong(0)
        successCounts[key] = AtomicLong(0)
        failureCounts[key] = AtomicLong(0)
        latencySamples[key] = CopyOnWriteArrayList()
        cacheHits[key] = AtomicLong(0)
        cacheMisses[key] = AtomicLong(0)
        circuitBreakers[key] = CircuitBreakerState(key, CircuitState.CLOSED, 0, 0, 0, System.currentTimeMillis())
        rateLimitBuckets[key] = TokenBucket(profile.rateLimitPerSecond, profile.rateLimitPerSecond)
    }

    fun getEndpoint(path: String, method: HttpMethod): ApiEndpointProfile? {
        return endpointProfiles[endpointKey(path, method)]
    }

    fun getAllEndpoints(): List<ApiEndpointProfile> = endpointProfiles.values.toList()

    fun recordRequest(path: String, method: HttpMethod) {
        val key = endpointKey(path, method)
        requestCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordSuccess(path: String, method: HttpMethod, latencyMs: Long) {
        val key = endpointKey(path, method)
        successCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val samples = latencySamples.computeIfAbsent(key) { CopyOnWriteArrayList() }
        samples.add(latencyMs)
        if (samples.size > LATENCY_HISTORY_SIZE) samples.removeAt(0)

        circuitBreakers.compute(key) { _, state ->
            if (state != null) state.copy(
                state = CircuitState.CLOSED,
                successCount = state.successCount + 1,
                lastSuccessTimeMs = System.currentTimeMillis()
            ) else null
        }
    }

    fun recordFailure(path: String, method: HttpMethod, latencyMs: Long) {
        val key = endpointKey(path, method)
        failureCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()

        circuitBreakers.compute(key) { _, state ->
            if (state != null) {
                val newFailureCount = state.failureCount + 1
                val newState = when {
                    newFailureCount >= config.circuitBreakerThreshold -> CircuitState.OPEN
                    state.state == CircuitState.OPEN && state.openSinceMs != null &&
                        System.currentTimeMillis() - state.openSinceMs > 30000L -> CircuitState.HALF_OPEN
                    else -> state.state
                }
                state.copy(
                    state = newState,
                    failureCount = if (newState == CircuitState.OPEN) 0 else newFailureCount,
                    lastFailureTimeMs = System.currentTimeMillis(),
                    openSinceMs = if (newState == CircuitState.OPEN) System.currentTimeMillis() else state.openSinceMs
                )
            } else null
        }
    }

    fun recordCacheHit(path: String, method: HttpMethod) {
        val key = endpointKey(path, method)
        cacheHits.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordCacheMiss(path: String, method: HttpMethod) {
        val key = endpointKey(path, method)
        cacheMisses.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    fun isCircuitOpen(path: String, method: HttpMethod): Boolean {
        val key = endpointKey(path, method)
        val breaker = circuitBreakers[key] ?: return false
        when (breaker.state) {
            CircuitState.CLOSED -> false
            CircuitState.OPEN -> {
                if (breaker.openSinceMs != null && System.currentTimeMillis() - breaker.openSinceMs > 30000L) {
                    circuitBreakers[key] = breaker.copy(state = CircuitState.HALF_OPEN, openSinceMs = null)
                    false
                } else true
            }
            CircuitState.HALF_OPEN -> {
                if (breaker.successCount >= config.circuitBreakerHalfOpenMaxRequests) {
                    circuitBreakers[key] = breaker.copy(state = CircuitState.CLOSED, failureCount = 0)
                    false
                } else false
            }
        }
    }

    fun getCircuitBreakerState(path: String, method: HttpMethod): CircuitBreakerState? {
        return circuitBreakers[endpointKey(path, method)]
    }

    fun allowRequest(path: String, method: HttpMethod): Boolean {
        if (isCircuitOpen(path, method)) return false
        val key = endpointKey(path, method)
        val bucket = rateLimitBuckets[key] ?: return true
        val profile = endpointProfiles[key]
        val limit = profile?.rateLimitPerSecond ?: DEFAULT_RATE_LIMIT
        if (bucket.tryConsume(1, limit, limit)) true else false
    }

    fun getRateLimitStatus(path: String, method: HttpMethod): RateLimitStatus {
        val key = endpointKey(path, method)
        val bucket = rateLimitBuckets[key]
        val profile = endpointProfiles[key]
        RateLimitStatus(
            path = path,
            currentRequestsPerSecond = bucket?.let { profile?.rateLimitPerSecond?.toDouble()?.minus(it.currentTokens().toDouble()) } ?: 0.0,
            limitPerSecond = profile?.rateLimitPerSecond ?: DEFAULT_RATE_LIMIT,
            isLimited = !allowRequest(path, method),
            remainingTokens = bucket?.currentTokens() ?: 0,
            resetTimeMs = System.currentTimeMillis() + 1000L
        )
    }

    fun getMetrics(path: String, method: HttpMethod): ApiMetrics {
        val key = endpointKey(path, method)
        val requests = requestCounts[key]?.get() ?: 0
        val successes = successCounts[key]?.get() ?: 0
        val failures = failureCounts[key]?.get() ?: 0
        val samples = latencySamples[key]
        val avgLatency = if (samples != null && samples.isNotEmpty()) samples.average() else 0.0
        val sorted = samples?.sorted() ?: emptyList()
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val errorRate = if (requests > 0) failures.toDouble() / requests else 0.0
        val hits = cacheHits[key]?.get() ?: 0
        val misses = cacheMisses[key]?.get() ?: 0
        val cacheTotal = hits + misses
        val cacheHitRate = if (cacheTotal > 0) hits.toDouble() / cacheTotal else 0.0
        val ratePerSecond = requests.toDouble() / (METRICS_WINDOW_MS / 1000.0)
        ApiMetrics(
            path = path, totalRequests = requests, successfulRequests = successes,
            failedRequests = failures, averageLatencyMs = avgLatency, p95LatencyMs = p95,
            p99LatencyMs = p99, errorRate = errorRate, requestRatePerSecond = ratePerSecond,
            cacheHitRate = cacheHitRate, averagePayloadSize = endpointProfiles[key]?.payloadSizeBytes ?: 0
        )
    }

    fun getAllMetrics(): List<ApiMetrics> {
        endpointProfiles.keys.map { key ->
            val parts = key.split(":")
            val path = parts.getOrElse(0) { "/unknown" }
            val method = try { HttpMethod.valueOf(parts.getOrElse(1) { "GET" }) } catch (_: Exception) { HttpMethod.GET }
            getMetrics(path, method)
        }
    }

    fun suggestOptimizations(): List<ApiOptimizationSuggestion> {
        val suggestions = mutableListOf<ApiOptimizationSuggestion>()
        for ((key, profile) in endpointProfiles) {
            val parts = key.split(":")
            val path = parts[0]
            val method = try { HttpMethod.valueOf(parts[1]) } catch (_: Exception) { HttpMethod.GET }
            val metrics = getMetrics(path, method)
            val breaker = circuitBreakers[key]

            if (profile.cacheable && metrics.cacheHitRate < 0.3 && metrics.requestRatePerSecond > 2) {
                suggestions.add(ApiOptimizationSuggestion(path, method, ApiSuggestionType.ADD_CACHE,
                    "High request rate (${"%.1f".format(metrics.requestRatePerSecond)}/s) but low cache hit rate", 40.0, 5))
            }
            if (metrics.p95LatencyMs > metrics.averageLatencyMs * 3) {
                suggestions.add(ApiOptimizationSuggestion(path, method, ApiSuggestionType.ENABLE_COMPRESSION,
                    "High latency variance detected", 25.0, 4))
            }
            if (breaker?.state != CircuitState.CLOSED) {
                suggestions.add(ApiOptimizationSuggestion(path, method, ApiSuggestionType.ADD_CIRCUIT_BREAKER,
                    "Circuit breaker status: ${breaker?.state}", 60.0, 5))
            }
            if (metrics.failedRequests > 10 && profile.retryCount < 2) {
                suggestions.add(ApiOptimizationSuggestion(path, method, ApiSuggestionType.INCREASE_RETRY_COUNT,
                    "${metrics.failedRequests} failures detected", 30.0, 3))
            }
            if (profile.averageLatencyMs > 1000 && profile.timeoutMs > 30000) {
                suggestions.add(ApiOptimizationSuggestion(path, method, ApiSuggestionType.REDUCE_TIMEOUT,
                    "Reduce timeout from ${profile.timeoutMs}ms based on ${metrics.averageLatencyMs}ms avg latency", 10.0, 2))
            }
        }
        suggestions.sortedByDescending { it.priority }
    }

    private fun endpointKey(path: String, method: HttpMethod): String = "$path:${method.name}"

    fun updateConfig(newConfig: ApiPoolConfig): ApiPoolConfig { newConfig }

    fun resetMetrics(path: String? = null) {
        if (path != null) {
            endpointProfiles.keys.filter { it.startsWith(path) }.forEach { key ->
                requestCounts[key]?.set(0)
                successCounts[key]?.set(0)
                failureCounts[key]?.set(0)
                latencySamples[key]?.clear()
                cacheHits[key]?.set(0)
                cacheMisses[key]?.set(0)
            }
        } else {
            requestCounts.values.forEach { it.set(0) }
            successCounts.values.forEach { it.set(0) }
            failureCounts.values.forEach { it.set(0) }
            latencySamples.values.forEach { it.clear() }
            cacheHits.values.forEach { it.set(0) }
            cacheMisses.values.forEach { it.set(0) }
        }
    }

    private class TokenBucket(private var capacity: Int, private var tokens: Int = capacity) {
        private var lastRefillMs = System.currentTimeMillis()

        @Synchronized
        fun tryConsume(count: Int, limit: Int, refillRate: Int): Boolean {
            refill(limit, refillRate)
            if (tokens >= count) {
                tokens -= count
                true
            } else false
        }

        @Synchronized
        fun currentTokens(): Int {
            refill(capacity, capacity)
            tokens
        }

        private fun refill(limit: Int, refillRate: Int) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefillMs
            if (elapsed >= 1000L) {
                val toAdd = (elapsed / 1000L * refillRate).toInt()
                tokens = (tokens + toAdd).coerceAtMost(limit)
                lastRefillMs = now
            }
        }
    }

    private fun analyzeAndSuggest() {
        val suggestions = suggestOptimizations()
        if (suggestions.isNotEmpty()) {
            val top = suggestions.first()
            suggestions.take(3).forEach { s ->
                s
            }
        }
    }
}
