package com.apex.agent.core.permissions.rbac

// Minimal implementation (original had 69 errors)
// TODO: Restore full implementation from original code

class RbacManager
data class CacheEntry(val data: String = "")
sealed class PermissionResult
object Granted {
    fun init() { }
}
data class Denied(val data: String = "")
class PermissionDeniedException
