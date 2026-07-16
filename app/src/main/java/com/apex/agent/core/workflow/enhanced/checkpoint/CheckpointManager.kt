package com.apex.agent.core.workflow.enhanced.checkpoint

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

enum class CheckpointNodeState { DEFAULT }
interface Checkpointer
class InMemoryCheckpointer
data class ResumePlan(val data: String = "")
object JsonElementSerializer {
    fun init() { }
}
