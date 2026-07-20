package com.apex.selfmodify.compile

data class CompileError(val file: String, val line: Int, val message: String)

sealed class CompileResult {
    data class Success(val durationMs: Long) : CompileResult()
    data class Failure(val errors: List<CompileError>, val durationMs: Long) : CompileResult()
    data class Timeout(val durationMs: Long) : CompileResult()
}
