package com.apex.selfmodify.workspace

import android.content.Context
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import java.io.File
import java.io.FileNotFoundException

class WorkspaceManager(private val context: Context) {
    val config: WorkspaceConfig by lazy { initWorkspace() }

    private fun initWorkspace(): WorkspaceConfig {
        val root = File(context.filesDir, "workspace").apply { mkdirs() }
        val src = File(root, "src").apply { mkdirs() }
        val snapshots = File(root, ".snapshots").apply { mkdirs() }
        val index = File(root, ".index").apply { mkdirs() }
        val audit = File(root, ".audit").apply { mkdirs() }
        val ws = WorkspaceConfig(root, listOf(src), snapshots, index, audit)
        ApexLog.i(ApexSuite.ApkId.MAIN, "[SelfModify] workspace at ${root.absolutePath}")
        return ws
    }

    fun resolvePath(relativePath: String): File {
        val resolved = File(config.rootDir, relativePath).canonicalFile
        if (!resolved.path.startsWith(config.rootDir.canonicalPath)) {
            throw SecurityException("Path '$relativePath' escapes workspace sandbox")
        }
        return resolved
    }

    suspend fun readFile(relativePath: String): String {
        val file = resolvePath(relativePath)
        if (!file.exists()) throw FileNotFoundException("Not found: $relativePath")
        return file.readText()
    }

    suspend fun writeFile(relativePath: String, content: String) {
        val file = resolvePath(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    suspend fun deleteFile(relativePath: String): Boolean {
        val file = resolvePath(relativePath)
        return file.delete()
    }

    fun listFiles(pattern: String): List<String> {
        val regex = Regex(pattern)
        return config.sourceDirs.flatMap { srcDir ->
            srcDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(config.rootDir).path }.filter { regex.containsMatchIn(it) }.toList()
        }
    }

    fun isInsideSandbox(path: String): Boolean = try { resolvePath(path); true } catch (e: SecurityException) { false }
}
