package com.mobilegh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 在选区两侧包裹标记（**、_、` 等）；无选区时把光标放到标记中间 */
private fun wrap(v: TextFieldValue, pre: String, post: String = pre): TextFieldValue {
    val t = v.text
    val a = v.selection.min
    val b = v.selection.max
    val out = t.substring(0, a) + pre + t.substring(a, b) + post + t.substring(b)
    val cursor = if (a == b) a + pre.length else b + pre.length + post.length
    return v.copy(text = out, selection = TextRange(cursor))
}

/** 在当前行行首插入前缀（> 、- 、### 、- [ ] 等） */
private fun linePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val t = v.text
    val at = v.selection.min
    val ls = if (at == 0) 0 else t.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
    val out = t.substring(0, ls) + prefix + t.substring(ls)
    return v.copy(text = out, selection = TextRange(v.selection.min + prefix.length))
}

/** 插入一段文本，光标落在末尾 */
private fun insert(v: TextFieldValue, s: String): TextFieldValue {
    val t = v.text
    val a = v.selection.min
    val b = v.selection.max
    val out = t.substring(0, a) + s + t.substring(b)
    return v.copy(text = out, selection = TextRange(a + s.length))
}

@Composable
private fun FmtBtn(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------- @ 提及 ----------

/** 光标前正在输入的 @ 提及：start 为 @ 的下标，query 为 @ 之后已输入的登录名片段 */
internal data class MentionFragment(val start: Int, val query: String)

private val MENTION_QUERY = Regex("[A-Za-z0-9-]{0,39}")
private const val MENTION_LIMIT = 8

/**
 * 光标前是否为 @ 提及的片段：@ 必须位于行首或空白 / 括号之后，之后只能是登录名字符。
 * 例如 "a@b" 不是提及（邮箱），"see @ali" 是提及。
 */
internal fun mentionFragment(text: String, cursor: Int): MentionFragment? {
    if (cursor < 0 || cursor > text.length) return null
    val before = text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    val query = before.substring(at + 1)
    if (!MENTION_QUERY.matches(query)) return null
    if (at > 0 && !(before[at - 1].isWhitespace() || before[at - 1] in "([{")) return null
    return MentionFragment(at, query)
}

/** 按输入的片段过滤候选人：前缀匹配优先，其次包含匹配，最多 [MENTION_LIMIT] 个 */
internal fun mentionCandidates(all: List<String>, query: String): List<String> {
    val q = query.lowercase()
    return all.filter { it.lowercase().contains(q) }
        .sortedWith(compareBy<String>({ !it.lowercase().startsWith(q) }, { it.lowercase() }))
        .take(MENTION_LIMIT)
}

/** 用 @login 替换光标前的 @ 片段（后面补一个空格，便于继续输入）；返回新文本与新光标位置 */
internal fun replaceMentionText(text: String, cursor: Int, fragment: MentionFragment, login: String): Pair<String, Int> {
    val insertion = "@$login "
    val out = text.substring(0, fragment.start) + insertion + text.substring(cursor)
    return out to fragment.start + insertion.length
}

/** Markdown 格式工具栏 + 编辑/预览切换。preview 状态由外部持有，方便预览时替换输入框。 */
@Composable
fun MarkdownToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (!preview) {
                FmtBtn(tr("标题"), { onValueChange(linePrefix(value, "### ")) }) { Oc(R.drawable.oc_heading, g.fgMuted, 18.dp) }
                FmtBtn(tr("加粗"), { onValueChange(wrap(value, "**")) }) { Oc(R.drawable.oc_bold, g.fgMuted, 18.dp) }
                FmtBtn(tr("斜体"), { onValueChange(wrap(value, "_")) }) { Oc(R.drawable.oc_italic, g.fgMuted, 18.dp) }
                FmtBtn(tr("删除线"), { onValueChange(wrap(value, "~~")) }) { Oc(R.drawable.oc_strikethrough, g.fgMuted, 18.dp) }
                FmtBtn(tr("行内代码"), { onValueChange(wrap(value, "`")) }) { Oc(R.drawable.oc_code, g.fgMuted, 18.dp) }
                FmtBtn(tr("链接"), { onValueChange(wrap(value, "[", "](url)")) }) { Oc(R.drawable.oc_link, g.fgMuted, 18.dp) }
                FmtBtn(tr("引用"), { onValueChange(linePrefix(value, "> ")) }) { Oc(R.drawable.oc_quote, g.fgMuted, 18.dp) }
                FmtBtn(tr("无序列表"), { onValueChange(linePrefix(value, "- ")) }) { Oc(R.drawable.oc_list_unordered, g.fgMuted, 18.dp) }
                FmtBtn(tr("有序列表"), { onValueChange(linePrefix(value, "1. ")) }) { Oc(R.drawable.oc_list_ordered, g.fgMuted, 18.dp) }
                FmtBtn(tr("任务列表"), { onValueChange(linePrefix(value, "- [ ] ")) }) { Oc(R.drawable.oc_checklist, g.fgMuted, 18.dp) }
                FmtBtn(tr("提及用户"), { onValueChange(insert(value, "@")) }) { Oc(R.drawable.oc_mention, g.fgMuted, 18.dp) }
            }
        }
        // 编辑 / 预览 分段切换
        Row(
            Modifier.padding(start = 6.dp).background(g.canvasSubtle, RoundedCornerShape(7.dp)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegBtn(R.drawable.oc_pencil, tr("编辑"), !preview) { onPreviewChange(false) }
            SegBtn(R.drawable.oc_eye, tr("预览"), preview) { onPreviewChange(true) }
        }
    }
}

@Composable
private fun SegBtn(icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .background(if (active) g.canvas else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, if (active) g.fg else g.fgMuted, 14.dp)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 12.sp, color = if (active) g.fg else g.fgMuted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 调 GitHub /markdown 接口渲染 GFM 预览（能解析 #123、@user、任务列表等） */
@Composable
fun MarkdownPreview(text: String, contextRepo: String?, modifier: Modifier = Modifier) {
    val g = Gh.c
    if (text.isBlank()) {
        Text(tr("没有可预览的内容"), color = g.fgMuted, fontSize = 13.sp, modifier = modifier.padding(12.dp))
        return
    }
    val html by produceState<String?>(null, text, contextRepo) {
        value = null
        value = runCatching { GitHub.renderMarkdown(text, contextRepo) }
            .getOrElse { trf("<p>预览失败：{0}</p>", it.message) }
    }
    val h = html
    if (h == null) {
        Row(modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = g.fgMuted, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("渲染中…"), color = g.fgMuted, fontSize = 13.sp)
        }
    } else {
        HtmlView(h, modifier.padding(vertical = 4.dp))
    }
}

/**
 * 完整 Markdown 编辑器：格式工具栏 + 编辑/预览切换 + 多行输入。用于新建 Issue、PR 描述等。
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = tr("支持 Markdown"),
    contextRepo: String? = null,
    minLines: Int = 6,
) {
    val g = Gh.c
    var preview by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        MarkdownToolbar(value, onValueChange, preview, { preview = it })
        Spacer(Modifier.height(6.dp))
        if (preview) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = (minLines * 22).dp)
                    .background(g.canvas, RoundedCornerShape(6.dp)),
            ) { MarkdownPreview(value.text, contextRepo) }
        } else {
            OutlinedTextField(
                value, onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(placeholder, color = g.fgMuted) },
                minLines = minLines,
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            MentionSuggestions(value, onValueChange, contextRepo, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * 输入 @ 片段时在输入框下方列出仓库的可指派人（按片段过滤），点选后替换为 @登录名。
 * 候选人只在出现 @ 片段时才读取；没有仓库上下文（contextRepo 为空）时不显示。
 */
@Composable
fun MentionSuggestions(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, contextRepo: String?, modifier: Modifier = Modifier) {
    val g = Gh.c
    val repo = contextRepo?.takeIf { it.count { c -> c == '/' } == 1 }
    val fragment = if (repo != null && value.selection.collapsed) mentionFragment(value.text, value.selection.start) else null
    val logins by produceState<List<String>?>(null, repo, fragment != null) {
        if (repo != null && fragment != null && this.value == null) {
            this.value = runCatching {
                GitHub.repoAssignees(repo.substringBefore('/'), repo.substringAfter('/')).map { it.login }
            }.getOrDefault(emptyList())
        }
    }
    val shown = if (fragment == null || logins == null) emptyList() else mentionCandidates(logins.orEmpty(), fragment.query)
    if (fragment == null || shown.isEmpty()) return
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(g.canvasSubtle)
            .border(1.dp, g.border, RoundedCornerShape(6.dp)),
    ) {
        shown.forEach { login ->
            Text(
                "@$login", fontSize = 14.sp, color = g.fg,
                modifier = Modifier.fillMaxWidth().clickable {
                    val (text, cursor) = replaceMentionText(value.text, value.selection.start, fragment, login)
                    onValueChange(value.copy(text = text, selection = TextRange(cursor)))
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

