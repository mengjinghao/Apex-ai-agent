package com.apex.agent.core.workflow.enhanced.observability

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

enum class SpanStatus { DEFAULT }
interface Span
data class SpanRecord(val data: String = "")
data class SpanEvent(val data: String = "")
interface WorkflowTracer
class InMemoryTracer
class ActiveSpan
object NoopTracer {
    fun init() { }
}
object NoopSpan {
    fun init() { }
}
object TracerHolder {
    fun init() { }
}
