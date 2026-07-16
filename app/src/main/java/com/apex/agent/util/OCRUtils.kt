package com.apex.util

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

object OCRUtils {
    fun init() { }
}
enum class Language { DEFAULT }
enum class Quality { DEFAULT }
sealed class OCRResult
data class Success(val data: String = "")
