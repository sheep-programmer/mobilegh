package com.mobilegh.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import com.mobilegh.data.Session
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.GhColors
import com.mobilegh.i18n.tr

// ============ 轻量语法高亮 ============

@Immutable
data class SyntaxColors(val kw: Color, val str: Color, val com: Color, val num: Color, val fn: Color, val type: Color, val tag: Color, val attr: Color)

fun syntaxColors(g: GhColors) = if (g.dark) {
    SyntaxColors(Color(0xFFFF7B72), Color(0xFFA5D6FF), Color(0xFF9198A1), Color(0xFF79C0FF), Color(0xFFD2A8FF), Color(0xFFFFA657), Color(0xFF7EE787), Color(0xFF79C0FF))
} else {
    SyntaxColors(Color(0xFFCF222E), Color(0xFF0A3069), Color(0xFF59636E), Color(0xFF0550AE), Color(0xFF6639BA), Color(0xFF953800), Color(0xFF116329), Color(0xFF0550AE))
}

private enum class Fam { C, Py, Sh, Markup, Sql, Lua, Plain, Json, Yaml }

private data class Lang(val fam: Fam, val keywords: Set<String>)

private val C_KW = "abstract as async await break case catch class const continue default defer delete do else enum export extends extern final finally for fun func function go goto if impl implements import in instanceof interface internal is let match mod module mut namespace new object open operator override package private protected pub public readonly return sealed select static struct super suspend switch this throw throws trait try type typealias typeof union unsafe use using val var virtual void volatile when where while with yield lateinit inline data companion constructor init get set crate self Self dyn ref move loop fn chan range map defer fallthrough var elif then extension protocol guard inout lazy weak mutating required convenience fileprivate throws rethrows associatedtype some any".split(" ").toSet()
private val C_LIT = setOf("true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN", "Infinity", "nullptr", "it")
private val PY_KW = "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case self print".split(" ").toSet()
private val SH_KW = "if then else elif fi case esac for while until do done in function return exit export local readonly echo cd set unset source alias sudo".split(" ").toSet()
private val SQL_KW = "select from where and or not insert into values update set delete create table drop alter index primary key foreign references join left right inner outer on group by order having limit offset as distinct union all case when then else end null is in like between exists default unique view trigger begin commit rollback".split(" ").toSet()
private val LUA_KW = "and break do else elseif end false for function goto if in local nil not or repeat return then true until while".split(" ").toSet()
private val RB_KW = "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_accessor puts".split(" ").toSet()
private val YAML_KW = setOf("true", "false", "null", "yes", "no", "on", "off")

private fun langOf(file: String): Lang {
    val name = file.substringAfterLast('/').lowercase()
    val ext = name.substringAfterLast('.', "")
    return when {
        name == "dockerfile" || ext == "dockerfile" -> Lang(Fam.Sh, SH_KW + setOf("from", "run", "cmd", "copy", "add", "env", "workdir", "expose", "entrypoint", "arg", "label", "volume", "user"))
        name == "makefile" || ext == "mk" -> Lang(Fam.Sh, SH_KW)
        ext in setOf("kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cxx", "cs", "swift", "m", "mm", "dart", "scala", "groovy", "gradle", "php", "zig", "v", "sol", "vue", "svelte", "proto", "glsl", "cu") -> Lang(Fam.C, C_KW)
        ext in setOf("py", "pyi", "pyw") -> Lang(Fam.Py, PY_KW)
        ext in setOf("rb", "rake", "gemspec") -> Lang(Fam.Py, RB_KW)
        ext in setOf("sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "env", "conf", "ini", "cfg", "properties", "toml", "r", "pl", "nix", "tf", "hcl") -> Lang(Fam.Sh, SH_KW)
        ext in setOf("yml", "yaml") -> Lang(Fam.Yaml, YAML_KW)
        ext in setOf("json", "jsonc", "json5", "ipynb", "lock") -> Lang(Fam.Json, emptySet())
        ext in setOf("xml", "html", "htm", "xhtml", "svg", "plist", "vue", "xaml", "csproj", "storyboard") -> Lang(Fam.Markup, emptySet())
        ext in setOf("sql") -> Lang(Fam.Sql, SQL_KW)
        ext in setOf("lua") -> Lang(Fam.Lua, LUA_KW)
        ext in setOf("css", "scss", "less", "sass") -> Lang(Fam.C, setOf("important", "media", "import", "keyframes", "from", "to", "include", "mixin", "extend", "if", "else", "each"))
        else -> Lang(Fam.Plain, emptySet())
    }
}

fun canHighlight(file: String) = langOf(file).fam != Fam.Plain

/** 高亮整段文本并按行切分（块注释、多行字符串跨行正确着色） */
fun highlightLines(text: String, file: String, sc: SyntaxColors): List<AnnotatedString> {
    val lang = langOf(file)
    val src = (if (text.length > 400_000) text.substring(0, 400_000) else text).replace("\t", "    ")
    if (lang.fam == Fam.Plain) return src.split('\n').map { AnnotatedString(it.trimEnd('\r')) }
    val spans = ArrayList<Triple<Int, Int, SpanStyle>>()
    val com = SpanStyle(color = sc.com, fontStyle = FontStyle.Italic)
    val str = SpanStyle(color = sc.str)
    val kw = SpanStyle(color = sc.kw)
    val num = SpanStyle(color = sc.num)
    val fn = SpanStyle(color = sc.fn)
    val type = SpanStyle(color = sc.type)
    val tag = SpanStyle(color = sc.tag)
    val attr = SpanStyle(color = sc.attr)
    val n = src.length
    var i = 0
    val hashComment = lang.fam in setOf(Fam.Py, Fam.Sh, Fam.Yaml)
    val slashComment = lang.fam == Fam.C || lang.fam == Fam.Json
    val dashComment = lang.fam == Fam.Sql || lang.fam == Fam.Lua

    fun add(a: Int, b: Int, s: SpanStyle) { if (b > a) spans.add(Triple(a, b, s)) }

    if (lang.fam == Fam.Markup) {
        while (i < n) {
            when {
                src.startsWith("<!--", i) -> { val e = src.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }; add(i, e, com); i = e }
                src[i] == '<' -> {
                    val e = src.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    var j = i + 1
                    while (j < e && (src[j] == '/' || src[j] == '?' || src[j] == '!')) j++
                    val ns = j
                    while (j < e && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                    add(ns, j, tag)
                    while (j < e) {
                        val c = src[j]
                        if (c == '"' || c == '\'') {
                            val q = src.indexOf(c, j + 1).let { if (it < 0 || it > e) e else it + 1 }
                            add(j, q, str); j = q
                        } else if (c.isLetter()) {
                            val s0 = j
                            while (j < e && (src[j].isLetterOrDigit() || src[j] in ":-_.")) j++
                            add(s0, j, attr)
                        } else j++
                    }
                    i = e
                }
                else -> i++
            }
        }
    } else {
        while (i < n) {
            val c = src[i]
            when {
                slashComment && src.startsWith("//", i) -> { val e = src.indexOf('\n', i).let { if (it < 0) n else it }; add(i, e, com); i = e }
                slashComment && src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }; add(i, e, com); i = e }
                hashComment && c == '#' && (i == 0 || src[i - 1].isWhitespace() || lang.fam != Fam.Sh) -> {
                    val e = src.indexOf('\n', i).let { if (it < 0) n else it }; add(i, e, com); i = e
                }
                dashComment && src.startsWith("--", i) -> {
                    val e = if (lang.fam == Fam.Lua && src.startsWith("--[[", i)) src.indexOf("]]", i).let { if (it < 0) n else it + 2 }
                    else src.indexOf('\n', i).let { if (it < 0) n else it }
                    add(i, e, com); i = e
                }
                lang.fam == Fam.Py && (src.startsWith("\"\"\"", i) || src.startsWith("'''", i)) -> {
                    val q = src.substring(i, i + 3)
                    val e = src.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }; add(i, e, str); i = e
                }
                c == '"' || c == '\'' || (c == '`' && lang.fam == Fam.C) -> {
                    var j = i + 1
                    while (j < n && src[j] != c) {
                        if (src[j] == '\\') j++
                        else if (src[j] == '\n' && c != '`') break
                        j++
                    }
                    val e = (j + 1).coerceAtMost(n)
                    // JSON 的 key 用属性色
                    val isKey = lang.fam == Fam.Json && src.indexOfFirstNonSpace(e) == ':'
                    add(i, e, if (isKey) attr else str); i = e
                }
                c.isDigit() && (i == 0 || !src[i - 1].isLetterOrDigit() && src[i - 1] != '_') -> {
                    var j = i
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '.' || src[j] == '_')) j++
                    add(i, j, num); i = j
                }
                c.isLetter() || c == '_' || c == '@' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    val w = src.substring(i, j)
                    when {
                        lang.fam == Fam.Yaml && src.indexOfFirstNonSpace(j) == ':' && lineStartSpaces(src, i) -> add(i, j, tag)
                        w in lang.keywords || (lang.fam == Fam.Sql && w.lowercase() in lang.keywords) -> add(i, j, kw)
                        w in C_LIT -> add(i, j, num)
                        c == '@' -> add(i, j, type)
                        lang.fam == Fam.C && w[0].isUpperCase() && w.length > 1 -> add(i, j, type)
                        j < n && src[j] == '(' -> add(i, j, fn)
                    }
                    i = j
                }
                else -> i++
            }
        }
    }
    // 切行
    val lines = ArrayList<AnnotatedString>()
    var lineStart = 0
    var si = 0
    spans.sortBy { it.first }
    while (lineStart <= n) {
        val lineEnd = src.indexOf('\n', lineStart).let { if (it < 0) n else it }
        val line = src.substring(lineStart, lineEnd).trimEnd('\r')
        while (si < spans.size && spans[si].second <= lineStart) si++
        val b = AnnotatedString.Builder(line)
        var k = si
        while (k < spans.size && spans[k].first < lineEnd) {
            val (a, e, s) = spans[k]
            val from = (a - lineStart).coerceAtLeast(0)
            val to = (e - lineStart).coerceAtMost(line.length)
            if (to > from) b.addStyle(s, from, to)
            k++
        }
        lines.add(b.toAnnotatedString())
        if (lineEnd >= n) break
        lineStart = lineEnd + 1
    }
    return lines
}

private fun String.indexOfFirstNonSpace(from: Int): Char? {
    var j = from
    while (j < length && (this[j] == ' ' || this[j] == '\t')) j++
    return getOrNull(j)
}

private fun lineStartSpaces(s: String, i: Int): Boolean {
    var j = i - 1
    while (j >= 0 && s[j] != '\n') {
        if (s[j] != ' ' && s[j] != '-' && s[j] != '\t') return false
        j--
    }
    return true
}

/** 代码查看：行号 + 高亮 + 可选自动换行 */
@Composable
fun CodeView(lines: List<AnnotatedString>, wrap: Boolean, modifier: Modifier = Modifier) {
    val g = Gh.c
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charW = remember(measurer) { measurer.measure("0", CodeStyle).size.width }
    val gutterChars = lines.size.toString().length
    val gutter = with(density) { (charW * gutterChars).toDp() } + 20.dp
    val maxChars = remember(lines) { lines.maxOfOrNull { it.length } ?: 0 }
    val contentW = with(density) { (charW * (maxChars + 2)).toDp() } + gutter + 16.dp
    val hs = rememberScrollState()
    Box(modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hs))) {
        SelectionContainer {
            LazyColumn(if (wrap) Modifier.fillMaxSize() else Modifier.width(contentW).fillMaxSize()) {
                itemsIndexed(lines) { idx, line ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            (idx + 1).toString(), Modifier.width(gutter).padding(end = 12.dp),
                            style = CodeStyle, color = g.fgMuted.copy(alpha = 0.7f), textAlign = TextAlign.End,
                        )
                        Text(line, style = CodeStyle, color = g.fg, softWrap = wrap, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

// ============ Diff ============

@Immutable
data class DiffLine(val text: String, val kind: Char, val oldNo: Int?, val newNo: Int?)

fun parsePatch(patch: String): List<DiffLine> {
    var o = 0
    var nn = 0
    val re = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    return patch.split('\n').map { l ->
        when {
            l.startsWith("@@") -> {
                re.find(l)?.let { o = it.groupValues[1].toInt(); nn = it.groupValues[2].toInt() }
                DiffLine(l, '@', null, null)
            }
            l.startsWith("+") -> DiffLine(l.substring(1), '+', null, nn++)
            l.startsWith("-") -> DiffLine(l.substring(1), '-', o++, null)
            l.startsWith("\\") -> DiffLine(l, '\\', null, null)
            else -> DiffLine(l.removePrefix(" "), ' ', o++, nn++)
        }
    }
}

/** 在 LazyColumn 中逐行渲染 diff：分栏模式左旧右新，否则单栏统一视图 */
fun LazyListScope.diffLines(key: String, lines: List<DiffLine>, g: GhColors) {
    if (Session.diffSplit) {
        val rows = splitRows(lines)
        val scroll = SplitScroll.of(key)
        itemsIndexed(rows, key = { i, _ -> "$key:$i" }) { _, r -> SplitDiffRow(r, g, key, scroll) }
    } else {
        itemsIndexed(lines, key = { i, _ -> "$key:$i" }) { _, d -> DiffRow(d, g, key) }
    }
}

/** 分栏的一行：左边是旧文件的行，右边是新文件的行；[note] 非空时是横跨两栏的 hunk 头或说明行 */
@Immutable
class SplitRow(val left: DiffLine?, val right: DiffLine?, val note: DiffLine? = null)

/**
 * 把统一 diff 配成左右对照：上下文行两边相同；连续的删除与新增按顺序一一配对（改动的行并排显示），
 * 多出来的一侧留空。
 */
fun splitRows(lines: List<DiffLine>): List<SplitRow> {
    val out = ArrayList<SplitRow>(lines.size)
    var i = 0
    while (i < lines.size) {
        val d = lines[i]
        when (d.kind) {
            '@', '\\' -> { out.add(SplitRow(null, null, d)); i++ }
            '-', '+' -> {
                val dels = ArrayList<DiffLine>()
                val adds = ArrayList<DiffLine>()
                while (i < lines.size && lines[i].kind == '-') dels.add(lines[i++])
                while (i < lines.size && lines[i].kind == '+') adds.add(lines[i++])
                for (k in 0 until maxOf(dels.size, adds.size)) out.add(SplitRow(dels.getOrNull(k), adds.getOrNull(k)))
            }
            else -> { out.add(SplitRow(d, d)); i++ }
        }
    }
    return out
}

/**
 * 同一文件的所有分栏行共用一个横向滚动状态：栏宽恰为半屏时没有滚动范围，
 * 在窗口比两栏更窄（分屏、大字体）时才会出现，此时整个文件各行一起滑动、两栏始终对齐。
 * 按文件键缓存，最多保留 64 个。
 */
object SplitScroll {
    private val cache = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>?) = size > 64
    }

    @Synchronized
    fun of(key: String): ScrollState = cache.getOrPut(key) { ScrollState(0) }
}

@Composable
fun SplitDiffRow(r: SplitRow, g: GhColors, file: String, scroll: ScrollState, onLineClick: ((DiffLine) -> Unit)? = null) {
    // 左右两栏各占一半屏宽，一眼同时看到旧、新两侧；长行在栏内换行，不需要左右滑动
    val side = LocalConfiguration.current.screenWidthDp.dp / 2
    val note = r.note
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        if (note != null) {
            Text(
                note.text,
                Modifier.width(side * 2).background(if (note.kind == '@') g.diffHunk else g.canvasSubtle).padding(horizontal = 8.dp, vertical = 2.dp),
                style = CodeStyle, color = g.fgMuted,
            )
        } else {
            Row(Modifier.height(IntrinsicSize.Min)) {
                SplitCell(r.left, old = true, g, file, side, onLineClick)
                SplitCell(r.right, old = false, g, file, side, onLineClick)
            }
        }
    }
}

@Composable
private fun SplitCell(d: DiffLine?, old: Boolean, g: GhColors, file: String, width: androidx.compose.ui.unit.Dp, onLineClick: ((DiffLine) -> Unit)?) {
    val bg = when {
        d == null -> g.border.copy(alpha = 0.12f)
        d.kind == '-' -> g.diffDel
        d.kind == '+' -> g.diffAdd
        else -> g.canvasSubtle
    }
    Row(
        Modifier.width(width).fillMaxHeight().background(bg)
            .then(if (!old) Modifier.drawBehind { drawLine(g.border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) } else Modifier)
            .then(if (d != null && onLineClick != null) Modifier.clickable { onLineClick(d) } else Modifier)
            .padding(vertical = 2.dp),
    ) {
        if (d == null) return@Row
        val no = if (old) d.oldNo else d.newNo
        Text(no?.toString() ?: "", Modifier.width(30.dp).padding(end = 4.dp), style = SplitStyle, color = g.fgMuted.copy(alpha = 0.7f), textAlign = TextAlign.End)
        val sc = syntaxColors(g)
        val code = remember(d, file, g.dark) { highlightLines(d.text, file, sc).firstOrNull() ?: AnnotatedString(d.text) }
        Text(code, Modifier.weight(1f).padding(end = 4.dp), style = SplitStyle, color = g.fg)
    }
}

/** 分栏里每栏只有半屏宽，字号比整行代码小一档 */
private val SplitStyle = CodeStyle.copy(fontSize = 11.sp, lineHeight = 16.sp)

/** 统一 / 分栏 切换，选择会保存下来；各 diff 页面放在文件列表上方 */
@Composable
fun DiffModeToggle(modifier: Modifier = Modifier) {
    Segmented(listOf(tr("统一"), tr("分栏")), if (Session.diffSplit) 1 else 0, modifier.width(132.dp)) { Session.chooseDiffSplit(it == 1) }
}

@Composable
fun DiffRow(d: DiffLine, g: GhColors, file: String = "", onLineClick: (() -> Unit)? = null) {
    val bg = when (d.kind) {
        '+' -> g.diffAdd
        '-' -> g.diffDel
        '@' -> g.diffHunk
        else -> g.canvasSubtle
    }
    Row(
        Modifier.fillMaxWidth().background(bg).then(
            // 代码行（含上下文行）可点按发表行内评论；hunk 头与「\ 无换行」标记不可
            if (onLineClick != null && d.kind != '@' && d.kind != '\\') Modifier.clickable(onClick = onLineClick) else Modifier,
        ).padding(vertical = 2.dp),
    ) {
        val no = when (d.kind) {
            '+' -> d.newNo?.toString() ?: ""
            '-' -> d.oldNo?.toString() ?: ""
            '@', '\\' -> ""
            else -> d.newNo?.toString() ?: ""
        }
        Text(no, Modifier.width(40.dp).padding(end = 6.dp), style = CodeStyle, color = g.fgMuted.copy(alpha = 0.7f), textAlign = TextAlign.End)
        Text(
            if (d.kind == '@' || d.kind == '\\') "" else d.kind.toString(),
            Modifier.width(14.dp), style = CodeStyle,
            color = when (d.kind) { '+' -> g.success; '-' -> g.danger; else -> g.fgMuted },
        )
        val code = if (d.kind == '@' || d.kind == '\\' || file.isBlank()) {
            AnnotatedString(d.text)
        } else {
            highlightLines(d.text, file, syntaxColors(g)).firstOrNull() ?: AnnotatedString(d.text)
        }
        Text(
            code,
            Modifier.weight(1f).padding(end = 8.dp), style = CodeStyle,
            color = if (d.kind == '@') g.fgMuted else g.fg,
        )
    }
}

fun annotated(s: String, style: SpanStyle) = buildAnnotatedString { pushStyle(style); append(s); pop() }
