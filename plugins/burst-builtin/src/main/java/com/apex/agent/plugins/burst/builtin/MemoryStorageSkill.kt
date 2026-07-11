package com.apex.agent.plugins.burst.builtin

import kotlinx.coroutines.Dispatchers

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 多级存储技能
 * 实现L1/L2/L3三级缓存管理、配额控制、健康检查
 */
class MemoryStorageSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    companion object {
        private const val DEFAULT_STORAGE_BYTES = 10L * 1024 * 1024 * 1024
        private const val L1_RATIO = 0.10f
        private const val L2_RATIO = 0.30f
        private const val L3_RATIO = 0.60f
    }
    
    private val l1Capacity = AtomicLong((DEFAULT_STORAGE_BYTES * L1_RATIO).toLong())
    private val l2Capacity = AtomicLong((DEFAULT_STORAGE_BYTES * L2_RATIO).toLong())
    private val l3Capacity = AtomicLong((DEFAULT_STORAGE_BYTES * L3_RATIO).toLong())
    
    private val usedSpace = AtomicLong(0L)
    private val allocatedSpace = AtomicLong(0L)
    
    private val l1MemoryCache = ConcurrentHashMap<String, MemoryItem>()
    private val l2FileCache = ConcurrentHashMap<String, String>()
    private val l3FileCache = ConcurrentHashMap<String, String>()
    
    private var isHealthy = true
    private var lastHealthCheck = 0L
    
    init {
        manifest = BurstSkillManifest(
            skillId = "memory_storage",
            skillName = "多级存储",
            version = "1.0.0",
            description = "三级存储管理（L1内存/L2文件/L3外部），配额控制和健康检查",
            author = "Apex Agent",
            tags = listOf("storage", "cache", "multi-level"),
            priority = 80,
            capabilities = listOf(
                "l1_memory_cache",
                "l2_file_cache",
                "l3_external_storage",
                "quota_management",
                "health_check"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "store"
            val key = task.metadata["key"] ?: task.id
            
            when (operation) {
                "store" -> {
                    val data = task.input.text ?: ""
                    val level = task.metadata["level"]?.toIntOrNull() ?: 1
                    store(key, data, level)
                }
                "retrieve" -> {
                    retrieve(key)
                }
                "delete" -> {
                    delete(key)
                }
                "health_check" -> {
                    performHealthCheck()
                }
                "stats" -> {
                    getStorageStats()
                }
                else -> {
                    // 默认操作：存储到L1
                    val data = task.input.text ?: ""
                    store(key, data, 1)
                }
            }
            
            val stats = getStorageStats()
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Memory storage operation completed:
                    |- Operation: $operation
                    |- Key: $key
                    |- L1 cache size: ${stats.l1CacheSize}
                    |- L2 cache size: ${stats.l2CacheSize}
                    |- L3 cache size: ${stats.l3CacheSize}
                    |- Used space: ${stats.usedSpaceMb}MB
                    |- Available space: ${stats.availableSpaceMb}MB
                    |- Health status: ${if (isHealthy) "healthy" else "warning"}
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = 1
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun store(key: String, data: String, level: Int): Boolean {
        val size = data.toByteArray().size.toLong()
        
        if (!allocateQuota(size)) {
            return false
        }
        
        val item = MemoryItem(
            key = key,
            data = data,
            size = size,
            timestamp = System.currentTimeMillis()
        )
        
        when (level) {
            1 -> l1MemoryCache[key] = item
            2 -> l2FileCache[key] = data
            3 -> l3FileCache[key] = data
            else -> l1MemoryCache[key] = item
        }
        
        usedSpace.addAndGet(size)
        return true
    }
    
    private fun retrieve(key: String): String? {
        // 先从L1查找
        val l1Item = l1MemoryCache[key]
        if (l1Item != null) {
            return l1Item.data
        }
        
        // 从L2查找
        val l2Data = l2FileCache[key]
        if (l2Data != null) {
            // 提升到L1
            l1MemoryCache[key] = MemoryItem(
                key = key,
                data = l2Data,
                size = l2Data.toByteArray().size.toLong(),
                timestamp = System.currentTimeMillis()
            )
            return l2Data
        }
        
        // 从L3查找
        val l3Data = l3FileCache[key]
        if (l3Data != null) {
            // 提升到L2
            l2FileCache[key] = l3Data
            return l3Data
        }
        
        return null
    }
    
    private fun delete(key: String): Boolean {
        val l1Item = l1MemoryCache.remove(key)
        if (l1Item != null) {
            usedSpace.addAndGet(-l1Item.size)
            return true
        }
        
        l2FileCache.remove(key)
        l3FileCache.remove(key)
        return true
    }
    
    fun checkQuota(): Boolean {
        return allocatedSpace.get() < DEFAULT_STORAGE_BYTES
    }
    
    fun allocateQuota(size: Long): Boolean {
        synchronized(this) {
            if (allocatedSpace.get() + size <= DEFAULT_STORAGE_BYTES) {
                allocatedSpace.addAndGet(size)
                return true
            }
            return false
        }
    }
    
    fun releaseQuota(size: Long) {
        synchronized(this) {
            allocatedSpace.addAndGet(-size.coerceAtLeast(0))
        }
    }
    
    fun performHealthCheck(): Boolean {
        lastHealthCheck = System.currentTimeMillis()
        
        val usedRatio = usedSpace.get().toFloat() / DEFAULT_STORAGE_BYTES
        isHealthy = usedRatio < 0.9f
        
        if (l1MemoryCache.size > 1000) {
            val oldestKeys = l1MemoryCache.entries
                .sortedBy { it.value.timestamp }
                .take(l1MemoryCache.size / 4)
                .map { it.key }
            
            oldestKeys.forEach { key ->
                val item = l1MemoryCache.remove(key)
                if (item != null) {
                    usedSpace.addAndGet(-item.size)
                }
            }
        }
        
        if (usedRatio > 0.8f) {
            val oldestKeys = l1MemoryCache.entries
                .sortedBy { it.value.timestamp }
                .take(l1MemoryCache.size / 4)
                .map { it.key }
            
            oldestKeys.forEach { key ->
                val item = l1MemoryCache.remove(key)
                if (item != null) {
                    usedSpace.addAndGet(-item.size)
                }
            }
        }
        
        if (l2FileCache.size > 5000) {
            val excess = l2FileCache.size - 5000
            l2FileCache.keys.take(excess).forEach { l2FileCache.remove(it) }
        }
        
        if (l3FileCache.size > 10000) {
            val excess = l3FileCache.size - 10000
            l3FileCache.keys.take(excess).forEach { l3FileCache.remove(it) }
        }
        
        return isHealthy
    }
    
    fun getStorageStats(): StorageStats {
        return StorageStats(
            l1CacheSize = l1MemoryCache.size,
            l2CacheSize = l2FileCache.size,
            l3CacheSize = l3FileCache.size,
            usedSpaceMb = usedSpace.get() / (1024 * 1024),
            availableSpaceMb = (DEFAULT_STORAGE_BYTES - usedSpace.get()) / (1024 * 1024),
            isHealthy = isHealthy
        )
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        l1MemoryCache.clear()
        l2FileCache.clear()
        l3FileCache.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.82f
    
    data class MemoryItem(
        val key: String,
        val data: String,
        val size: Long,
        val timestamp: Long
    )
    
    data class StorageStats(
        val l1CacheSize: Int,
        val l2CacheSize: Int,
        val l3CacheSize: Int,
        val usedSpaceMb: Long,
        val availableSpaceMb: Long,
        val isHealthy: Boolean
    )
}