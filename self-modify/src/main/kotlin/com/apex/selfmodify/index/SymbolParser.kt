package com.apex.selfmodify.index

import java.io.File

enum class SymbolKind { CLASS, OBJECT, INTERFACE, FUNCTION, PROPERTY, ENUM, ENUM_CONSTANT }

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val column: Int,
    val signature: String? = null,
    val documentation: String? = null
)

interface SymbolParser {
    fun parse(file: File): List<Symbol>
    val supportedExtensions: Set<String>
}
