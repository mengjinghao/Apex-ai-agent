package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CollaborationHistoryManager {

    companion object {
        private const val TAG = "CollaborationHistoryMgr"
    }

    data class CollaborationHistory(
        val taskId: String,
        val taskDescription: String,
        val collaborationMode: String,
        val agents: List<Agent>,
        val startTime: Long,
        val endTime: Long = 0,
        val status: CollaborationStatus,
        val events: List<HistoryEvent>,
        val messages: List<AgentMessage>,
        val decisionPoints: List<DecisionPoint>,
        val metrics: CollaborationMetrics
    )

    data class HistoryEvent(
        val timestamp: Long,
        val type: EventType,
        val agentId: String?,
        val description: String,
        val metadata: Map<String, Any> = emptyMap()
    )

    enum class EventType {
        TASK_CREATED,
        TASK_STARTED,
        TASK_PAUSED,
        TASK_RESUMED,
        TASK_COMPLETED,
        TASK_FAILED,
        AGENT_JOINED,
        AGENT_LEFT,
        AGENT_STATUS_CHANGED,
        MESSAGE_SENT,
        MESSAGE_RECEIVED,
        DECISION_MADE,
        CONFLICT_DETECTED,
        CONFLICT_RESOLVED
    }

    data class DecisionPoint(
        val timestamp: Long,
        val taskId: String,
        val description: String,
        val agentsInvolved: List<String>,
        val options: List<DecisionOption>,
        val chosenOption: String?,
        val reasoning: String?
    )

    data class DecisionOption(
        val optionId: String,
        val description: String,
        val votes: Map<String, Int> = emptyMap(),
        val outcome: String? = null
    )

    data class CollaborationMetrics(
        val totalDuration: Long = 0,
        val agentContribution: Map<String, AgentContribution> = emptyMap(),
        val messageCount: Int = 0,
        val decisionCount: Int = 0,
        val conflictCount: Int = 0,
        val successRate: Double = 0.0,
        val efficiencyScore: Double = 0.0
    )

    data class AgentContribution(
        val agentId: String,
        val agentName: String,
        val messageCount: Int = 0,
        val decisionParticipation: Int = 0,
        val activeTime: Long = 0,
        val tasksCompleted: Int = 0
    )

    enum class CollaborationStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED,
        PAUSED
    }

    private val histories = ConcurrentHashMap<String, CollaborationHistory>()
    private val eventListeners = CopyOnWriteArrayList<HistoryListener>()
    private var currentTaskId: String? = null

    interface HistoryListener {
        fun onHistoryUpdated(taskId: String, history: CollaborationHistory)
        fun onEventRecorded(taskId: String, event: HistoryEvent)
        fun onDecisionPointAdded(taskId: String, decisionPoint: DecisionPoint)
    }

    fun startRecording(taskId: String, taskDescription: String, collaborationMode: String, agents: List<Agent>) {
        currentTaskId = taskId

        val history = CollaborationHistory(
            taskId = taskId,
            taskDescription = taskDescription,
            collaborationMode = collaborationMode,
            agents = agents,
            startTime = System.currentTimeMillis(),
            status = CollaborationStatus.IN_PROGRESS,
            events = emptyList(),
            messages = emptyList(),
            decisionPoints = emptyList(),
            metrics = CollaborationMetrics()
        )

        histories[taskId] = history

        recordEvent(
            taskId,
            EventType.TASK_STARTED,
            null,
            "协作任务开 ${taskDescription}"
        )

        notifyHistoryUpdated(taskId, history)
    }

    fun recordEvent(taskId: String, type: EventType, agentId: String?, description: String, metadata: Map<String, Any> = emptyMap()) {
        val history = histories[taskId] ?: return

        val event = HistoryEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            agentId = agentId,
            description = description,
            metadata = metadata
        )

        val updatedEvents = history.events + event
        histories[taskId] = history.copy(events = updatedEvents)

        eventListeners.forEach { listener ->
            try {
                listener.onEventRecorded(taskId, event)
            } catch (e: Exception) {
                AppLogger.e(TAG, "recordEvent listener error", e)
            }
        }
    }

    fun recordMessage(taskId: String, message: AgentMessage) {
        val history = histories[taskId] ?: return

        val updatedMessages = history.messages + message
        histories[taskId] = history.copy(messages = updatedMessages)

        recordEvent(
            taskId,
            EventType.MESSAGE_SENT,
            message.sender,
            "发送消 ${message.content.take(50)}"
        )
    }

    fun addDecisionPoint(taskId: String, description: String, agentsInvolved: List<String>, options: List<DecisionOption>) {
        val history = histories[taskId] ?: return

        val decisionPoint = DecisionPoint(
            timestamp = System.currentTimeMillis(),
            taskId = taskId,
            description = description,
            agentsInvolved = agentsInvolved,
            options = options,
            chosenOption = null,
            reasoning = null
        )

        val updatedDecisionPoints = history.decisionPoints + decisionPoint
        histories[taskId] = history.copy(decisionPoints = updatedDecisionPoints)

        recordEvent(
            taskId,
            EventType.DECISION_MADE,
            null,
            "决策 ${description}"
        )

        eventListeners.forEach { listener ->
            try {
                listener.onDecisionPointAdded(taskId, decisionPoint)
            } catch (e: Exception) {
                AppLogger.e(TAG, "addDecisionPoint listener error", e)
            }
        }
    }

    fun resolveDecision(taskId: String, decisionPointIndex: Int, chosenOption: String, reasoning: String) {
        val history = histories[taskId] ?: return

        if (decisionPointIndex >= history.decisionPoints.size) return

        val updatedDecisionPoints = history.decisionPoints.toMutableList()
        val decisionPoint = updatedDecisionPoints[decisionPointIndex]
        updatedDecisionPoints[decisionPointIndex] = decisionPoint.copy(
            chosenOption = chosenOption,
            reasoning = reasoning
        )

        histories[taskId] = history.copy(decisionPoints = updatedDecisionPoints)

        recordEvent(
            taskId,
            EventType.DECISION_MADE,
            null,
            "决策已确 ${chosenOption}"
        )
    }

    fun recordConflict(taskId: String, description: String, agentsInvolved: List<String>) {
        val history = histories[taskId] ?: return

        recordEvent(
            taskId,
            EventType.CONFLICT_DETECTED,
            null,
            "冲突: ${description}, 涉及Agent: ${agentsInvolved.joinToString()}"
        )

        val updatedMetrics = history.metrics.copy(
            conflictCount = history.metrics.conflictCount + 1
        )
        histories[taskId] = history.copy(metrics = updatedMetrics)
    }

    fun stopRecording(taskId: String, status: CollaborationStatus) {
        val history = histories[taskId] ?: return

        val endTime = System.currentTimeMillis()
        val totalDuration = endTime - history.startTime

        val updatedMetrics = calculateMetrics(history, totalDuration)
        val updatedHistory = history.copy(
            endTime = endTime,
            status = status,
            metrics = updatedMetrics
        )

        histories[taskId] = updatedHistory

        val eventType = when (status) {
            CollaborationStatus.COMPLETED -> EventType.TASK_COMPLETED
            CollaborationStatus.FAILED -> EventType.TASK_FAILED
            CollaborationStatus.CANCELLED -> EventType.TASK_STOPPED
            else -> EventType.TASK_COMPLETED
        }

        recordEvent(taskId, eventType, null, "协作任务结束: ${status}")

        if (currentTaskId == taskId) {
            currentTaskId = null
        }

        notifyHistoryUpdated(taskId, updatedHistory)
    }

    private fun calculateMetrics(history: CollaborationHistory, totalDuration: Long): CollaborationMetrics {
        val agentContribution = mutableMapOf<String, AgentContribution>()

        history.agents.forEach { agent ->
            val agentMessages = history.messages.filter { it.sender == agent.name }
            val agentDecisions = history.decisionPoints.filter { agent.id in it.agentsInvolved }

            agentContribution[agent.id] = AgentContribution(
                agentId = agent.id,
                agentName = agent.name,
                messageCount = agentMessages.size,
                decisionParticipation = agentDecisions.size,
                activeTime = calculateActiveTime(agent.id, history.events),
                tasksCompleted = agentDecisions.count { it.chosenOption != null }
            )
        }

        val messageCount = history.messages.size
        val decisionCount = history.decisionPoints.size
        val successRate = if (decisionCount > 0) {
            history.decisionPoints.count { it.chosenOption != null }.toDouble() / decisionCount
        } else {
            0.0
        }

        val efficiencyScore = if (totalDuration > 0) {
            (messageCount + decisionCount * 2).toDouble() / (totalDuration / 1000)
        } else {
            0.0
        }

        return CollaborationMetrics(
            totalDuration = totalDuration,
            agentContribution = agentContribution,
            messageCount = messageCount,
            decisionCount = decisionCount,
            conflictCount = history.metrics.conflictCount,
            successRate = successRate,
            efficiencyScore = efficiencyScore
        )
    }

    private fun calculateActiveTime(agentId: String, events: List<HistoryEvent>): Long {
        var activeTime = 0L
        var lastActiveStart: Long? = null

        events
            .filter { it.agentId == agentId }
            .sortedBy { it.timestamp }
            .forEach { event ->
                when (event.type) {
                    EventType.AGENT_JOINED, EventType.AGENT_STATUS_CHANGED -> {
                        if (lastActiveStart == null) {
                            lastActiveStart = event.timestamp
                        }
                    }
                    EventType.AGENT_LEFT -> {
                        lastActiveStart?.let { start ->
                            activeTime += event.timestamp - start
                        }
                        lastActiveStart = null
                    }
                    else -> {}
                }
            }

        return activeTime
    }

    fun getHistory(taskId: String): CollaborationHistory? {
        return histories[taskId]
    }

    fun getAllHistories(): List<CollaborationHistory> {
        return histories.values.toList()
    }

    fun getHistoriesByStatus(status: CollaborationStatus): List<CollaborationHistory> {
        return histories.values.filter { it.status == status }
    }

    fun addListener(listener: HistoryListener) {
        eventListeners.add(listener)
    }

    fun removeListener(listener: HistoryListener) {
        eventListeners.remove(listener)
    }

    private fun notifyHistoryUpdated(taskId: String, history: CollaborationHistory) {
        eventListeners.forEach { listener ->
            try {
                listener.onHistoryUpdated(taskId, history)
            } catch (e: Exception) {
                AppLogger.e(TAG, "notifyHistoryUpdated listener error", e)
            }
        }
    }

    fun exportHistory(taskId: String): String {
        val history = histories[taskId] ?: return "{}"

        return buildString {
            appendLine("=== 协作历史记录 ===")
            appendLine("任务ID: ${history.taskId}")
            appendLine("任务描述: ${history.taskDescription}")
            appendLine("协作模式: ${history.collaborationMode}")
            appendLine("开始时 ${formatTime(history.startTime)}")
            if (history.endTime > 0) {
                appendLine("结束时间: ${formatTime(history.endTime)}")
                appendLine("总时 ${history.metrics.totalDuration / 1000})
            }
            appendLine("状 ${history.status}")
            appendLine()

            appendLine("=== 参与Agent ===")
            history.agents.forEach { agent ->
                appendLine("- ${agent.name} (${agent.role})")
            }
            appendLine()

            appendLine("=== 数据流分析报�?===")
            history.events.forEach { event ->
                appendLine("[${formatTime(event.timestamp)}] ${event.type}: ${event.description}")
            }
            appendLine()

            if (history.decisionPoints.isNotEmpty()) {
                appendLine("=== 决策===")
                history.decisionPoints.forEachIndexed { index, decision ->
                    appendLine("${index + 1}. ${decision.description}")
                    appendLine("   涉及Agent: ${decision.agentsInvolved.joinToString()}")
                    appendLine("   选项: ${decision.options.size}")
                    if (decision.chosenOption != null) {
                        appendLine("   选择: ${decision.chosenOption}")
                        appendLine("   理由: ${decision.reasoning}")
                    }
                }
                appendLine()
            }

            appendLine("=== 统计指标 ===")
            appendLine("消息 ${history.metrics.messageCount}")
            appendLine("决策 ${history.metrics.decisionCount}")
            appendLine("冲突 ${history.metrics.conflictCount}")
            appendLine("成功 ${String.format("%.1f", history.metrics.successRate * 100)}%")
            appendLine("效率得分: ${String.format("%.2f", history.metrics.efficiencyScore)}")
            appendLine()

            appendLine("=== Agent贡献 ===")
            history.metrics.agentContribution.forEach { (agentId, contribution) ->
                appendLine("${contribution.agentName}:")
                appendLine("  - 消息 ${contribution.messageCount}")
                appendLine("  - 决策参与: ${contribution.decisionParticipation}")
                appendLine("  - 完成任务: ${contribution.tasksCompleted}")
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    fun clearHistory(taskId: String) {
        histories.remove(taskId)
    }

    fun clearAllHistories() {
        histories.clear()
    }

    fun getHistoryCount(): Int = histories.size

    companion object {
        private var instance: CollaborationHistoryManager? = null

        fun getInstance(): CollaborationHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: CollaborationHistoryManager().also { instance = it }
            }
        }
    }
}
