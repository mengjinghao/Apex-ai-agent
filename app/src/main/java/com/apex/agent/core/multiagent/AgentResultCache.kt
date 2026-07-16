package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap

class AgentResultCache {

    private data class CacheKey(
        val producer: String,
        val operation: String,
        val inputHash: Int
    )


    private val cache = ConcurrentHashMap<CacheKey, CachedResult>()
    private val accessCount = ConcurrentHashMap<CacheKey, Int>()
    private val maxEntries = 500

    fun getOrCompute(
        agentId: String,
        operation: String,
        input: String,
        producer: String = agentId,
        computer: () -> String
    ): String {
        val key = CacheKey(producer, operation, input.hashCode())
        accessCount.compute(key) { _, v -> (v ?: 0) + 1 }

        val existing = cache[key]
        if (existing != null) {
            val updated = existing.copy(accessCount = existing.accessCount + 1)
            cache[key] = updated
            return existing.result
        }

        val result = computer()
        cache[key] = CachedResult(
            result = result,
            producer = producer,
            accessCount = 1
        )
        evictIfNeeded()
        return result
    }

    fun get(agentId: String, operation: String, input: String): String? {
        val key = CacheKey(agentId, operation, input.hashCode())
        return cache[key]?.also {
            val updated = it.copy(accessCount = it.accessCount + 1)
            cache[key] = updated
            accessCount.compute(key) { _, v -> (v ?: 0) + 1 }
        }?.result
    }

    fun invalidate(agentId: String, operation: String? = null) {
        val keysToRemove = cache.keys.filter {
            it.producer == agentId && (operation == null || it.operation == operation)
        }
        keysToRemove.forEach { cache.remove(it); accessCount.remove(it) }
    }

    fun invalidateAll() {
        cache.clear()
        accessCount.clear()
    }

    fun getStats(): CacheStats {
        val total = cache.size
        val totalAccesses = accessCount.values.sum()
        val topKeys = accessCount.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (key, count) ->
                "${key.producer}:${key.operation}" to count
            }
        return CacheStats(total, totalAccesses, topKeys)
    }

    private fun evictIfNeeded() {
        if (cache.size > maxEntries) {
            val toRemove = cache.entries
                .sortedBy { it.value.accessCount }
                .take(cache.size - maxEntries)
            toRemove.forEach { (key, _) ->
                cache.remove(key); accessCount.remove(key)
            }
        }
    }

