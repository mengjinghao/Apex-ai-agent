package com.apex.agent.core.memory.unified

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class SharedMemoryEntry(
    val entryId: String,
    val taskId: String,
    val content: String,
    val agentRole: String,
    val priority: Int = 50,
    val timestamp: Long = System.currentTimeMillis(),
    val isFinal: Boolean = false,
    val isRead: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val tags: MutableSet<String> = ConcurrentHashMap.newKeySet()
)

class SharedMemoryPool private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: SharedMemoryPool? = null

        fun getInstance(): SharedMemoryPool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SharedMemoryPool().also { INSTANCE = it }
            }
        }
        fun resetInstance() {
            INSTANCE?.clear()
            INSTANCE = null
        }
    }
        private val taskMemoryPool: ConcurrentHashMap<String, MutableList<SharedMemoryEntry>> = ConcurrentHashMap()
        private val memorySubscribers: ConcurrentHashMap<String, MutableList<(SharedMemoryEntry) -> Unit>> = ConcurrentHashMap()
        private val modeSubscribers: ConcurrentHashMap<AgentMode, MutableList<(SharedMemoryEntry) -> Unit>> = ConcurrentHashMap()
        fun writeSharedMemory(entry: SharedMemoryEntry) {
        val taskMemories = taskMemoryPool.getOrPut(entry.taskId) { CopyOnWriteArrayList() }
        if (entry.isFinal && taskMemories.any { it.entryId == entry.entryId }) {
            throw IllegalStateException("Memory already marked as final: ${entry.entryId}")
        }

        synchronized(taskMemories) {
            taskMemories.add(entry)
        }

        notifySubscribers(entry)
        notifyModeSubscribers(entry)
    }
        fun getTaskSharedMemories(taskId: String): List<SharedMemoryEntry> {
        return taskMemoryPool[taskId]?.toList() ?: emptyList()
    }
        fun getUnreadMemoriesForAgent(taskId: String, agentRole: String): List<SharedMemoryEntry> {
        val memories = taskMemoryPool[taskId] ?: return emptyList()
        val unreadMemories = memories.filter { !it.isRead.contains(agentRole) }
        unreadMemories.forEach { it.isRead.add(agentRole) }
        return unreadMemories
    }
        fun getAllMemories(): List<SharedMemoryEntry> {
        return taskMemoryPool.values.flatMap { it.toList() }
    }
        fun getMemoriesByPriority(minPriority: Int): List<SharedMemoryEntry> {
        return taskMemoryPool.values.flatMap { it.toList() }
            .filter { it.priority >= minPriority }
            .sortedByDescending { it.priority }
    }
        fun getMemoriesByRole(agentRole: String): List<SharedMemoryEntry> {
        return taskMemoryPool.values.flatMap { it.toList() }
            .filter { it.agentRole == agentRole }
            .sortedByDescending { it.timestamp }
    }
        fun subscribeTaskMemory(taskId: String, onMemoryUpdate: (SharedMemoryEntry) -> Unit) {
        val subscribers = memorySubscribers.getOrPut(taskId) { CopyOnWriteArrayList() }
        synchronized(subscribers) {
            subscribers.add(onMemoryUpdate)
        }
    }
        fun unsubscribeTaskMemory(taskId: String, onMemoryUpdate: (SharedMemoryEntry) -> Unit) {
        memorySubscribers[taskId]?.remove(onMemoryUpdate)
    }
        fun subscribeByMode(mode: AgentMode, callback: (SharedMemoryEntry) -> Unit) {
        val subscribers = modeSubscribers.getOrPut(mode) { CopyOnWriteArrayList() }
        synchronized(subscribers) {
            subscribers.add(callback)
        }
    }
        fun unsubscribeByMode(mode: AgentMode, callback: (SharedMemoryEntry) -> Unit) {
        modeSubscribers[mode]?.remove(callback)
    }
        fun getTasksWithMemory(): Set<String> {
        return taskMemoryPool.keys.toSet()
    }
        fun countByTask(taskId: String): Int {
        return taskMemoryPool[taskId]?.size ?: 0
    }
        fun totalSize(): Int {
        return taskMemoryPool.values.sumOf { it.size }
    }
        fun clearTaskMemory(taskId: String) {
        taskMemoryPool.remove(taskId)
        memorySubscribers.remove(taskId)
    }
        fun clear() {
        taskMemoryPool.clear()
        memorySubscribers.clear()
        modeSubscribers.clear()
    }
        private fun notifySubscribers(entry: SharedMemoryEntry) {
        memorySubscribers[entry.taskId]?.forEach { subscriber ->
            try {
                subscriber(entry)
            } catch (e: Exception) {
                AppLogger.e(TAG, "notifySubscribers error", e)
            }
        }
    }
        private fun notifyModeSubscribers(entry: SharedMemoryEntry) {
        modeSubscribers.forEach { (mode, callbacks) ->
            callbacks.forEach { cb ->
                try {
                    cb(entry)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "notifyModeSubscribers error", e)
                }
            }
        }
    }
}
