package com.apex.agent.presentation.enhancedterminal.data

import java.io.File

class TabCompleter(
    private val aliases: () -> Map<String, CommandAlias>,
    private val quickCommands: () -> List<QuickCommand>,
    private val history: () -> List<Pair<String, Long>>,
) {
    fun complete(input: String, workingDir: String): List<String> {
        if (input.isBlank()) return emptyList()
        val parts = input.split(" ", limit = 2)
        return when {
            parts.size == 1 -> completeCommand(parts[0])
            parts[1].contains("/") || parts[1].startsWith(".") -> completePath(parts[1], workingDir).map { "${parts[0]} $it" }
            else -> completeHistory(input)
        }
    }
        private fun completeCommand(prefix: String): List<String> {
        val results = mutableSetOf<String>()
        BuiltInCommands.all.forEach { if (it.action.startsWith(prefix, true)) results.add(it.action) }
        aliases().keys.forEach { if (it.startsWith(prefix, true)) results.add(it) }
        quickCommands().forEach { if (qc.command.startsWith(prefix, true)) results.add(qc.command) }
        history().reversed().forEach { (cmd, _) -> cmd.split(" ").firstOrNull()?.let { if (it.startsWith(prefix, true)) results.add(it) } }
        listOf("ls","cd","pwd","cat","grep","find","cp","mv","rm","mkdir","touch","chmod","echo","head","tail","wc","sort","ps","top","kill","git","node","npm","python","java","curl","wget","ssh","tar","zip","vi","vim","man","df","du","free","uptime","env","export","ifconfig","ip","netstat","ping","diff","sed","awk").forEach { if (it.startsWith(prefix, true)) results.add(it) }
        return results.sorted().take(10)
    }
        private fun completePath(input: String, workingDir: String): List<String> = try {
        val expanded = input.replace("~", System.getProperty("user.home") ?: "/")
        val base = if (File(expanded).isAbsolute) expanded else File(workingDir, expanded).path
        val file = File(base)
        val parent = if (input.contains("/")) file.parentFile ?: File("/") else File(workingDir)
        val prefix = if (input.contains("/")) input.substringAfterLast('/') else input
        if (!parent.exists()) emptyList() else parent.listFiles()?.filter { it.name.startsWith(prefix) }?.sortedBy { it.name }?.take(10)?.map { f ->
            val basePath = if (input.contains("/")) input.substringBeforeLast('/') + "/" else ""
            basePath + f.name + if (f.isDirectory) "/" else ""
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }
        private fun completeHistory(prefix: String): List<String> =
        history().reversed().filter { (_, cmd) -> cmd.startsWith(prefix, true) && cmd != prefix }.map { it.first }.distinct().take(5)
}
