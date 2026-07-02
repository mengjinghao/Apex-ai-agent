package com.apex.agent.core.cache

/**
 * 缓存淘汰策略密封类，定义缓存条目被淘汰的判断规则。
 *
 * 支持以下策略的组合使用：
 * - [TtlPolicy]：基于存活时间的过期策略
 * - [LruPolicy]：基于最近最少使用的淘汰策略
 * - [LfuPolicy]：基于最低使用频率的淘汰策略
 * - [FifoPolicy]：基于先进先出的淘汰策略
 * - [HybridPolicy]：多种策略加权组合
 */
sealed class CachePolicy {

    /**
     * TTL（Time-To-Live）过期策略。
     * 缓存条目超过指定 [duration] 毫秒后自动失效。
     */
    data class TtlPolicy(val duration: Long) : CachePolicy() {
        init {
            require(duration > 0) { "TTL duration must be positive" }
        }

        /** 判断指定条目是否已过期 */
        fun isExpired(entry: CacheEntry<*>): Boolean {
            return (System.currentTimeMillis() - entry.createdAt) > duration
        }
    }

    /**
     * LRU（Least Recently Used）淘汰策略。
     * 当缓存条目数超过 [maxSize] 时，淘汰最久未被访问的条目。
     */
    data class LruPolicy(val maxSize: Int) : CachePolicy() {
        init {
            require(maxSize > 0) { "LRU maxSize must be positive" }
        }

        /** 按最后访问时间升序排序，返回需要淘汰的条目 */
        fun evictCandidates(entries: Collection<CacheEntry<*>>): List<CacheEntry<*>> {
            if (entries.size <= maxSize) return emptyList()
            return entries.sortedBy { it.lastAccessedAt }
                .take(entries.size - maxSize)
        }
    }

    /**
     * LFU（Least Frequently Used）淘汰策略。
     * 当缓存条目数超过 [minFrequency] 对应的阈值时，淘汰访问频率最低的条目。
     */
    data class LfuPolicy(val minFrequency: Long) : CachePolicy() {
        init {
            require(minFrequency >= 0) { "LFU minFrequency must be non-negative" }
        }

        /** 按命中次数升序排序，返回频率低于阈值的条目 */
        fun evictCandidates(entries: Collection<CacheEntry<*>>): List<CacheEntry<*>> {
            return entries.filter { it.hitCount <= minFrequency }
                .sortedBy { it.hitCount }
        }
    }

    /**
     * FIFO（First In First Out）先进先出淘汰策略。
     * 当缓存条目数超过 [maxSize] 时，淘汰最早创建的条目。
     */
    data class FifoPolicy(val maxSize: Int) : CachePolicy() {
        init {
            require(maxSize > 0) { "FIFO maxSize must be positive" }
        }

        /** 按创建时间升序排序，返回需要淘汰的条目 */
        fun evictCandidates(entries: Collection<CacheEntry<*>>): List<CacheEntry<*>> {
            if (entries.size <= maxSize) return emptyList()
            return entries.sortedBy { it.createdAt }
                .take(entries.size - maxSize)
        }
    }

    /**
     * HybridPolicy 混合策略，支持按权重组合多种策略。
     *
     * 淘汰评分 = Σ(策略评分 × 权重)，评分最高的条目优先淘汰。
     *
     * @param policies 策略及其权重映射
     */
    data class HybridPolicy(
        val policies: Map<CachePolicy, Double>
    ) : CachePolicy() {
        init {
            require(policies.isNotEmpty()) { "HybridPolicy requires at least one sub-policy" }
            require(policies.values.all { it > 0 }) { "All weights must be positive" }
        }

        /**
         * 计算指定条目在混合策略下的综合淘汰评分。
         * 评分越高，越应优先淘汰。
         */
        fun evictionScore(entry: CacheEntry<*>): Double {
            var score = 0.0
            for ((policy, weight) in policies) {
                score += when (policy) {
                    is TtlPolicy -> {
                        val age = System.currentTimeMillis() - entry.createdAt
                        (age.toDouble() / policy.duration) * weight
                    }
                    is LruPolicy -> {
                        val idle = System.currentTimeMillis() - entry.lastAccessedAt
                        (idle.toDouble() / 1_000_000) * weight
                    }
                    is LfuPolicy -> {
                        (1.0 / (entry.hitCount + 1)) * weight
                    }
                    is FifoPolicy -> {
                        (System.currentTimeMillis() - entry.createdAt).toDouble() * weight
                    }
                    is HybridPolicy -> {
                        (policy as CachePolicy).let { this.evictionScore(entry) } * weight
                    }
                }
            }
            return score
        }
    }
}
