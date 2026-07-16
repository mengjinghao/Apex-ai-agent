package com.apex.agent.core.multiagent

import android.content.Context
import java.util.UUID

/**
 * Agent 反思系�?- 参�?GPTeam �?AutoGPT 的反思功�?
 * �?Agent 能够反思过去的行为并改�?
 */
data class Reflection(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: ReflectionType,
    val observation: String,
    val insight: String,
    val suggestion: String? = null,
    val relatedTaskId: String? = null,
    val importance: Float = 0.5f
)

enum class ReflectionType {
    SUCCESS, FAILURE, OBSERVATION, IMPROVEMENT
}

class ReflectionSystem(private val context: Context) {

    companion object {
        private const val TAG = "ReflectionSystem"
    }

    private val reflections = mutableListOf<Reflection>()

    fun recordObservation(
        agentId: String,
        observation: String,
        type: ReflectionType = ReflectionType.OBSERVATION,
        insight: String = "",
        taskId: String? = null
    ): Reflection {
        val reflection = Reflection(
            agentId = agentId,
            type = type,
            observation = observation,
            insight = insight,
            relatedTaskId = taskId
        )
        reflections.add(reflection)
        return reflection
    }

    fun generateReflectionsForAgent(agentId: String, limit: Int = 10): List<Reflection> {
        return reflections
            .filter { it.agentId == agentId }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    fun getInsightsForAgent(agentId: String): List<String> {
        return reflections
            .filter { it.agentId == agentId }
            .filter { it.insight.isNotEmpty() }
            .map { it.insight }
    }

    fun analyzePerformanceTrends(agentId: String): Map<String, Any> {
        val agentReflections = reflections.filter { it.agentId == agentId }

        val successRate = if (agentReflections.isNotEmpty()) {
            agentReflections.count { it.type == ReflectionType.SUCCESS }.toFloat() / agentReflections.size
        } else {
            0f
        }

        val improvementCount = agentReflections.count { it.type == ReflectionType.IMPROVEMENT }

        return mapOf(
            "success_rate" to successRate,
            "improvement_count" to improvementCount,
            "total_reflections" to agentReflections.size,
            "last_observation" to agentReflections.lastOrNull()?.observation
        )
    }

    fun generateImprovementSuggestions(agentId: String): List<String> {
        val agentReflections = reflections.filter { it.agentId == agentId }
        val suggestions = mutableListOf<String>()

        val recentFailures = agentReflections.filter { it.type == ReflectionType.FAILURE }.takeLast(3)

        recentFailures.forEach { failure ->
            suggestions.add("改进: ${failure.observation}")
        }

        val recentSuccess = agentReflections.takeLast(5)
        if (recentSuccess.isNotEmpty()) {
            suggestions.add("回顾上次成功: ${recentSuccess.first().observation}")
        }

        return suggestions.take(3)
    }
}
