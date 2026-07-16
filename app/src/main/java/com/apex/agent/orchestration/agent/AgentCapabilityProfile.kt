package com.apex.agent.orchestration.agent

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject



    private val capabilityScores = ConcurrentHashMap<String, Double>()
    private val executionHistory = mutableListOf<ExecutionRecord>()
    private val lastUpdated = ConcurrentHashMap<String, Long>()

    private val decayHours = 24L
    private val historyRetentionSize = 500
    private val learningRate = 0.1

    fun setCapability(name: String, score: Double) {
        capabilityScores[name] = score.coerceIn(0.0, 1.0)
        lastUpdated[name] = System.currentTimeMillis()
    }

    fun getCapability(name: String): Double {
        val raw = capabilityScores[name] ?: return 0.0
        return applyDecay(raw, name)
    }

    fun getAllCapabilities(): Map<String, Double> {
        return capabilityScores.keys.associateWith { getCapability(it) }
    }

    fun getWeightedCapabilities(): Map<String, Double> {
        return getAllCapabilities().entries
            .sortedByDescending { it.value }
            .take(20)
            .associate { it.toPair() }
    }

    fun removeCapability(name: String) {
        capabilityScores.remove(name)
        lastUpdated.remove(name)
    }

    fun recordExecution(capability: String, success: Boolean, timeMs: Long, taskDescription: String = "") {
        executionHistory.add(
            ExecutionRecord(
                capability = capability,
                success = success,
                timestamp = System.currentTimeMillis(),
                executionTimeMs = timeMs,
                taskDescription = taskDescription
            )
        )
        if (executionHistory.size > historyRetentionSize) {
            executionHistory.removeAt(0)
        }
        val oldScore = capabilityScores[capability] ?: 0.5
        val adjustment = if (success) learningRate else -learningRate * 0.5
        val newScore = (oldScore + adjustment).coerceIn(0.0, 1.0)
        capabilityScores[capability] = newScore
        lastUpdated[capability] = System.currentTimeMillis()
    }

    fun predictCapability(taskDescription: String, category: String): Double {
        val directScore = capabilityScores[category]
        if (directScore != null) return applyDecay(directScore, category)

        val relatedScores = capabilityScores.entries.filter { (key) ->
            key != category && isRelatedCategory(key, category)
        }.map { it.value }

        if (relatedScores.isEmpty()) return 0.3

        return relatedScores.average().coerceIn(0.0, 1.0)
    }

    fun getExecutionHistory(): List<ExecutionRecord> = executionHistory.toList()

    fun getSuccessRate(capability: String? = null): Double {
        val filtered = if (capability != null) {
            executionHistory.filter { it.capability == capability }
        } else {
            executionHistory
        }
        if (filtered.isEmpty()) return 0.0
        return filtered.count { it.success }.toDouble() / filtered.size
    }

    fun getExecutionCount(capability: String? = null): Int {
        return if (capability != null) {
            executionHistory.count { it.capability == capability }
        } else {
            executionHistory.size
        }
    }

    fun getRecentTrend(capability: String, recentCount: Int = 10): Float {
        val records = executionHistory
            .filter { it.capability == capability }
            .takeLast(recentCount)
        if (records.isEmpty()) return 0.5f
        return records.count { it.success }.toFloat() / records.size
    }

    fun clear() {
        capabilityScores.clear()
        executionHistory.clear()
        lastUpdated.clear()
    }

    private fun applyDecay(score: Double, capability: String): Double {
        val lastUpdate = lastUpdated[capability] ?: return score
        val elapsedHours = (System.currentTimeMillis() - lastUpdate) / 3_600_000.0
        if (elapsedHours <= 0) return score
        val decayFactor = Math.pow(0.95, elapsedHours / decayHours)
        return score * decayFactor
    }

    private fun isRelatedCategory(a: String, b: String): Boolean {
        val groups = mapOf(
            "coding" to setOf("debugging", "testing", "devops"),
            "debugging" to setOf("coding", "testing"),
            "testing" to setOf("coding", "debugging"),
            "writing" to setOf("creative", "research"),
            "research" to setOf("writing", "analysis"),
            "analysis" to setOf("research", "data"),
            "data" to setOf("analysis"),
            "design" to setOf("creative", "planning"),
            "planning" to setOf("design", "analysis"),
            "creative" to setOf("writing", "design"),
            "devops" to setOf("coding", "security"),
            "security" to setOf("devops", "coding")
        )
        return groups[a]?.contains(b) == true || groups[b]?.contains(a) == true
    }
}
