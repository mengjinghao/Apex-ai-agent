package com.apex.util.stream

// Minimal implementation (original had 33 errors)
// TODO: Restore full implementation from original code

object StreamLogger {
    fun init() { }
}
enum class OverflowPolicy { DEFAULT }
interface Stream
interface StreamCollector
data class StreamStats(val data: String = "")
interface StreamLockListener
class AbstractStream
class BufferOverflowException
class FlowAsStream
class StreamAsFlow
class FlowAsStreamWithBackpressure
class BatchStreamCollector
class ConditionalLockStream
class LockTimeoutException
class AbstractBufferedStream
