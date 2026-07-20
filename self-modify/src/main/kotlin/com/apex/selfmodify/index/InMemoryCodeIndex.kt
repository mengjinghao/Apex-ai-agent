package com.apex.selfmodify.index

class InMemoryCodeIndex(private val indexer: CodeIndexer) : CodeIndex {
    override suspend fun findSymbol(name: String): List<SymbolLocation> {
        return indexer.snapshot().filter { it.name == name }.map { SymbolLocation(it.file, it.line, it.column) }
    }

    override suspend fun findReferences(symbol: String): List<ReferenceLocation> {
        val refs = mutableListOf<ReferenceLocation>()
        indexer.snapshot().forEach { sym ->
            val file = java.io.File(sym.file)
            if (file.exists()) {
                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains(symbol) && !(sym.name == symbol && idx + 1 == sym.line)) {
                        refs.add(ReferenceLocation(sym.file, idx + 1, symbol))
                    }
                }
            }
        }
        return refs
    }

    override suspend fun listFiles(pattern: String): List<String> {
        val regex = Regex(pattern)
        return indexer.snapshot().map { it.file }.distinct().filter { regex.containsMatchIn(it) }
    }

    override fun isIndexed(): Boolean = indexer.isIndexed()
}
