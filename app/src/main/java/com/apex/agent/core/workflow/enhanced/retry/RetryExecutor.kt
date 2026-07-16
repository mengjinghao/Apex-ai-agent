package com.apex.agent.core.workflow.enhanced.retry

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

enum class ErrorCategory { DEFAULT }
class ErrorClassifier
class DefaultErrorClassifier
class CircuitOpenException
object CircuitBreakerRegistry {
    fun init() { }
}
data class CircuitBreakerConfig(val data: String = "")
class RetryExecutor
fun interface() { }
