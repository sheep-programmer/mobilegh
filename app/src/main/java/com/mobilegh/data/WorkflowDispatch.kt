package com.mobilegh.data

/** workflow_dispatch 的一个输入参数 */
data class WorkflowInput(
    val name: String,
    val type: String = "string",
    val description: String? = null,
    val required: Boolean = false,
    val default: String? = null,
    val options: List<String> = emptyList(),
)

/** enabled = 工作流声明了 workflow_dispatch 触发器 */
data class DispatchSpec(val enabled: Boolean, val inputs: List<WorkflowInput> = emptyList())

/**
 * 只为读取 `on.workflow_dispatch.inputs` 而写的宽松 YAML 子集解析器。
 *
 * 只截取顶层 `on:` 段落解析（jobs 里的脚本再复杂也不影响），支持：块映射、块序列（含 `- key: v`）、
 * 行内 `[...]` / `{...}`、单双引号、`|` / `>` 块文本（含 `-` / `+`）、多行普通文本、行尾注释，
 * 以及 `on:` / `"on":` / `true:`（部分工具按 YAML 1.1 把 on 写成 true）三种键名。
 */
object WorkflowDispatch {
    val TYPES = setOf("string", "boolean", "choice", "number", "environment")

    fun parse(yaml: String): DispatchSpec {
        val on = onValue(yaml) ?: return DispatchSpec(false)
        return when (on) {
            is String -> DispatchSpec(on.split(',').any { it.trim() == "workflow_dispatch" })
            is List<*> -> DispatchSpec(on.any { it == "workflow_dispatch" })
            is Map<*, *> -> {
                if (!on.containsKey("workflow_dispatch")) return DispatchSpec(false)
                val inputs = (on["workflow_dispatch"] as? Map<*, *>)?.get("inputs") as? Map<*, *>
                DispatchSpec(true, inputs?.entries?.mapNotNull { (k, v) -> k?.toString()?.let { input(it, v as? Map<*, *>) } }.orEmpty())
            }
            else -> DispatchSpec(false)
        }
    }

    private fun input(name: String, spec: Map<*, *>?): WorkflowInput {
        val type = (spec?.get("type") as? String)?.trim()?.lowercase()?.takeIf { it in TYPES } ?: "string"
        val options = when (val o = spec?.get("options")) {
            is List<*> -> o.mapNotNull { it?.toString() }
            is String -> listOf(o)
            else -> emptyList()
        }
        return WorkflowInput(
            name = name,
            type = type,
            description = (spec?.get("description") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            required = truthy(spec?.get("required")),
            default = spec?.get("default")?.takeIf { it is String }?.toString(),
            options = options,
        )
    }

    private fun truthy(v: Any?) = (v as? String)?.trim()?.lowercase() in setOf("true", "yes", "on", "y")

    // ---------------- 截取顶层 on 段 ----------------

    private fun isOnKey(key: String) = key.equals("on", ignoreCase = true) || key == "true"

    private fun onValue(yaml: String): Any? {
        val lines = yaml.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "  ").split('\n')
        var start = -1
        for ((i, l) in lines.withIndex()) {
            if (l.isEmpty() || l[0] == ' ' || l[0] == '#' || l.startsWith("---") || l.startsWith("%")) continue
            val key = Yaml.splitKey(l)?.first ?: continue
            if (isOnKey(key)) { start = i; break }
        }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size) {
            val l = lines[end]
            // 段落直到下一个顶层键；"- x" 允许出现在 0 列（与键同级的序列值）
            if (l.isNotEmpty() && l[0] != ' ' && l[0] != '#' && !l.startsWith("- ") && l != "-") break
            end++
        }
        val map = runCatching { Yaml(lines.subList(start, end).toMutableList()).parseBlock(0) }.getOrNull() as? Map<*, *> ?: return null
        return map.entries.firstOrNull { it.key?.toString()?.let(::isOnKey) == true }?.value
    }
}

/** 极简块结构解析：值只有 String / List / LinkedHashMap / null 四种 */
internal class Yaml(private val lines: MutableList<String>) {
    private var i = 0

    private fun indent(s: String) = s.length - s.trimStart(' ').length
    private fun blankOrComment(s: String) = s.isBlank() || s.trimStart().startsWith("#")
    private fun skip() { while (i < lines.size && blankOrComment(lines[i])) i++ }
    private fun isSeqItem(t: String) = t == "-" || t.startsWith("- ")

    fun parseBlock(min: Int): Any? {
        skip()
        if (i >= lines.size) return null
        val ind = indent(lines[i])
        if (ind < min) return null
        return if (isSeqItem(lines[i].substring(ind))) parseSeq(ind) else parseMap(ind)
    }

    private fun parseSeq(ind: Int): List<Any?> {
        val out = ArrayList<Any?>()
        while (true) {
            skip()
            if (i >= lines.size) break
            val l = lines[i]
            if (indent(l) != ind || !isSeqItem(l.substring(ind))) break
            val after = l.substring(ind + 1)
            val rest = after.trimStart()
            val col = ind + 1 + (after.length - rest.length)
            when {
                stripComment(rest).isBlank() -> { i++; out += parseBlock(ind + 1) }
                // "- key: value"：把这一行改写成从 key 开始的映射，再按映射解析
                splitKey(rest) != null && !rest.startsWith("[") && !rest.startsWith("{") -> {
                    lines[i] = " ".repeat(col) + rest
                    out += parseMap(col)
                }
                else -> { i++; out += value(stripComment(rest).trim(), ind, raw = rest) }
            }
        }
        return out
    }

    private fun parseMap(ind: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            skip()
            if (i >= lines.size) break
            val l = lines[i]
            if (indent(l) != ind) break
            val t = l.substring(ind)
            if (isSeqItem(t)) break
            val kv = splitKey(t)
            if (kv == null) { i++; continue } // 容错：无法识别的行直接跳过
            i++
            val (key, rawValue) = kv
            val v = stripComment(rawValue).trim()
            out[key] = if (v.isEmpty()) {
                skip()
                when {
                    i >= lines.size -> null
                    indent(lines[i]) > ind -> parseBlock(ind + 1)
                    indent(lines[i]) == ind && isSeqItem(lines[i].substring(ind)) -> parseSeq(ind)
                    else -> null
                }
            } else value(v, ind, raw = rawValue.trim())
        }
        return out
    }

    /** 键之后（或序列项之后）的单行值，可能延续到更深缩进的后续行 */
    private fun value(v: String, parentIndent: Int, raw: String): Any? = when {
        v.startsWith("|") || v.startsWith(">") -> blockScalar(v, parentIndent)
        v.startsWith("[") || v.startsWith("{") -> {
            val text = StringBuilder(v)
            while (!balanced(text) && i < lines.size && (lines[i].isBlank() || indent(lines[i]) > parentIndent)) {
                text.append(' ').append(stripComment(lines[i]).trim()); i++
            }
            Flow(text.toString()).parse()
        }
        v.startsWith("\"") || v.startsWith("'") -> {
            // 引号字符串可以跨行：拼到出现闭合引号为止
            val text = StringBuilder(if (closes(v)) v else raw)
            while (!closes(text.toString()) && i < lines.size) {
                val next = lines[i]
                text.append(if (next.isBlank()) "\n" else " " + next.trim()); i++
            }
            unquote(stripComment(text.toString()).trim())
        }
        else -> {
            // 多行普通文本：后续更深缩进的行折叠成一个空格
            val text = StringBuilder(v)
            while (i < lines.size && !blankOrComment(lines[i]) && indent(lines[i]) > parentIndent) {
                text.append(' ').append(stripComment(lines[i]).trim()); i++
            }
            plain(text.toString())
        }
    }

    private fun blockScalar(header: String, parentIndent: Int): String {
        val literal = header[0] == '|'
        val chomp = when { '-' in header -> '-'; '+' in header -> '+'; else -> ' ' }
        val explicit = header.drop(1).firstOrNull { it.isDigit() }?.digitToInt()
        val body = ArrayList<String>()
        var contentIndent = explicit?.let { parentIndent + it } ?: -1
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) { body += ""; i++; continue }
            val ind = indent(l)
            if (ind <= parentIndent) break
            if (contentIndent < 0) contentIndent = ind
            if (ind < contentIndent) break
            body += l.substring(contentIndent); i++
        }
        // 结尾的空行不属于正文，按 chomp 规则处理
        var trailing = 0
        while (body.isNotEmpty() && body.last().isEmpty()) { body.removeAt(body.lastIndex); trailing++ }
        val text = if (literal) body.joinToString("\n") else fold(body)
        if (text.isEmpty()) return ""
        return when (chomp) {
            '-' -> text
            '+' -> text + "\n".repeat(trailing + 1)
            else -> text + "\n"
        }
    }

    private fun fold(body: List<String>): String {
        val sb = StringBuilder()
        var prevBlank = true
        for (line in body) {
            when {
                line.isEmpty() -> { sb.append('\n'); prevBlank = true }
                line.startsWith(" ") -> { if (!prevBlank) sb.append('\n'); sb.append(line).append('\n'); prevBlank = true }
                else -> { if (!prevBlank) sb.append(' '); sb.append(line); prevBlank = false }
            }
        }
        return sb.toString().trimEnd('\n')
    }

    companion object {
        /** "key: value" 拆分；键可加引号。值为空时返回空串，不是映射行时返回 null */
        fun splitKey(t: String): Pair<String, String>? {
            val s = t.trimStart()
            if (s.isEmpty() || s.startsWith("#")) return null
            if (s[0] == '"' || s[0] == '\'') {
                val close = closingQuote(s, 0)
                if (close < 0) return null
                val after = s.substring(close + 1).trimStart()
                if (!after.startsWith(":")) return null
                val rest = after.substring(1)
                if (rest.isNotEmpty() && rest[0] != ' ') return null
                return unquote(s.substring(0, close + 1)) to rest
            }
            var j = 0
            while (j < s.length) {
                val c = s[j]
                if (c == '#' && j > 0 && s[j - 1] == ' ') return null
                if (c == ':' && (j + 1 == s.length || s[j + 1] == ' ')) {
                    val key = s.substring(0, j).trim()
                    return if (key.isEmpty()) null else key to s.substring(j + 1)
                }
                j++
            }
            return null
        }

        private fun closingQuote(s: String, open: Int): Int {
            val q = s[open]
            var j = open + 1
            while (j < s.length) {
                val c = s[j]
                if (q == '"' && c == '\\') { j += 2; continue }
                if (c == q) {
                    if (q == '\'' && j + 1 < s.length && s[j + 1] == '\'') { j += 2; continue }
                    return j
                }
                j++
            }
            return -1
        }

        private fun closes(v: String) = v.isNotEmpty() && closingQuote(v, 0) >= 0

        /** 去掉行尾注释：# 前必须是空白，且不在引号内（引号只在值的开头才算） */
        fun stripComment(s: String): String {
            var quote: Char? = null
            var j = 0
            while (j < s.length) {
                val c = s[j]
                if (quote != null) {
                    if (quote == '"' && c == '\\') { j += 2; continue }
                    if (c == quote) quote = null
                } else if ((c == '"' || c == '\'') && tokenStart(s, j)) {
                    quote = c
                } else if (c == '#' && (j == 0 || s[j - 1] == ' ')) {
                    return s.substring(0, j).trimEnd()
                }
                j++
            }
            return s
        }

        private fun tokenStart(s: String, j: Int): Boolean {
            var k = j - 1
            while (k >= 0 && s[k] == ' ') k--
            return k < 0 || s[k] in ":-[{,"
        }

        private fun balanced(sb: CharSequence): Boolean {
            var depth = 0
            var quote: Char? = null
            for (c in sb) {
                if (quote != null) { if (c == quote) quote = null; continue }
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '{' -> depth++
                    ']', '}' -> depth--
                }
            }
            return depth <= 0
        }

        fun unquote(v: String): String {
            if (v.length >= 2 && v[0] == '\'' && v.last() == '\'') return v.substring(1, v.length - 1).replace("''", "'")
            if (v.length >= 2 && v[0] == '"' && v.last() == '"') {
                val s = v.substring(1, v.length - 1)
                val sb = StringBuilder()
                var j = 0
                while (j < s.length) {
                    val c = s[j]
                    if (c == '\\' && j + 1 < s.length) {
                        val n = s[j + 1]
                        when (n) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/'); '0' -> sb.append('\u0000')
                            'u' -> if (j + 6 <= s.length) {
                                s.substring(j + 2, j + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                j += 4
                            }
                            else -> sb.append(n)
                        }
                        j += 2
                        continue
                    }
                    sb.append(c)
                    j++
                }
                return sb.toString()
            }
            return v
        }

        /** 普通文本：null / ~ 视为空值，其余原样（true、数字等保持字符串） */
        fun plain(v: String): String? = v.trim().let { if (it == "~" || it == "null" || it == "Null" || it == "NULL") null else it }
    }
}

/** 行内集合 [a, "b"] / {k: v} 的递归解析 */
private class Flow(private val s: String) {
    private var p = 0

    fun parse(): Any? = value()

    private fun ws() { while (p < s.length && s[p].isWhitespace()) p++ }

    private fun value(): Any? {
        ws()
        if (p >= s.length) return null
        return when (s[p]) {
            '[' -> list()
            '{' -> map()
            else -> scalar(inMap = false)
        }
    }

    private fun list(): List<Any?> {
        p++
        val out = ArrayList<Any?>()
        while (true) {
            ws()
            if (p >= s.length) break
            if (s[p] == ']') { p++; break }
            if (s[p] == ',') { p++; continue }
            out += value()
        }
        return out
    }

    private fun map(): Map<String, Any?> {
        p++
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            ws()
            if (p >= s.length) break
            if (s[p] == '}') { p++; break }
            if (s[p] == ',') { p++; continue }
            val key = scalar(inMap = true)?.toString() ?: ""
            ws()
            if (p < s.length && s[p] == ':') {
                p++
                ws()
                out[key] = if (p < s.length && (s[p] == ',' || s[p] == '}')) null else value()
            } else out[key] = null
        }
        return out
    }

    private fun scalar(inMap: Boolean): String? {
        ws()
        if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
            val q = s[p]
            val start = p
            p++
            while (p < s.length) {
                if (q == '"' && s[p] == '\\') { p += 2; continue }
                if (s[p] == q) {
                    if (q == '\'' && p + 1 < s.length && s[p + 1] == '\'') { p += 2; continue }
                    p++; break
                }
                p++
            }
            return Yaml.unquote(s.substring(start, p.coerceAtMost(s.length)))
        }
        val start = p
        while (p < s.length) {
            val c = s[p]
            if (c == ',' || c == ']' || c == '}') break
            if (inMap && c == ':' && (p + 1 >= s.length || s[p + 1] == ' ' || s[p + 1] == ',' || s[p + 1] == '}')) break
            p++
        }
        return Yaml.plain(s.substring(start, p))
    }
}
