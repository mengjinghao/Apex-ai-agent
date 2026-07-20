package com.apex.selfmodify.audit

import com.apex.selfmodify.workspace.WorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.security.MessageDigest

@Serializable
data class AuditEntry(
    val timestamp: Long,
    val planId: String,
    val agentId: String,
    val filesChanged: List<String>,
    val compileSuccess: Boolean,
    val reloadSuccess: Boolean?,
    val previousHash: String,
    val thisHash: String
)

class AuditLog(private val workspace: WorkspaceManager) {
    private val logFile: File by lazy { File(workspace.config.auditDir, "audit.log") }
    private val json = Json { encodeDefaults = true }

    fun record(planId: String, agentId: String, files: List<String>, compileOk: Boolean, reloadOk: Boolean?) {
        val ts = System.currentTimeMillis()
        val prev = readLastHash()
        val entry = AuditEntry(ts, planId, agentId, files, compileOk, reloadOk, prev, "")
        val computed = hash(entry)
        val final = entry.copy(thisHash = computed)
        logFile.appendText(serialize(final) + "\n")
    }

    fun verify(): Boolean {
        val lines = logFile.takeIf { it.exists() }?.readLines() ?: return true
        var prevHash = ""
        for (line in lines) {
            val entry = deserialize(line) ?: return false
            if (entry.previousHash != prevHash) return false
            val computed = hash(entry)
            if (computed != entry.thisHash) return false
            prevHash = entry.thisHash
        }
        return true
    }

    private fun readLastHash(): String =
        logFile.takeIf { it.exists() }?.readLines()?.lastOrNull()?.let { deserialize(it)?.thisHash } ?: ""

    private fun hash(e: AuditEntry): String {
        val data = "${e.timestamp}|${e.planId}|${e.agentId}|${e.filesChanged.joinToString(",")}|${e.compileSuccess}|${e.reloadSuccess}|${e.previousHash}"
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun serialize(e: AuditEntry): String = json.encodeToString(serializer(), e)

    private fun deserialize(line: String): AuditEntry? = try {
        json.decodeFromString(serializer(), line)
    } catch (e: Exception) { null }
}
