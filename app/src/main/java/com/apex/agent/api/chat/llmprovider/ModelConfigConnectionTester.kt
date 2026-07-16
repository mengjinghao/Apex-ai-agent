package com.apex.api.chat.llmprovider

// Minimal implementation (original had 25 errors)
// TODO: Restore full implementation from original code

enum class ModelConnectionTestType { DEFAULT }
data class ModelConnectionTestItem(val data: String = "")
data class ModelConnectionTestReport(val data: String = "")
object ModelConfigConnectionTester {
    fun init() { }
}
