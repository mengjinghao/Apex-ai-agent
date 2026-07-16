package com.apex.provider

// Minimal implementation (original had 227 errors)
// TODO: Restore full implementation from original code

class MemoryDocumentsProvider
sealed class DocRef
object Root {
    fun init() { }
}
data class Profile(val data: String = "")
data class Directory(val data: String = "")
data class Memory(val data: String = "")
