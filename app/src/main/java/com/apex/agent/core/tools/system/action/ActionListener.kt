package com.apex.agent.core.tools.system.action

// Minimal implementation (original had 8 errors)
// TODO: Restore full implementation from original code

interface ActionListener
data class ActionEvent(val data: String = "")
data class ElementInfo(val data: String = "")
data class ListeningResult(val data: String = "")
data class PermissionStatus(val data: String = "")
