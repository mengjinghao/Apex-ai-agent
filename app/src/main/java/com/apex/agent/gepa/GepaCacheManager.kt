package com.apex.gepa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

class GepaCacheManager(
    private val maxCacheSize: Int = 1000,
    private val defaultTtlMs: Long = 3600000,
    private val cleanupIntervalMs: Long = 300000
) {

    private val cache = ConcurrentHashMap<String, CacheEntry<*>>()
        private val accessOrder = ConcurrentHashMap<String, Long>()
        private val stats = CacheStats()
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        scheduler.scheduleAtFixedRate(
            { cleanup() },
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }
        fun <T> put(key: String, value: T, ttlMs: Long = defaultTtlMs): T {
        val entry = CacheEntry(value, System.currentTimeMillis() + ttlMs)
        cache[key] = entry as CacheEntry<*>
        accessOrder[key] = System.currentTimeMillis()
        updateStats { it.recordPut() }
        enforceMaxSize()
        return value
    }

    @Suppress("UNCHECKED_CAST")
        fun <T> get(key: String, ttlMs: Long = defaultTtlMs): T? {
        val entry = cache[key] as? CacheEntry<T>
        if (entry == null) {
            updateStats { it.recordMiss() }
        return null
        }
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            accessOrder.remove(key)
            updateStats { it.recordExpired() }
        return null
        }

        accessOrder[key] = System.currentTimeMillis()
        updateStats { it.recordHit() }
        return entry.value
    }
        fun <T> getOrPut(key: String, factory: () -> T, ttlMs: Long = defaultTtlMs): T {
        return get(key, ttlMs) ?: put(key, factory(), ttlMs)
    }
        fun remove(key: String): Boolean {
        val removed = cache.remove(key) != null
        accessOrder.remove(key)
        if (removed) {
            updateStats { it.recordEviction() }
        }
        return removed
    }
        fun clear() {
        cache.clear()
        accessOrder.clear()
        stats.reset()
    }
        fun size(): Int = cache.size

    fun contains(key: String): Boolean {
        return cache.containsKey(key) && (cache[key] as? CacheEntry<*>)?.expiresAt ?: 0 > System.currentTimeMillis()
    }
        fun getStats(): CacheStats = stats.copy()
        private fun enforceMaxSize() {
        while (cache.size > maxCacheSize) {
            val lruKey = accessOrder.minByOrNull { accessOrder[it.key] ?: 0 }?.key
            if (lruKey != null) {
                cache.remove(lruKey)
                accessOrder.remove(lruKey)
                updateStats { it.recordEviction() }
            } else {
                break
            }
        }
    }
        private fun cleanup() {
        val now = System.currentTimeMillis()
        val iterator = cache.iterator()
        var removed = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
        if (entry.value.expiresAt < now) {
                iterator.remove()
                accessOrder.remove(entry.key)
                removed++
            }
        }
        if (removed > 0) {
            updateStats { it.recordExpired(removed) }
        }
    }
        private inline fun updateStats(update: (CacheStats) -> Unit) {
        synchronized(stats) {
            update(stats)
        }
    }
        fun shutdown() {
        scheduler.shutdown()
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }

    data class CacheEntry<T>(
        val value: T,
        val expiresAt: Long
    )

    data class CacheStats(
        var hits: Long = 0,
        var misses: Long = 0,
        var puts: Long = 0,
        var evictions: Long = 0,
        var expirations: Long = 0
    ) {
        val hitRate: Float
            get() = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f

        fun recordHit() { hits++ }
        fun recordMiss() { misses++ }
        fun recordPut() { puts++ }
        fun recordEviction() { evictions++ }
        fun recordExpired(count: Int = 1) { expirations += count }
        fun reset() {
            hits = 0
            misses = 0
            puts = 0
            evictions = 0
            expirations = 0
        }
        fun copy() = CacheStats(hits, misses, puts, evictions, expirations)
    }
}

class SkillTemplateCacheManager(
    private val delegate: GepaCacheManager = GepaCacheManager()
) {

    private val _cacheStats = MutableStateFlow(delegate.getStats())
        val cacheStats: StateFlow<GepaCacheManager.CacheStats> = _cacheStats.asStateFlow()

    companion object {
        private const val SKILL_KEY_PREFIX = "skill_"
        private const val MATCH_KEY_PREFIX = "match_"
        private const val TASK_TYPE_KEY_PREFIX = "tasktype_"
    }
        fun cacheSkill(skillId: Int, skill: com.apex.data.gepa.SkillTemplate, ttlMs: Long = 1800000) {
        delegate.put("${SKILL_KEY_PREFIX}${skillId}", skill, ttlMs)
        updateStats()
    }
        fun getCachedSkill(skillId: Int): com.apex.data.gepa.SkillTemplate? {
        return delegate.get("${SKILL_KEY_PREFIX}${skillId}")
    }
        fun cacheMatchResult(taskType: String, taskDescription: String, match: MatchedSkill, ttlMs: Long = 600000) {
        val key = "${MATCH_KEY_PREFIX}${taskType}_${taskDescription.hashCode()}"
        delegate.put(key, match, ttlMs)
        updateStats()
    }
        fun getCachedMatch(taskType: String, taskDescription: String): MatchedSkill? {
        val key = "${MATCH_KEY_PREFIX}${taskType}_${taskDescription.hashCode()}"
        return delegate.get(key)
    }
        fun cacheTaskTypeSkills(taskType: String, skills: List<com.apex.data.gepa.SkillTemplate>, ttlMs: Long = 900000) {
        delegate.put("${TASK_TYPE_KEY_PREFIX}${taskType}", skills, ttlMs)
        updateStats()
    }
        fun getCachedTaskTypeSkills(taskType: String): List<com.apex.data.gepa.SkillTemplate>? {
        return delegate.get("${TASK_TYPE_KEY_PREFIX}${taskType}")
    }
        fun invalidateSkill(skillId: Int) {
        delegate.remove("${SKILL_KEY_PREFIX}${skillId}")
        updateStats()
    }
        fun invalidateMatchesForType(taskType: String) {
        delegate.clear()
        updateStats()
    }
        fun clearAll() {
        delegate.clear()
        updateStats()
    }
        fun getStats(): GepaCacheManager.CacheStats = delegate.getStats()
        private fun updateStats() {
        _cacheStats.value = delegate.getStats()
    }
        fun shutdown() {
        delegate.shutdown()
    }
}
