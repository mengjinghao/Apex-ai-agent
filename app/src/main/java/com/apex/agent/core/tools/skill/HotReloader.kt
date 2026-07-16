package com.apex.agent.core.tools.skill

// Minimal implementation (original had 6 errors)
// TODO: Restore full implementation from original code

class HotReloader
data class FileChange(val data: String = "")
data class ReloadEvent(val data: String = "")
interface ReloadListener
data class ReloadStats(val data: String = "")
