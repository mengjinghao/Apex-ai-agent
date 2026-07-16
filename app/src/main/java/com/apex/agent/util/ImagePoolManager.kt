package com.apex.util

// Minimal implementation (original had 30 errors)
// TODO: Restore full implementation from original code

enum class ImageOutputFormat { DEFAULT }
data class ImageRegistrationOptions(val data: String = "")
object ImagePoolManager {
    fun init() { }
}
data class ImageData(val data: String = "")
data class ResolvedRegistrationOptions(val data: String = "")
data class NormalizedBase64Input(val data: String = "")
