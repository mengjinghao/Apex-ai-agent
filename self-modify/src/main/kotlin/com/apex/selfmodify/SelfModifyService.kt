package com.apex.selfmodify

import android.content.Context
import com.apex.selfmodify.audit.AuditLog
import com.apex.selfmodify.compile.CompileGate
import com.apex.selfmodify.compile.CompileResult
import com.apex.selfmodify.index.CodeIndex
import com.apex.selfmodify.index.CodeIndexer
import com.apex.selfmodify.index.InMemoryCodeIndex
import com.apex.selfmodify.plan.ApplyResult
import com.apex.selfmodify.plan.ModificationPlan
import com.apex.selfmodify.plan.RiskLevel
import com.apex.selfmodify.watch.AndroidFileWatcher
import com.apex.selfmodify.watch.FileWatcher
import com.apex.selfmodify.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class SelfModifyService(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val workspace: WorkspaceManager = WorkspaceManager(context)
    val watcher: FileWatcher = AndroidFileWatcher()
    val indexer: CodeIndexer = CodeIndexer(workspace)
    val index: CodeIndex = InMemoryCodeIndex(indexer)
    val audit: AuditLog = AuditLog(workspace)
    private val compileGate: CompileGate = CompileGate(workspace.config.rootDir)

    suspend fun init() {
        watcher.start(workspace.config.rootDir)
        indexer.reindexAll()
    }

    // Read APIs
    suspend fun readFile(path: String) = workspace.readFile(path)
    suspend fun findSymbol(name: String) = index.findSymbol(name)
    suspend fun findReferences(symbol: String) = index.findReferences(symbol)
    suspend fun listFiles(pattern: String) = index.listFiles(pattern)

    // Modify API
    suspend fun apply(plan: ModificationPlan): ApplyResult {
        if (plan.riskLevel in setOf(RiskLevel.HIGH, RiskLevel.CRITICAL) && plan.requiresUserConfirm) {
            return ApplyResult.Rejected("Requires user confirmation for ${plan.riskLevel} risk")
        }
        // Apply changes
        plan.changes.forEach { change ->
            when (change.type) {
                com.apex.selfmodify.workspace.ChangeType.CREATE,
                com.apex.selfmodify.workspace.ChangeType.MODIFY -> workspace.writeFile(change.path, change.newContent ?: "")
                com.apex.selfmodify.workspace.ChangeType.DELETE -> workspace.deleteFile(change.path)
                com.apex.selfmodify.workspace.ChangeType.MOVE -> { /* TODO */ }
            }
        }
        // Compile gate
        val result = compileGate.compile("app")
        val compileOk = result is CompileResult.Success
        // Audit
        audit.record(plan.id, plan.agentId, plan.changes.map { it.path }, compileOk, null)
        return if (compileOk) ApplyResult.Success(plan, (result as CompileResult.Success).durationMs)
        else ApplyResult.RolledBack(plan, "compile failed: ${(result as? CompileResult.Failure)?.errors?.size ?: 0} errors")
    }
}
