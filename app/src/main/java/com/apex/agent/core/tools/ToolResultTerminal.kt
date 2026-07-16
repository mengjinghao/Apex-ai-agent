package com.apex.core.tools

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

data class ADBResultData(val data: String = "")
data class TerminalCommandResultData(val data: String = "")
data class HiddenTerminalCommandResultData(val data: String = "")
data class TerminalSessionCreationResultData(val data: String = "")
data class TerminalSessionCloseResultData(val data: String = "")
data class TerminalSessionScreenResultData(val data: String = "")
data class GrepResultData(val data: String = "")
data class FileMatch(val data: String = "")
data class LineMatch(val data: String = "")
