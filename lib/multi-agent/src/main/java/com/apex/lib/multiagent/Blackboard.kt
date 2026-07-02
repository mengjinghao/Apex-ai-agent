package com.apex.lib.multiagent

import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 间共享黑板。
 *
 * 任意 Agent 可写入任意 key，其他 Agent 通过订阅 [flow] 获取变更通知。
 * 这是多 Agent 协作的“共享内存”。
 */
class Blackboard {

    private val store = ConcurrentHashMap<String, Any>()
    private val _flow = MutableSharedFlow<BlackboardEntry>(extraBufferCapacity = 128)
    val flow = _flow

    fun put(key: String, value: Any) {
        store[key] = value
        _flow.tryEmit(BlackboardEntry(key, value, System.currentTimeMillis()))
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = store[key] as? T

    fun remove(key: String) {
        store.remove(key)
    }

    fun keys(): Set<String> = store.keys.toSet()

    fun snapshot(): Map<String, Any> = store.toMap()
}

data class BlackboardEntry(
    val key: String,
    val value: Any,
    val timestampMs: Long
)
