package com.apex.util.stream

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

interface SharedStream
interface MutableSharedStream
interface StateStream
interface MutableStateStream
class MutableSharedStreamImpl
interface SharedEvent
data class Value(val data: String = "")
data class Completion(val data: String = "")
class MutableStateStreamImpl
enum class StreamStart { DEFAULT }
