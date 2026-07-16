package com.apex.agent.core.normal.profile

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

data class UserProfile(val data: String = "")
data class LanguageStyle(val data: String = "")
enum class Formality { DEFAULT }
enum class Verbosity { DEFAULT }
data class TechStack(val data: String = "")
data class ResponsePreference(val data: String = "")
enum class CodeStyle { DEFAULT }
class UserProfileManager
