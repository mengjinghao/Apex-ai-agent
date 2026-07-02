package com.apex.agent.plugins.burst.base

interface SkillEventBus {
    fun publish(event: SkillEvent)
    fun subscribe(eventType: String, handler: (SkillEvent) -> Unit): () -> Unit
    fun unsubscribe(eventType: String, handler: (SkillEvent) -> Unit)
    fun getPendingEvents(skillId: String): List<SkillEvent>
}

data class SkillEvent(
    val type: String,
    val sourceSkillId: String,
    val targetSkillIds: List<String> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

object SkillEventTypes {
    const val TASK_COMPLETED = "task.completed"
    const val TASK_FAILED = "task.failed"
    const val SKILL_LOADED = "skill.loaded"
    const val SKILL_UNLOADED = "skill.unloaded"
    const val LLM_RESPONSE = "llm.response"
    const val MEMORY_UPDATED = "memory.updated"
    const val KNOWLEDGE_UPDATED = "knowledge.updated"
    const val SECURITY_ALERT = "security.alert"
    const val STATE_CHANGED = "state.changed"
    const val CUSTOM = "custom"
}
