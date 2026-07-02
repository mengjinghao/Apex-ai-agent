package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FeedbackCollector {

    data class Feedback(
        val id: String,
        val userId: String,
        val sessionId: String,
        val type: FeedbackType,
        val severity: Severity,
        val content: String,
        val timestamp: Long,
        val status: FeedbackStatus,
        val assignedTo: String? = null,
        val resolution: String? = null
    )

    enum class FeedbackType {
        BUG,         // 功能 bug
        FEATURE,     // 功能建议
        UI,          // UI/UX 问题
        PERFORMANCE, // 性能问题
        OTHER        // 其他问题
    }

    enum class Severity {
        CRITICAL,    // 严重，影响核心功�?       HIGH,        // 高，影响用户体验
        MEDIUM,      // 中，需要改�?       LOW          // 低，轻微问题
    }

    enum class FeedbackStatus {
        PENDING,     // 待处�?       IN_PROGRESS, // 处理�?       RESOLVED,    // 已解�?       CLOSED       // 已关�?   }

    private val feedbacks = ConcurrentHashMap<String, Feedback>()
    private val feedbackIdCounter = AtomicInteger(0)

    fun submitFeedback(userId: String, sessionId: String, type: FeedbackType, severity: Severity, content: String): String {
        val id = "feedback_${feedbackIdCounter.incrementAndGet()}"
        val feedback = Feedback(
            id = id,
            userId = userId,
            sessionId = sessionId,
            type = type,
            severity = severity,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = FeedbackStatus.PENDING
        )
        feedbacks[id] = feedback
        return id
    }

    fun getFeedback(id: String): Feedback? {
        return feedbacks[id]
    }

    fun getAllFeedbacks(): List<Feedback> {
        return feedbacks.values.toList()
    }

    fun getFeedbacksByType(type: FeedbackType): List<Feedback> {
        return feedbacks.values.filter { it.type == type }
    }

    fun getFeedbacksByStatus(status: FeedbackStatus): List<Feedback> {
        return feedbacks.values.filter { it.status == status }
    }

    fun getFeedbacksBySeverity(severity: Severity): List<Feedback> {
        return feedbacks.values.filter { it.severity == severity }
    }

    fun updateFeedbackStatus(id: String, status: FeedbackStatus, assignedTo: String? = null): Boolean {
        val feedback = feedbacks[id]
        if (feedback != null) {
            val updatedFeedback = feedback.copy(
                status = status,
                assignedTo = assignedTo
            )
            feedbacks[id] = updatedFeedback
            return true
        }
        return false
    }

    fun resolveFeedback(id: String, resolution: String): Boolean {
        val feedback = feedbacks[id]
        if (feedback != null) {
            val updatedFeedback = feedback.copy(
                status = FeedbackStatus.RESOLVED,
                resolution = resolution
            )
            feedbacks[id] = updatedFeedback
            return true
        }
        return false
    }

    fun closeFeedback(id: String): Boolean {
        val feedback = feedbacks[id]
        if (feedback != null) {
            val updatedFeedback = feedback.copy(
                status = FeedbackStatus.CLOSED
            )
            feedbacks[id] = updatedFeedback
            return true
        }
        return false
    }

    fun deleteFeedback(id: String): Boolean {
        return feedbacks.remove(id) != null
    }

    fun getFeedbackStats(): FeedbackStats {
        val total = feedbacks.size
        val byType = feedbacks.values.groupBy { it.type }.mapValues { it.value.size }
        val byStatus = feedbacks.values.groupBy { it.status }.mapValues { it.value.size }
        val bySeverity = feedbacks.values.groupBy { it.severity }.mapValues { it.value.size }

        return FeedbackStats(
            total = total,
            byType = byType,
            byStatus = byStatus,
            bySeverity = bySeverity
        )
    }

    data class FeedbackStats(
        val total: Int,
        val byType: Map<FeedbackType, Int>,
        val byStatus: Map<FeedbackStatus, Int>,
        val bySeverity: Map<Severity, Int>
    )

    fun exportFeedbacks(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,UserID,SessionID,Type,Severity,Content,Timestamp,Status,AssignedTo,Resolution")
        feedbacks.values.forEach { feedback ->
            sb.appendLine("${feedback.id},${feedback.userId},${feedback.sessionId},${feedback.type},${feedback.severity},${feedback.content.replace(",", " ")},${feedback.timestamp},${feedback.status},${feedback.assignedTo ?: ""},${feedback.resolution?.replace(",", " ") ?: ""}")
        }
        return sb.toString()
    }

    fun clearAllFeedbacks() {
        feedbacks.clear()
    }
}
