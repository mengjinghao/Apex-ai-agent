package com.apex.selfmodify.index

data class SymbolLocation(val file: String, val line: Int, val column: Int)
data class ReferenceLocation(val file: String, val line: Int, val symbol: String)

interface CodeIndex {
    suspend fun findSymbol(name: String): List<SymbolLocation>
    suspend fun findReferences(symbol: String): List<ReferenceLocation>
    suspend fun listFiles(pattern: String): List<String>
    fun isIndexed(): Boolean
}
