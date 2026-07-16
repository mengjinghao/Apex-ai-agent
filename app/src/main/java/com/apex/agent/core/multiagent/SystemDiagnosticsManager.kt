
package com.apex.agent.core.multiagent

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class SystemDiagnosticsManager(private val context: Context) {

    data class DiagnosticResult(
        val component: String,
        val status: Status,
        val message: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )


    private val diagnostics = ConcurrentHashMap<String, DiagnosticResult>()

    fun runDiagnostics(): List<DiagnosticResult> {
        return diagnostics.values.toList()
    }

    fun registerComponent(name: String) {
        diagnostics[name] = DiagnosticResult(name, Status.UNKNOWN)
    }

    fun updateComponentStatus(name: String, status: Status, message: String = "") {
        diagnostics[name] = DiagnosticResult(name, status, message)
    }

    fun getComponentStatus(name: String): DiagnosticResult? {
        return diagnostics[name]
    }

    fun clear() {
        diagnostics.clear()
    }
}
