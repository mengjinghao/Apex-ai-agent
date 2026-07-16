package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StateManager {

    private val taskStates = ConcurrentHashMap<String, CollaborationTask.Status>()
    private val agentStates = ConcurrentHashMap<String, AgentInstance.Status>()
    private val listeners = ConcurrentHashMap<String, StateChangeListener>()
    private val listenerIdCounter = AtomicInteger(0)

    fun updateTaskStatus(taskId: String, status: CollaborationTask.Status) {
        val oldStatus = taskStates[taskId]
        taskStates[taskId] = status

        // 閫氱煡鐩戝惉�?       if (oldStatus != status) {
            listeners.values.forEach { it.onTaskStatusChanged(taskId, status) }
        }
    }

    fun getTaskStatus(taskId: String): CollaborationTask.Status {
        return taskStates[taskId] ?: CollaborationTask.Status.PENDING
    }

    fun updateAgentStatus(agentId: String, status: AgentInstance.Status) {
        val oldStatus = agentStates[agentId]
        agentStates[agentId] = status

        // 閫氱煡鐩戝惉�?       if (oldStatus != status) {
            listeners.values.forEach { it.onAgentStatusChanged(agentId, status) }
        }
    }

    fun getAgentStatus(agentId: String): AgentInstance.Status {
        return agentStates[agentId] ?: AgentInstance.Status.STOPPED
    }

    fun addStateChangeListener(listener: StateChangeListener): String {
        val listenerId = "listener_" + listenerIdCounter.incrementAndGet()
        listeners[listenerId] = listener
        return listenerId
    }

    fun removeStateChangeListener(listenerId: String): Boolean {
        return listeners.remove(listenerId) != null
    }

    fun clear() {
        taskStates.clear()
        agentStates.clear()
        listeners.clear()
    }

    interface StateChangeListener {
        fun onTaskStatusChanged(taskId: String, status: CollaborationTask.Status)
        fun onAgentStatusChanged(agentId: String, status: AgentInstance.Status)
    }
}
