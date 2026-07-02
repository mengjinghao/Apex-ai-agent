package com.ai.assistance.apex.engine.tools

import com.ai.assistance.apex.engine.model.ExecutionResult

interface Tool {
    val name: String
    val description: String
    val category: String
    val parameters: Array<String>
    val requiresRoot: Boolean

    fun execute(args: String): ExecutionResult
}