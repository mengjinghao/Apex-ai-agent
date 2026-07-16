package com.apex.api.chat.enhance

// Minimal implementation (original had 20 errors)
// TODO: Restore full implementation from original code

class FileBindingService
enum class EditAction { DEFAULT }
enum class StructuredEditAction { DEFAULT }
data class StructuredEditOperation(val data: String = "")
data class EditOperation(val data: String = "")
data class MatchSearchResult(val data: String = "")
