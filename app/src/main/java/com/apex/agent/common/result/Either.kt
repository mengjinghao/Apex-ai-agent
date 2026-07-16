package com.apex.agent.common.result

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

sealed class Either
data class Left(val data: String = "")
data class Right(val data: String = "")
