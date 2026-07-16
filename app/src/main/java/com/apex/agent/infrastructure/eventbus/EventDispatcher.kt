package com.apex.agent.infrastructure.eventbus

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

interface DispatchingStrategy
object Sequential {
    fun init() { }
}
data class Parallel(val data: String = "")
data class Ordered(val data: String = "")
interface BackpressureStrategy
object Drop {
    fun init() { }
}
data class Buffer(val data: String = "")
object Backpressure {
    fun init() { }
}
interface ErrorStrategy
object FailFast {
    fun init() { }
}
object ContinueOnError {
    fun init() { }
}
data class RetryOnError(val data: String = "")
class EventDispatcher
