package com.apex.selfmodify.index

import java.io.File

class CppSymbolParser : SymbolParser {
    override val supportedExtensions = setOf("cpp", "cc", "cxx", "h", "hpp", "hxx")

    private val classPattern = Regex("""^\s*(?:class|struct)\s+(\w+)""")
    private val funPattern = Regex("""^\s*(?:[\w:*&<>]+\s+)+(\w+)\s*\(""")

    override fun parse(file: File): List<Symbol> {
        val symbols = mutableListOf<Symbol>()
        file.readLines().forEachIndexed { idx, line ->
            val lineNo = idx + 1
            classPattern.find(line)?.let { m ->
                symbols.add(Symbol(m.groupValues[1], SymbolKind.CLASS, file.path, lineNo, m.range.first + 1))
            }
            funPattern.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name !in setOf("if", "for", "while", "switch", "return", "sizeof")) {
                    symbols.add(Symbol(name, SymbolKind.FUNCTION, file.path, lineNo, m.range.first + 1))
                }
            }
        }
        return symbols
    }
}
