package com.apex.agent.core.cache

/**
 * 缓存存储接口，定义三级缓存统一的操作契约。
 *
 * 各级缓存（内存 / 磁盘 / 分布式）均需实现此接口，
 * 以便 [CacheManager] 以统一方式进行读写、驱逐和统计。
 *
 * @param K 缓存键类型
 * @param V 缓存值类型
 */
interface ICacheStore<K, V> {

    /** 根据键获取缓存值，若不存在或已过期返回 null */
    fun get(key: K): CacheEntry<V>?

    /** 存入缓存条目 */
    fun put(entry: CacheEntry<V>)

    /** 根据键移除缓存条目 */
    fun remove(key: K): Boolean

    /** 清空所有缓存条目 */
    fun clear()

    /** 判断指定键是否存在且未过期 */
    fun contains(key: K): Boolean

    /** 返回当前缓存条目总数 */
    fun size(): Int

    /** 返回当前缓存统计信息 */
    fun stats(): CacheStats

    /** 执行缓存驱逐，根据策略移除符合条件的条目 */
    fun evict(policy: CachePolicy): List<String>

    /** 预热缓存，从外部来源预加载指定键的数据 */
    fun warmUp(keys: Collection<K>): Int
}
