package com.apex.agent.core.cache

import java.io.File

/**
 * 缓存构建器，使用流式（Fluent）API 便捷构建 [CacheManager] 实例。
 *
 * 用法示例：
 * ```
 * val cache = CacheBuilder<String>()
 *     .memoryCache(maxSize = 1000, ttl = 60_000L)
 *     .diskCache(cacheDir = File("/tmp/cache"), maxSize = 10_000)
 *     .distributedCache(remoteUrl = "redis://localhost:6379")
 *     .policy(CachePolicy.LruPolicy(500))
 *     .build()
 * ```
 *
 * @param V 缓存值类型
 */
class CacheBuilder<V> {

    private var memoryMaxSize: Int = 500
    private var memoryMaxMemory: Long = -1L
    private var memoryDefaultTtl: Long = -1L

    private var diskCacheDir: File? = null
    private var diskMaxSize: Int = -1
    private var diskMaxBytes: Long = -1L
    private var diskDefaultTtl: Long = -1L

    private var distributedUrl: String = ""
    private var distributedMaxRetries: Int = 3
    private var distributedRetryDelayMs: Long = 100L
    private var distributedLocalBackup: Boolean = true

    private var policy: CachePolicy = CachePolicy.LruPolicy(500)
    private var promotionThreshold: Long = 10L
    private var demotionThreshold: Long = 3L
    private var writeBackIntervalMs: Long = 5000L

    /**
     * 配置 L1 内存缓存。
     * @param maxSize  最大条目数
     * @param maxMemory 最大内存字节数
     * @param ttl      默认过期时间毫秒
     */
    fun memoryCache(
        maxSize: Int = 500,
        maxMemory: Long = -1L,
        ttl: Long = -1L
    ): CacheBuilder<V> {
        this.memoryMaxSize = maxSize
        this.memoryMaxMemory = maxMemory
        this.memoryDefaultTtl = ttl
        return this
    }

    /**
     * 配置 L2 磁盘缓存。
     * @param dir      缓存根目录
     * @param maxSize  最大条目数
     * @param maxBytes 最大磁盘使用字节数
     * @param ttl      默认过期时间毫秒
     */
    fun diskCache(
        dir: File,
        maxSize: Int = -1,
        maxBytes: Long = -1L,
        ttl: Long = -1L
    ): CacheBuilder<V> {
        this.diskCacheDir = dir
        this.diskMaxSize = maxSize
        this.diskMaxBytes = maxBytes
        this.diskDefaultTtl = ttl
        return this
    }

    /**
     * 配置 L3 分布式缓存。
     * @param url          远端服务 URL
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试间隔基数毫秒
     * @param localBackup  是否启用本地备份降级
     */
    fun distributedCache(
        url: String = "",
        maxRetries: Int = 3,
        retryDelayMs: Long = 100L,
        localBackup: Boolean = true
    ): CacheBuilder<V> {
        this.distributedUrl = url
        this.distributedMaxRetries = maxRetries
        this.distributedRetryDelayMs = retryDelayMs
        this.distributedLocalBackup = localBackup
        return this
    }

    /** 配置默认缓存淘汰策略 */
    fun policy(policy: CachePolicy): CacheBuilder<V> {
        this.policy = policy
        return this
    }

    /**
     * 配置条目晋升/降级阈值。
     * @param promote 晋升阈值（访问次数超过此值时晋升到 L1）
     * @param demote  降级阈值（访问次数低于此值时降级到 L2）
     */
    fun promotion(promote: Long = 10L, demote: Long = 3L): CacheBuilder<V> {
        this.promotionThreshold = promote
        this.demotionThreshold = demote
        return this
    }

    /**
     * 配置 Write-Back 回写间隔。
     * @param intervalMs 回写间隔毫秒，设为 0 或负值禁用回写
     */
    fun writeBack(intervalMs: Long = 5000L): CacheBuilder<V> {
        this.writeBackIntervalMs = intervalMs
        return this
    }

    /**
     * 构建 [CacheManager] 实例。
     * 所有配置项均为可选，未配置的级别将在运行时被跳过。
     */
    fun build(): CacheManager<V> {
        val memoryStore = MemoryCacheStore<V>(
            maxSize = memoryMaxSize,
            maxMemory = memoryMaxMemory,
            defaultTtl = memoryDefaultTtl
        )
        val diskStore = diskCacheDir?.let { dir ->
            DiskCacheStore(
                cacheDir = dir,
                maxSize = diskMaxSize,
                maxDiskBytes = diskMaxBytes,
                defaultTtl = diskDefaultTtl
            )
        }
        val distributedStore = DistributedCacheStore<V>(
            remoteUrl = distributedUrl,
            maxRetries = distributedMaxRetries,
            retryDelayMs = distributedRetryDelayMs,
            localBackup = distributedLocalBackup
        )
        return CacheManager(
            memoryStore = memoryStore,
            diskStore = diskStore,
            distributedStore = distributedStore,
            defaultPolicy = policy,
            promotionThreshold = promotionThreshold,
            demotionThreshold = demotionThreshold,
            writeBackIntervalMs = writeBackIntervalMs
        )
    }
}
