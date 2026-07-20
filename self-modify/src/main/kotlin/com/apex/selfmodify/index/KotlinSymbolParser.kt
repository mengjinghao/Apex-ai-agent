package com.apex.selfmodify.index

import java.io.File

class KotlinSymbolParser : SymbolParser {
    override val supportedExtensions = setOf("kt", "kts")

    private val classPattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+|open\s+|abstract\s+|sealed\s+|data\s+|final\s+)*class\s+(\w+)""")
    private val objectPattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+)*object\s+(\w+)""")
    private val interfacePattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+)*interface\s+(\w+)""")
    private val funPattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+|suspend\s+|open\s+|abstract\s+|final\s+|override\s+)*fun\s+(\w+)\s*[<(]""")
    private val valPattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+|override\s+|const\s+)*val\s+(\w+)""")
    private val enumPattern = Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+)*enum\s+class\s+(\w+)""")

    override fun parse(file: File): List<Symbol> {
        val symbols = mutableListOf<Symbol>()
        file.readLines().forEachIndexed { idx, line ->
            val lineNo = idx + 1
            fun match(pattern: Regex, kind: SymbolKind) {
                pattern.find(line)?.let { m ->
                    symbols.add(Symbol(m.groupValues[1], kind, file.path, lineNo, m.range.first + 1))
                }
            }
            match(enumPattern, SymbolKind.ENUM)
            match(classPattern, SymbolKind.CLASS)
            match(objectPattern, SymbolKind.OBJECT)
            match(interfacePattern, SymbolKind.INTERFACE)
            match(funPattern, SymbolKind.FUNCTION)
            match(valPattern, SymbolKind.PROPERTY)
        }
        return symbols
    }
}
