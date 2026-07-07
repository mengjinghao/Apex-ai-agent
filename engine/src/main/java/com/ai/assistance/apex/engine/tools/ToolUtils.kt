package com.ai.assistance.apex.engine.tools

import com.ai.assistance.apex.engine.model.ExecutionResult

fun parseArgs(args: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    val parts = args.split(" ")
    var i = 0
    while (i < parts.size) {
        if (parts[i].startsWith("--")) {
            val key = parts[i].substring(2)
            val value = StringBuilder()
            i++
            while (i < parts.size && !parts[i].startsWith("--")) {
                if (value.isNotEmpty()) value.append(" ")
                value.append(parts[i])
                i++
            }
            params[key] = value.toString()
        } else {
            i++
        }
    }
    return params
}

fun errorResult(message: String): ExecutionResult {
    return ExecutionResult().apply {
        exitCode = -1
        error = message
        success = false
    }
}

fun successResult(output: String, exitCode: Int = 0): ExecutionResult {
    return ExecutionResult().apply {
        this.exitCode = exitCode
        this.output = output
        success = exitCode == 0
    }
}
