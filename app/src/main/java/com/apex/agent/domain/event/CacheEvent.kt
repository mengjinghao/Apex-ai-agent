package com.apex.agent.domain.event

/**
 * 缓存命中事件
 */
data class CacheHitEvent(
    val cacheName: String,
    val key: String,
    val level: Int,
    val accessTime: Long
)

/**
 * 缓存未命中事件
 */
data class CacheMissEvent(
    val cacheName: String,
    val key: String,
    val level: Int,
    val accessTime: Long
)

/**
 * 缓存驱逐事件
 */
data class CacheEvictionEvent(
    val cacheName: String,
    val key: String,
    val reason: EvictionReason,
    val entriesCount: Int,
    val sizeFreed: Long
)

enum class EvictionReason {
    SIZE, TTL, LRU, MANUAL
}

/**
 * 缓存提升事件
 */
data class CachePromotionEvent(
    val cacheName: String,
    val key: String,
    val fromLevel: Int,
    val toLevel: Int
)

/**
 * 缓存满事件
 */
data class CacheFullEvent(
    val cacheName: String,
    val level: Int,
    val usagePercent: Double,
    val threshold: Double
)
