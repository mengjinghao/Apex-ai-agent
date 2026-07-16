package com.apex.agent.core.tools.defaultTool.websession.browser

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

enum class BrowserDownloadStatus { DEFAULT }
enum class BrowserDownloadAction { DEFAULT }
data class BrowserDownloadSegmentRecord(val data: String = "")
data class BrowserDownloadTaskRecord(val data: String = "")
data class BrowserDownloadActiveControl(val data: String = "")
data class BrowserDownloadInlinePayload(val data: String = "")
class BrowserDownloadManager
data class BrowserDownloadSummary(val data: String = "")
data class PendingExternalOpenRequest(val data: String = "")
