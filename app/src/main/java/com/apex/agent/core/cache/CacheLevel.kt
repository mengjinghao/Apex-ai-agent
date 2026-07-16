package com.apex.agent.core.cache

/**
 * 缓存层级枚举，定义三级缓存架构中的优先级与性能特性。
 *
 * L1_MEMORY  —— 内存级缓存，访问速度最快，容量最小，优先级最高。
 * L2_DISK    —— 磁盘级缓存，访问速度中等，容量较大，优先级中等。
 * L3_DISTRIBUTED —— 分布式缓存（远端），访问速度最慢，容量最大，优先级最低。
 *
 * @property priority 缓存优先级数值（数值越小优先级越高）
 * @property speed    访问速度描述
 * @property capacity 容量层级描述
 */
enum class CacheLevel(
    val priority: Int,
    val speed: String,
    val capacity: String
) {
    /** 内存级缓存：纳秒级访问，受 JVM 堆大小限制 */
    L1_MEMORY(
        priority = 1,
        speed = "纳秒级",
        capacity = "小（受堆内存限制）"
    ),

    /** 磁盘级缓存：毫秒级访问，受存储空间限制 */
    L2_DISK(
        priority = 2,
        speed = "毫秒级",
        capacity = "中（受磁盘空间限制）"
    ),

    /** 分布式缓存：网络级访问，理论上可无限扩展 */
    L3_DISTRIBUTED(
        priority = 3,
        speed = "网络级",
        capacity = "大（可水平扩展）"
    );

    companion object {
        /** 根据优先级数值查找对应的缓存层级 */
        fun fromPriority(priority: Int): CacheLevel? =
            entries.find { it.priority == priority }

        /** 返回从高到低排序的层级列表 */
        fun hierarchy(): List<CacheLevel> =
            entries.sortedBy { it.priority }
    }
}
