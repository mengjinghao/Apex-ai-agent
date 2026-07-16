package com.apex.agent.core.performance

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

class CircuitBreaker
data class CircuitBreakerMetrics(val data: String = "")
class RateLimiter
data class RateLimiterMetrics(val data: String = "")
class ConcurrencyLimiter
class ConcurrencyLimitExceededException
class TimeoutHandler
class RetryHandler
data class RetryMetrics(val data: String = "")
