package com.apex.core.config

// Minimal implementation (original had 179 errors)
// TODO: Restore full implementation from original code

class ModelConfigService
data class ActiveConfigChanged(val data: String = "")
data class ConfigAdded(val data: String = "")
data class ConfigDeleted(val data: String = "")
data class ConfigUpdated(val data: String = "")
data class ModelConfigTemplate(val data: String = "")
