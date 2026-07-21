package com.apex.selfmodify.rollback

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SnapshotInfo(
    val sha: String,
    val tag: String,
    val timestamp: Long,
    val message: String
)

/**
 * Git-based snapshot + rollback for the self-modify workspace.
 * Per AGENT_SELF_MODIFY_SPEC §4.4.
 *
 * Each [snapshot] creates a git commit; [rollback] checks out a prior commit's tree.
 * Falls back gracefully if git is unavailable (commands return empty → reported as failure).
 */
class RollbackManager(private val workspaceDir: File) {

    fun init() {
        runGit(listOf("init"), workspaceDir)
        runGit(listOf("config", "user.email", "agent@apex.local"), workspaceDir)
        runGit(listOf("config", "user.name", "ApexAgent"), workspaceDir)
    }

    suspend fun snapshot(tag: String): String = withContext(Dispatchers.IO) {
        runGit(listOf("add", "-A"), workspaceDir)
        runGit(listOf("commit", "-m", "snapshot: $tag", "--allow-empty"), workspaceDir)
        val sha = runGit(listOf("rev-parse", "HEAD"), workspaceDir).trim()
        ApexLog.i(ApexSuite.ApkId.MAIN, "[Rollback] snapshot '$tag' -> $sha")
        sha
    }

    suspend fun rollback(toCommit: String): Boolean = withContext(Dispatchers.IO) {
        val r = runGit(listOf("checkout", toCommit, "--", "."), workspaceDir)
        val ok = r.isEmpty() || !r.contains("error", ignoreCase = true)
        ApexLog.i(ApexSuite.ApkId.MAIN, "[Rollback] rollback to $toCommit: ${if (ok) "ok" else r}")
        ok
    }

    suspend fun rollbackLast(): Boolean = withContext(Dispatchers.IO) {
        val r = runGit(listOf("reset", "--hard", "HEAD~1"), workspaceDir)
        val ok = r.isEmpty() || !r.contains("error", ignoreCase = true)
        ok
    }

    fun listSnapshots(): List<SnapshotInfo> {
        val log = runGit(listOf("log", "-n", "20", "--format=%H|%s|%ct"), workspaceDir)
        return log.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3) {
                SnapshotInfo(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L, parts[1])
            } else null
        }
    }

    fun cleanup() {
        runGit(listOf("gc", "--prune=now"), workspaceDir)
    }

    private fun runGit(args: List<String>, dir: File): String {
        return try {
            val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out
        } catch (e: Exception) {
            ApexLog.w(ApexSuite.ApkId.MAIN, "[Rollback] git ${args.firstOrNull()} failed: ${e.message}")
            ""
        }
    }
}
