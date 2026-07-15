package com.apex.agent.core.patterns

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 策略注册表模式 - 通用的线程安全策略管理器
 * 支持按优先级排序、自动发现和回退策略
 */

/** 策略接口 */
interface Strategy {
    val name: String
    val priority: Int get() = 0
}

/** 通用策略注册表 */
class StrategyRegistry<K, V> {

    private val strategies = ConcurrentHashMap<K, V>()
        private val lock = ReentrantReadWriteLock()
        fun register(key: K, strategy: V) {
        lock.write { strategies[key] = strategy }
    }
        fun unregister(key: K): V? {
        return lock.write { strategies.remove(key) }
    }
        fun get(key: K): V? {
        return lock.read { strategies[key] }
    }
        fun getAll(): Map<K, V> {
        return lock.read { strategies.toMap() }
    }
        fun find(predicate: (Map.Entry<K, V>) -> Boolean): List<Map.Entry<K, V>> {
        return lock.read { strategies.filter(predicate) }
    }
        val keys: Set<K> get() = lock.read { strategies.keys.toSet() }
        fun clear() {
        lock.write { strategies.clear() }
    }
        val size: Int get() = lock.read { strategies.size }
}

/** 推理策略 */
interface ReasoningStrategy : Strategy {
    suspend fun reason(input: String): String
}

class ChainOfThoughtStrategy : ReasoningStrategy {
    override val name = "chain_of_thought"
    override val priority = 10

    override suspend fun reason(input: String): String = "Chain of thought: $input"
}

class TreeOfThoughtStrategy : ReasoningStrategy {
    override val name = "tree_of_thought"
    override val priority = 20

    override suspend fun reason(input: String): String = "Tree of thought: $input"
}

class FallbackReasoningStrategy : ReasoningStrategy {
    override val name = "fallback"
    override val priority = Int.MIN_VALUE

    override suspend fun reason(input: String): String = "Fallback: $input"
}

/** 推理策略注册表 */
class ReasoningStrategyRegistry {
    private val registry = StrategyRegistry<String, ReasoningStrategy>()
        private val fallback = FallbackReasoningStrategy()

    init {
        register(ChainOfThoughtStrategy())
        register(TreeOfThoughtStrategy())
    }
        fun register(strategy: ReasoningStrategy) {
        if (strategy.priority > Int.MIN_VALUE) {
            registry.register(strategy.name, strategy)
        }
    }
        fun getStrategy(name: String): ReasoningStrategy {
        return registry.get(name) ?: fallback
    }
        fun getStrategiesSorted(): List<ReasoningStrategy> {
        return registry.getAll().values.sortedByDescending { it.priority }
    }
        fun discoverStrategies(): List<ReasoningStrategy> {
        return registry.getAll().values.toList()
    }
}
