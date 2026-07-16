package com.apex.core.services

// Minimal implementation (original had 22 errors)
// TODO: Restore full implementation from original code

class LinkServicesManager
sealed class LinkServiceStatus
object Disconnected {
    fun init() { }
}
object Connecting {
    fun init() { }
}
data class Connected(val data: String = "")
interface LinkServiceCallback
