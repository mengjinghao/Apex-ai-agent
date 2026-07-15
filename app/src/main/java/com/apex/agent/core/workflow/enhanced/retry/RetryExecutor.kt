package com.apex.agent.core.workflow.enhanced.retry

// STUBBED: had 4 errors
enum class ErrorCategory { DEFAULT }
interface ErrorClassifier
class DefaultErrorClassifier
class CircuitOpenException
object CircuitBreakerRegistry
data class CircuitBreakerConfig(val placeholder: String = "")
class RetryExecutor
