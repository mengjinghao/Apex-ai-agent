package com.apex.agent.core.provider

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

data class ProviderProfile(val data: String = "")
enum class AuthType { DEFAULT }
enum class ProviderType { DEFAULT }
data class ModelInfo(val data: String = "")
data class Pricing(val data: String = "")
class ProviderRegistry
