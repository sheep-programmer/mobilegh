package com.mobilegh.ui.screens

internal data class CodeSymbol(
    val name: String,
    val kind: String,
    val line: Int,
    val depth: Int = 0,
)

/**
 * Lightweight, offline symbol extractor for code files so large source files
 * can jump straight to classes, functions, structs, or headings.
 */
internal object CodeOutline {
    private val kotlinJavaPattern = Regex("""^(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|value|inline|infix|operator|suspend|tailrec|override|external|static|synchronized|native|default)\s+)*(class|interface|object|enum\s+class|enum|fun)\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_.]*)""")
    private val jsTsPattern = Regex("""^(?:export\s+(?:default\s+)?)?(?:async\s+)?(?:(class|interface|type|enum|function)\s+([A-Za-z_$][A-Za-z0-9_$]*)|const\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>)""")
    private val pythonPattern = Regex("""^(?:async\s+)?(class|def)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val goPattern = Regex("""^(?:(func)\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)|(type)\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?:struct|interface))""")
    private val rustPattern = Regex("""^(?:pub(?:\([^)]+\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(struct|enum|trait|impl(?:<[^>]+>)?|fn|mod)\s+([A-Za-z_][A-Za-z0-9_:]*)""")
    private val swiftCsPattern = Regex("""^(?:(?:public|private|internal|fileprivate|open|protected|static|final|abstract|sealed|partial|async|override|mutating)\s+)*(class|struct|enum|protocol|extension|namespace|func)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val markdownHeading = Regex("""^(#{1,4})\s+(.+?)\s*#*\s*$""")

    fun extract(path: String, text: String): List<CodeSymbol> {
        if (text.isBlank()) return emptyList()
        val ext = path.substringAfterLast('.', "").lowercase()
        val lines = text.lineSequence().take(20_000).toList()
        val out = ArrayList<CodeSymbol>()
        for ((index, raw) in lines.withIndex()) {
            val lineNo = index + 1
            val indent = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: continue
            val trimmed = raw.substring(indent)
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
            val depth = (indent / 4).coerceIn(0, 3)
            val symbol = when (ext) {
                "kt", "kts", "java", "scala", "groovy" -> kotlinJavaPattern.find(trimmed)?.let {
                    val kind = it.groupValues[1].substringBefore(' ')
                    CodeSymbol(it.groupValues[2], kind, lineNo, depth)
                }
                "js", "jsx", "ts", "tsx", "mjs", "cjs" -> jsTsPattern.find(trimmed)?.let {
                    if (it.groupValues[1].isNotEmpty()) {
                        CodeSymbol(it.groupValues[2], it.groupValues[1], lineNo, depth)
                    } else {
                        CodeSymbol(it.groupValues[3], "fn", lineNo, depth)
                    }
                }
                "py" -> pythonPattern.find(trimmed)?.let {
                    CodeSymbol(it.groupValues[2], it.groupValues[1], lineNo, depth)
                }
                "go" -> goPattern.find(trimmed)?.let {
                    if (it.groupValues[1].isNotEmpty()) {
                        CodeSymbol(it.groupValues[2], "func", lineNo, 0)
                    } else {
                        CodeSymbol(it.groupValues[4], "type", lineNo, 0)
                    }
                }
                "rs" -> rustPattern.find(trimmed)?.let {
                    val kind = it.groupValues[1].substringBefore('<')
                    CodeSymbol(it.groupValues[2], kind, lineNo, depth)
                }
                "swift", "cs", "cpp", "cc", "hpp", "h", "c" -> swiftCsPattern.find(trimmed)?.let {
                    CodeSymbol(it.groupValues[2], it.groupValues[1], lineNo, depth)
                }
                "md", "markdown", "mdx" -> markdownHeading.find(trimmed)?.let {
                    CodeSymbol(it.groupValues[2], "h${it.groupValues[1].length}", lineNo, (it.groupValues[1].length - 1).coerceIn(0, 3))
                }
                else -> (kotlinJavaPattern.find(trimmed) ?: pythonPattern.find(trimmed))?.let {
                    CodeSymbol(it.groupValues[2], it.groupValues[1].substringBefore(' '), lineNo, depth)
                }
            }
            if (symbol != null) out.add(symbol)
            if (out.size >= 500) break
        }
        return out
    }
}
