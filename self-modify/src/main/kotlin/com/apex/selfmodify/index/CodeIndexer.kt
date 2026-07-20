package com.apex.selfmodify.index

import com.apex.selfmodify.workspace.WorkspaceManager
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CodeIndexer(
    private val workspace: WorkspaceManager,
    private val parsers: List<SymbolParser> = listOf(KotlinSymbolParser(), CppSymbolParser())
) {
    private val symbols = mutableListOf<Symbol>()
    @Volatile private var indexed = false

    suspend fun reindexAll(): Int = withContext(Dispatchers.IO) {
        symbols.clear()
        var count = 0
        workspace.config.sourceDirs.forEach { srcDir ->
            srcDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val ext = file.extension
                val parser = parsers.find { ext in it.supportedExtensions }
                if (parser != null) {
                    val syms = parser.parse(file)
                    symbols.addAll(syms)
                    count += syms.size
                }
            }
        }
        indexed = true
        ApexLog.i(ApexSuite.ApkId.MAIN, "[CodeIndexer] indexed $count symbols across ${workspace.config.sourceDirs.size} source dirs")
        count
    }

    suspend fun reindexFile(file: File) = withContext(Dispatchers.IO) {
        symbols.removeAll { it.file == file.path }
        val ext = file.extension
        val parser = parsers.find { ext in it.supportedExtensions }
        if (parser != null) symbols.addAll(parser.parse(file))
    }

    fun snapshot(): List<Symbol> = symbols.toList()
    fun isIndexed(): Boolean = indexed
}
