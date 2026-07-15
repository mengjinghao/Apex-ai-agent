package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SkillCache private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillCache"
        private const val DEFAULT_MAX_SIZE = 100
        private const val DEFAULT_EXPIRY_MS = 30 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L

        @Volatile private var INSTANCE: SkillCache? = null

        fun getInstance(context: Context): SkillCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillCache(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class CacheEntry(
        val skillName: String,
        val toolName: String,
        val parametersHash: String,
        val result: Any?,
        val cachedAt: Long,
        val expiresAt: Long,
        val hitCount: AtomicLong = AtomicLong(0)
    )

    data class CacheConfig(
        val maxSize: Int = DEFAULT_MAX_SIZE,
        val defaultExpiryMs: Long = DEFAULT_EXPIRY_MS,
        val enableExpiration: Boolean = true
    )
        private var config: CacheConfig = CacheConfig()
        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val accessOrder = ConcurrentHashMap<String, Long>()
        private val cleanupExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SkillCache-Cleanup").also { it.isDaemon = true }
    }
        private var cleanupTask: ScheduledFuture<*>? = null

    private val statsHits = AtomicLong(0)
        private val statsMisses = AtomicLong(0)
        private val statsEvictions = AtomicLong(0)
        private val statsExpired = AtomicLong(0)

    init {
        startCleanupTask()
    }
        fun setConfig(newConfig: CacheConfig) {
        config = newConfig
        AppLogger.d(TAG, "Cache config updated: maxSize=${newConfig.maxSize}, expiryMs=${newConfig.defaultExpiryMs}")
    }
        fun getConfig(): CacheConfig = config

    private fun startCleanupTask() {
        cleanupTask?.cancel(false)
        cleanupTask = cleanupExecutor.scheduleAtFixedRate(
            { runCleanup() },
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }
        private fun runCleanup() {
        if (!config.enableExpiration) return

        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()

        cache.forEach { (key, entry) ->
            if (entry.expiresAt <= now) {
                keysToRemove.add(key)
            }
        }
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { key ->
                cache.remove(key)
                accessOrder.remove(key)
            }
            statsExpired.addAndGet(keysToRemove.size.toLong())
            AppLogger.d(TAG, "Cache cleanup removed ${keysToRemove.size} expired entries")
        }
    }
        fun generateCacheKey(skillName: String, toolName: String, parameters: Map<String, Any?>): String {
        val keyBase = "${skillName}|${toolName}|${serializeParameters(parameters)}"
        return hashKey(keyBase)
    }
        private fun serializeParameters(parameters: Map<String, Any?>): String {
        return parameters.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "${key}=${value?.toString() ?: "null"}"
            }
    }
        private fun hashKey(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
        fun get(skillName: String, toolName: String, parameters: Map<String, Any?>): Any? {
        val key = generateCacheKey(skillName, toolName, parameters)
        return getByKey(key)
    }
        fun getByKey(key: String): Any? {
        val entry = cache[key] ?: run {
            statsMisses.incrementAndGet()
        return null
        }
        if (config.enableExpiration && entry.expiresAt <= System.currentTimeMillis()) {
            invalidate(key)
            statsMisses.incrementAndGet()
        return null
        }

        accessOrder[key] = System.currentTimeMillis()
        entry.hitCount.incrementAndGet()
        statsHits.incrementAndGet()
        return entry.result
    }
        fun put(skillName: String, toolName: String, parameters: Map<String, Any?>, result: Any?, expiryMs: Long? = null) {
        val key = generateCacheKey(skillName, toolName, parameters)
        putByKey(key, skillName, toolName, parameters, result, expiryMs)
    }
        fun putByKey(
        key: String,
        skillName: String,
        toolName: String,
        parameters: Map<String, Any?>,
        result: Any?,
        expiryMs: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val effectiveExpiry = expiryMs ?: config.defaultExpiryMs
        val expiresAt = if (config.enableExpiration) now + effectiveExpiry else Long.MAX_VALUE

        if (cache.size >= config.maxSize && !cache.containsKey(key)) {
            evictLeastRecentlyUsed()
        }
        val entry = CacheEntry(
            skillName = skillName,
            toolName = toolName,
            parametersHash = hashKey(serializeParameters(parameters)),
            result = result,
            cachedAt = now,
            expiresAt = expiresAt
        )

        cache[key] = entry
        accessOrder[key] = now
    }
        private fun evictLeastRecentlyUsed() {
        if (accessOrder.isEmpty()) return

        val lruKey = accessOrder.minByOrNull { it.value }?.key ?: return
        invalidate(lruKey)
        statsEvictions.incrementAndGet()
    }
        fun invalidate(skillName: String, toolName: String, parameters: Map<String, Any?>) {
        val key = generateCacheKey(skillName, toolName, parameters)
        invalidate(key)
    }
        fun invalidate(key: String) {
        cache.remove(key)
        accessOrder.remove(key)
    }
        fun invalidateSkill(skillName: String) {
        val keysToRemove = cache.entries
            .filter { it.value.skillName == skillName }
            .map { it.key }

        keysToRemove.forEach { key ->
            cache.remove(key)
            accessOrder.remove(key)
        }
        if (keysToRemove.isNotEmpty()) {
            AppLogger.d(TAG, "Invalidated ${keysToRemove} entries for skill: ${skillName}")
        }
    }
        fun invalidateAll() {
        val size = cache.size
        cache.clear()
        accessOrder.clear()
        AppLogger.d(TAG, "Cache cleared: ${size} entries removed")
    }
        fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            maxSize = config.maxSize,
            hits = statsHits.get(),
            misses = statsMisses.get(),
            evictions = statsEvictions.get(),
            expired = statsExpired.get(),
            hitRate = calculateHitRate()
        )
    }
        private fun calculateHitRate(): Double {
        val total = statsHits.get() + statsMisses.get()
        return if (total > 0) statsHits.get().toDouble() / total else 0.0
    }
        fun getEntryCount(): Int = cache.size

    fun isEmpty(): Boolean = cache.isEmpty()
        fun contains(skillName: String, toolName: String, parameters: Map<String, Any?>): Boolean {
        val key = generateCacheKey(skillName, toolName, parameters)
        return cache.containsKey(key)
    }
        fun shutdown() {
        cleanupTask?.cancel(false)
        cleanupExecutor.shutdown()
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            cleanupExecutor.shutdownNow()
        }
        invalidateAll()
        AppLogger.d(TAG, "SkillCache shutdown complete")
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hits: Long,
        val misses: Long,
        val evictions: Long,
        val expired: Long,
        val hitRate: Double
    )
}