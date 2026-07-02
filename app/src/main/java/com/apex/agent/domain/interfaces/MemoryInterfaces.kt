package com.apex.agent.domain.interfaces

/**
 * 键值记忆存储接口
 *
 * 提供基本的键值对存储能力，支持 TTL 过期和容量限制。
 * 实现类应保证线程安全。
 */
interface IMemoryStore {
    /**
     * 存储键值对
     * @param key 键
     * @param value 值
     * @param ttl 过期时间（毫秒），null 表示永不过期
     */
    fun put(key: String, value: String, ttl: Long? = null)

    /**
     * 获取值
     * @param key 键
     * @return 值，若不存在或已过期返回 null
     */
    fun get(key: String): String?

    /**
     * 删除键值对
     * @param key 键
     * @return 是否成功删除
     */
    fun remove(key: String): Boolean

    /** 清空所有存储 */
    fun clear()

    /** 获取所有键 */
    fun keys(): Set<String>

    /** 获取存储条目数 */
    fun size(): Int

    /** 检查键是否存在 */
    fun contains(key: String): Boolean
}

/**
 * 向量搜索结果
 */
data class VectorResult(
    val id: String,
    val score: Float,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 向量存储接口
 *
 * 提供浮点向量的存储和余弦相似度搜索能力。
 * 实现类应保证线程安全。
 */
interface IVectorStore {
    /**
     * 添加向量
     * @param id 向量 ID
     * @param vector 浮点向量数据
     * @param metadata 附加元数据
     */
    fun add(id: String, vector: FloatArray, metadata: Map<String, String> = emptyMap())

    /**
     * 删除向量
     * @param id 向量 ID
     * @return 是否成功删除
     */
    fun remove(id: String): Boolean

    /**
     * 搜索最相似向量
     * @param query 查询向量
     * @param topK 返回结果数
     * @return 按相似度降序排列的结果列表
     */
    fun search(query: FloatArray, topK: Int = 10): List<VectorResult>

    /** 清空所有向量 */
    fun clear()

    /** 获取向量条数 */
    fun size(): Int
}
