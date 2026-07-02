package com.apex.agent.orchestration.memory

data class CollaborationSessionMemory(
    val sessionId: String,
    val taskId: String,
    val taskDescription: String,
    val agents: MutableList<AgentMemory> = mutableListOf(),
    val decisions: MutableList<Decision> = mutableListOf(),
    val sharedContext: MutableList<ContextItem> = mutableListOf(),
    val taskProgress: MutableList<ProgressUpdate> = mutableListOf(),
    val artifacts: MutableList<Artifact> = mutableListOf(),
    val startTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = System.currentTimeMillis(),
    var summary: String = ""
)

data class AgentMemory(
    val agentId: String,
    val agentName: String,
    val role: String,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val decisionsMade: MutableList<String> = mutableListOf(),
    val contributions: MutableList<String> = mutableListOf(),
    val status: String = "idle",
    val lastActive: Long = System.currentTimeMillis()
)

data class Decision(
    val id: String,
    val description: String,
    val proposedBy: String,
    val agreedBy: List<String> = emptyList(),
    val opposedBy: List<String> = emptyList(),
    val status: DecisionStatus = DecisionStatus.PROPOSED,
    val reasoning: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)

enum class DecisionStatus {
    PROPOSED, UNDER_DISCUSSION, AGREED, REJECTED, SUPERSEDED
}

data class ContextItem(
    val key: String,
    val value: String,
    val source: String = "",
    val category: ContextCategory = ContextCategory.GENERAL,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

enum class ContextCategory {
    GOAL, CONSTRAINT, REQUIREMENT, PREFERENCE, FACT, INSIGHT, WARNING, GENERAL
}

data class ProgressUpdate(
    val agentId: String,
    val taskRef: String,
    val status: String,
    val progress: Float,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class Artifact(
    val id: String,
    val name: String,
    val type: ArtifactType,
    val content: String,
    val createdBy: String,
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ArtifactType {
    CODE, DOCUMENT, PLAN, DIAGRAM, REPORT, DATA, CONFIG, OTHER
}
