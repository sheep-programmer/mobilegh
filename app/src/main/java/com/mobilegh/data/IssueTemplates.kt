package com.mobilegh.data

import com.mobilegh.i18n.tr

/** 仓库 .github/ISSUE_TEMPLATE/ 中的一个模板（Markdown 模板或 YAML Issue 表单） */
data class IssueTemplate(
    val file: String,
    val name: String,
    val about: String = "",
    val title: String = "",
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val body: String = "",
    val form: Boolean = false,
)

data class ContactLink(val name: String, val url: String, val about: String = "")

data class IssueTemplateConfig(val blankIssuesEnabled: Boolean = true, val contactLinks: List<ContactLink> = emptyList())

data class IssueTemplateSet(val templates: List<IssueTemplate> = emptyList(), val config: IssueTemplateConfig = IssueTemplateConfig()) {
    /** 有模板或联系链接时，先显示模板选择页 */
    val hasChooser: Boolean get() = templates.isNotEmpty() || config.contactLinks.isNotEmpty() || !config.blankIssuesEnabled
}

/** 解析 Issue 模板；只处理模板常用的 YAML 子集，无法识别的内容会被忽略 */
object IssueTemplates {
    const val DIR = ".github/ISSUE_TEMPLATE"
    const val MAX_FILES = 20

    fun isConfig(file: String) = file.substringAfterLast('/').lowercase().let { it == "config.yml" || it == "config.yaml" }
    fun isForm(file: String) = file.lowercase().let { it.endsWith(".yml") || it.endsWith(".yaml") } && !isConfig(file)
    fun isMarkdown(file: String) = file.lowercase().endsWith(".md")

    /** files：文件名与原文；按文件名排序（与 GitHub 网页一致），解析失败的文件跳过 */
    fun build(files: List<Pair<String, String>>): IssueTemplateSet {
        val config = files.firstOrNull { isConfig(it.first) }?.let { runCatching { parseConfig(it.second) }.getOrNull() } ?: IssueTemplateConfig()
        val templates = files.filterNot { isConfig(it.first) }.sortedBy { it.first.lowercase() }
            .mapNotNull { (f, t) -> runCatching { parse(f, t) }.getOrNull() }
        return IssueTemplateSet(templates, config)
    }

    fun parse(file: String, text: String): IssueTemplate? = when {
        isForm(file) -> parseForm(file, text)
        isMarkdown(file) -> parseMarkdown(file, text)
        else -> null
    }

    /** Markdown 模板：开头的 --- YAML front matter --- 之后是正文 */
    fun parseMarkdown(file: String, text: String): IssueTemplate {
        val src = text.replace("\r\n", "\n").removePrefix("\uFEFF")
        val lines = src.lines()
        var meta: Map<*, *> = emptyMap<String, Any>()
        var body = src
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                meta = MiniYaml.parse(lines.subList(1, end + 1).joinToString("\n")) as? Map<*, *> ?: emptyMap<String, Any>()
                body = lines.drop(end + 2).joinToString("\n").trimStart('\n')
            }
        }
        return IssueTemplate(
            file = file,
            name = str(meta["name"]).ifBlank { baseName(file) },
            about = str(meta["about"]),
            title = str(meta["title"]),
            labels = strList(meta["labels"]),
            assignees = strList(meta["assignees"]).map { it.removePrefix("@") },
            body = body,
        )
    }

    /** YAML Issue 表单：不实现表单引擎，只把 body 中的字段转成 Markdown 骨架 */
    fun parseForm(file: String, text: String): IssueTemplate? {
        val root = MiniYaml.parse(text.replace("\r\n", "\n").removePrefix("\uFEFF")) as? Map<*, *> ?: return null
        val items = root["body"] as? List<*> ?: emptyList<Any>()
        val body = buildString {
            items.forEach { raw ->
                val item = raw as? Map<*, *> ?: return@forEach
                val type = str(item["type"])
                val attrs = item["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()
                if (type == "markdown") return@forEach
                val label = str(attrs["label"]).ifBlank { return@forEach }
                append("### ").append(label).append("\n\n")
                str(attrs["description"]).takeIf { it.isNotBlank() }?.let { append("<!-- ").append(it.replace("-->", "").trim()).append(" -->\n") }
                when (type) {
                    "checkboxes" -> (attrs["options"] as? List<*>).orEmpty().forEach { o ->
                        val l = (o as? Map<*, *>)?.let { str(it["label"]) } ?: str(o)
                        if (l.isNotBlank()) append("- [ ] ").append(l).append('\n')
                    }
                    "dropdown" -> strList(attrs["options"]).takeIf { it.isNotEmpty() }?.let {
                        append(tr("<!-- 可选：")).append(it.joinToString(" / ")).append(" -->\n")
                    }
                    else -> str(attrs["value"]).takeIf { it.isNotBlank() }?.let { v ->
                        if (str(attrs["render"]).isNotBlank()) append("```").append(str(attrs["render"])).append('\n').append(v.trimEnd()).append("\n```\n")
                        else append(v.trimEnd()).append('\n')
                    }
                }
                append('\n')
            }
        }.trimEnd() + "\n"
        return IssueTemplate(
            file = file,
            name = str(root["name"]).ifBlank { baseName(file) },
            about = str(root["description"]),
            title = str(root["title"]),
            labels = strList(root["labels"]),
            assignees = strList(root["assignees"]).map { it.removePrefix("@") },
            body = body.takeIf { it.isNotBlank() } ?: "",
            form = true,
        )
    }

    fun parseConfig(text: String): IssueTemplateConfig {
        val root = MiniYaml.parse(text.replace("\r\n", "\n")) as? Map<*, *> ?: return IssueTemplateConfig()
        val blank = str(root["blank_issues_enabled"]).lowercase() != "false"
        val links = (root["contact_links"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val url = str(m["url"]).trim()
            val name = str(m["name"]).trim()
            if (name.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) null
            else ContactLink(name, url, str(m["about"]).trim())
        }
        return IssueTemplateConfig(blank, links)
    }

    private fun baseName(file: String) = file.substringAfterLast('/').substringBeforeLast('.')

    private fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is List<*> -> v.joinToString(", ") { str(it) }
        else -> v.toString()
    }

    /** labels 可写成列表、[a, b] 或 "a, b" */
    fun strList(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is List<*> -> v.map { str(it).trim() }
        else -> str(v).split(',').map { it.trim() }
    }.filter { it.isNotEmpty() }
}

/**
 * 极简 YAML 读取器：支持映射、序列（含序列中的映射）、| 与 > 块文本、单双引号、[a, b] 行内序列与注释。
 * 结果为 Map<String, Any?> / List<Any?> / String / null。
 */
object MiniYaml {
    private class Line(val indent: Int, val text: String, val raw: String)

    fun parse(text: String): Any? {
        val raw = text.replace("\t", "  ").lines()
        val lines = raw.filterIndexed { i, l -> !(i == 0 && l.trim() == "---") && l.trim() != "..." }.toMutableList()
        val p = Parser(lines)
        return p.block(-1)
    }

    private class Parser(val raw: MutableList<String>) {
        var i = 0

        fun indentOf(s: String) = s.length - s.trimStart(' ').length

        /** 下一行有内容的行（跳过空行和整行注释） */
        fun peek(): Line? {
            while (i < raw.size) {
                val s = raw[i]
                val t = stripComment(s).trimEnd()
                if (t.isBlank()) { i++; continue }
                return Line(indentOf(s), t.trim(), s)
            }
            return null
        }

        fun block(parentIndent: Int): Any? {
            val first = peek() ?: return null
            if (first.indent <= parentIndent) return null
            return when {
                isSeqItem(first.text) -> seq(first.indent)
                keyOf(first.text) != null -> map(first.indent)
                else -> plain(parentIndent)
            }
        }

        fun plain(parentIndent: Int): String {
            val parts = ArrayList<String>()
            while (true) {
                val l = peek() ?: break
                if (l.indent <= parentIndent) break
                parts += l.text
                i++
            }
            return parts.joinToString(" ")
        }

        fun map(indent: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                val l = peek() ?: break
                if (l.indent != indent || isSeqItem(l.text)) break
                val (key, rest) = keyOf(l.text) ?: break
                i++
                out[key] = value(rest, indent)
            }
            return out
        }

        fun seq(indent: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (true) {
                val l = peek() ?: break
                if (l.indent != indent || !isSeqItem(l.text)) break
                val content = l.text.removePrefix("-").trimStart()
                if (content.isEmpty()) {
                    i++
                    out += block(indent)
                } else if (keyOf(content) != null && !content.startsWith("\"") && !content.startsWith("'")) {
                    // "- key: value"：把这一行改写成同列的映射首行，继续按映射解析
                    val col = l.raw.indexOf(content.first(), l.raw.indexOf('-') + 1)
                    raw[i] = " ".repeat(col) + l.raw.substring(col)
                    out += map(col)
                } else {
                    i++
                    out += value(content, indent)
                }
            }
            return out
        }

        /** 解析 key: 之后的部分；为空时取下面缩进更深的块 */
        fun value(rest: String, indent: Int): Any? {
            val r = rest.trim()
            if (r.isEmpty()) {
                val next = peek() ?: return null
                // 允许 key:\n- a 这种与 key 同列的序列
                if (next.indent == indent && isSeqItem(next.text)) return seq(indent)
                return block(indent)
            }
            if (r.startsWith("|") || r.startsWith(">")) return blockScalar(r, indent)
            // 跨行的普通或引号文本
            val sb = StringBuilder(r)
            val quote = r.first().takeIf { it == '"' || it == '\'' }
            while (true) {
                val unclosed = quote != null && !closed(sb.toString(), quote)
                val next = peek() ?: break
                if (next.indent <= indent && !unclosed) break
                if (!unclosed && quote == null && (isSeqItem(next.text) || keyOf(next.text) != null)) break
                if (!unclosed && quote != null) break
                sb.append(' ').append(next.text)
                i++
            }
            return scalar(sb.toString())
        }

        fun blockScalar(header: String, indent: Int): String {
            val folded = header.startsWith(">")
            val chomp = when {
                header.contains('-') -> '-'
                header.contains('+') -> '+'
                else -> ' '
            }
            val body = ArrayList<String>()
            var blockIndent = -1
            while (i < raw.size) {
                val s = raw[i]
                if (s.isBlank()) { body += ""; i++; continue }
                val ind = indentOf(s)
                if (ind <= indent) break
                if (blockIndent < 0) blockIndent = ind
                if (ind < blockIndent) break
                body += s.substring(blockIndent)
                i++
            }
            val text = if (!folded) body.joinToString("\n") else buildString {
                body.forEachIndexed { k, line ->
                    if (k > 0) append(if (line.isEmpty() || body[k - 1].isEmpty() || line.startsWith(" ")) "\n" else " ")
                    append(line)
                }
            }
            return when (chomp) {
                '-' -> text.trimEnd('\n')
                '+' -> text + "\n"
                else -> text.trimEnd('\n') + "\n"
            }
        }
    }

    private fun closed(s: String, q: Char): Boolean {
        var k = 1
        while (k < s.length) {
            val c = s[k]
            if (q == '"' && c == '\\') { k += 2; continue }
            if (c == q) {
                if (q == '\'' && k + 1 < s.length && s[k + 1] == '\'') { k += 2; continue }
                return true
            }
            k++
        }
        return false
    }

    private fun isSeqItem(t: String) = t == "-" || t.startsWith("- ")

    /** 拆出 "key: rest"；冒号必须在引号外且后跟空格或行尾 */
    private fun keyOf(t: String): Pair<String, String>? {
        var q: Char? = null
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (q != null) {
                if (c == q) q = null
            } else if ((c == '"' || c == '\'') && k == 0) {
                q = c
            } else if (c == ':' && (k + 1 == t.length || t[k + 1] == ' ')) {
                val key = unquote(t.substring(0, k).trim())
                if (key.isEmpty() || t.startsWith("[") || t.startsWith("{")) return null
                return key to t.substring(k + 1)
            }
            k++
        }
        return null
    }

    /** 去掉引号外 " #" 之后的注释 */
    private fun stripComment(s: String): String {
        var q: Char? = null
        for (k in s.indices) {
            val c = s[k]
            if (q != null) {
                if (c == q) q = null
            } else if (c == '"' || c == '\'') {
                if (k == 0 || s[k - 1] == ' ' || s[k - 1] == '[' || s[k - 1] == ',' || s[k - 1] == ':') q = c
            } else if (c == '#' && (k == 0 || s[k - 1] == ' ')) {
                return s.substring(0, k)
            }
        }
        return s
    }

    private fun scalar(s: String): Any? {
        val t = s.trim()
        if (t.isEmpty() || t == "~" || t == "null") return null
        if (t.startsWith("[") && t.endsWith("]")) return splitFlow(t.substring(1, t.length - 1)).map { scalar(it) }
        if (t == "{}") return emptyMap<String, Any?>()
        return unquote(t)
    }

    private fun splitFlow(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q: Char? = null
        for (c in s) {
            when {
                q != null -> { if (c == q) q = null; cur.append(c) }
                c == '"' || c == '\'' -> { q = c; cur.append(c) }
                c == ',' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unquote(t: String): String = when {
        t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") ->
            t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        t.length >= 2 && t.startsWith("'") && t.endsWith("'") -> t.substring(1, t.length - 1).replace("''", "'")
        else -> t
    }
}
