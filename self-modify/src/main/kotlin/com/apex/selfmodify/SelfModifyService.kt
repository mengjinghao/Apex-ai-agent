package com.apex.selfmodify

import android.content.Context
import com.apex.selfmodify.audit.AuditLog
import com.apex.selfmodify.index.CodeIndex
import com.apex.selfmodify.index.CodeIndexer
import com.apex.selfmodify.index.InMemoryCodeIndex
import com.apex.selfmodify.plan.ApplyResult
import com.apex.selfmodify.plan.ModificationPlan
import com.apex.selfmodify.plan.PlanExecutor
import com.apex.selfmodify.plan.RiskLevel
import com.apex.selfmodify.reload.DexHotReloader
import com.apex.selfmodify.reload.HotReloader
import com.apex.selfmodify.rollback.RollbackManager
import com.apex.selfmodify.rollback.SnapshotInfo
import com.apex.selfmodify.watch.AndroidFileWatcher
import com.apex.selfmodify.watch.FileWatcher
import com.apex.selfmodify.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Facade for the Agent self-modify subsystem.
 * Per AGENT_SELF_MODIFY_SPEC §8.1.
 *
 * Wires together: WorkspaceManager (sandbox) + FileWatcher + CodeIndex +
 * CompileGate + RollbackManager + HotReloader + AuditLog + PlanExecutor.
 *
 * Usage:
 * ```
 * val svc = SelfModifyService(context)
 * svc.init()                        // start watcher + build index
 * val syms = svc.findSymbol("RageEngine")
 * val plan = ModificationPlan(...)
 * val result = svc.apply(plan)      // snapshot → apply → compile → reload → audit
 * ```
 */
class SelfModifyService(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val workspace: WorkspaceManager = WorkspaceManager(context)
    val watcher: FileWatcher = AndroidFileWatcher()
    val indexer: CodeIndexer = CodeIndexer(workspace)
    val index: CodeIndex = InMemoryCodeIndex(indexer)
    val audit: AuditLog = AuditLog(workspace)
    val rollback: RollbackManager = RollbackManager(workspace.config.rootDir)
    val reloader: HotReloader = DexHotReloader(workspace.config.indexDir)

    private val compileGate = com.apex.selfmodify.compile.CompileGate(workspace.config.rootDir)
    private val planExecutor: PlanExecutor = PlanExecutor(
        workspace, compileGate, reloader, rollback, audit, scope
    )

    suspend fun init() {
        rollback.init()
        watcher.start(workspace.config.rootDir)
        indexer.reindexAll()
    }

    // ---- Read APIs ----
    suspend fun readFile(path: String) = workspace.readFile(path)
    suspend fun findSymbol(name: String) = index.findSymbol(name)
    suspend fun findReferences(symbol: String) = index.findReferences(symbol)
    suspend fun listFiles(pattern: String) = index.listFiles(pattern)

    // ---- Modify API ----
    suspend fun apply(plan: ModificationPlan): ApplyResult {
        if (plan.riskLevel in setOf(RiskLevel.HIGH, RiskLevel.CRITICAL) && plan.requiresUserConfirm) {
            return ApplyResult.Rejected("Requires user confirmation for ${plan.riskLevel} risk")
        }
        return planExecutor.execute(plan)
    }

    // ---- Rollback API ----
    suspend fun rollback(toCommit: String? = null): Boolean =
        if (toCommit != null) rollback_rollback(toCommit) else rollback_rollbackLast()

    private suspend fun rollback_rollback(toCommit: String): Boolean = rollback.rollback(toCommit)
    private suspend fun rollback_rollbackLast(): Boolean = rollback.rollbackLast()

    fun listSnapshots(): List<SnapshotInfo> = rollback.listSnapshots()

    /**
     * Rebuild the code index from scratch. Returns the number of symbols indexed.
     * Per AGENT_SELF_MODIFY_SPEC §5.3 — full reindex triggered by [reindex] bridge method.
     */
    suspend fun reindex(): Int = indexer.reindexAll()
}
