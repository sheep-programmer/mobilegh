package com.mobilegh.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** 模式栏：编辑 / 预览切换与全屏入口。放在输入框上方，与输入框下方的格式工具栏分开。 */
@Composable
fun MarkdownModeBar(
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    val g = Gh.c
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.background(g.canvasSubtle, RoundedCornerShape(7.dp)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegBtn(R.drawable.oc_pencil, tr("编辑"), !preview) { onPreviewChange(false) }
            SegBtn(R.drawable.oc_eye, tr("预览"), preview) { onPreviewChange(true) }
        }
        Spacer(Modifier.weight(1f))
        if (onExpand != null) FmtBtn(tr("全屏编辑"), onExpand) { Oc(R.drawable.oc_screen_full, g.fgMuted, 16.dp) }
    }
}

/** 格式工具栏：标题、加粗等插入按钮。按钮平分整行宽度（不横向滚动，小屏上后面的按钮不会看不见）；放在输入框下方，只在编辑时出现。 */
@Composable
fun MarkdownToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = Gh.c
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolBtn(tr("标题"), { onValueChange(linePrefix(value, "### ")) }) { Oc(R.drawable.oc_heading, g.fgMuted, 18.dp) }
        ToolBtn(tr("加粗"), { onValueChange(wrap(value, "**")) }) { Oc(R.drawable.oc_bold, g.fgMuted, 18.dp) }
        ToolBtn(tr("斜体"), { onValueChange(wrap(value, "_")) }) { Oc(R.drawable.oc_italic, g.fgMuted, 18.dp) }
        ToolBtn(tr("删除线"), { onValueChange(wrap(value, "~~")) }) { Oc(R.drawable.oc_strikethrough, g.fgMuted, 18.dp) }
        ToolBtn(tr("行内代码"), { onValueChange(wrap(value, "`")) }) { Oc(R.drawable.oc_code, g.fgMuted, 18.dp) }
        ToolBtn(tr("链接"), { onValueChange(wrap(value, "[", "](url)")) }) { Oc(R.drawable.oc_link, g.fgMuted, 18.dp) }
        ToolBtn(tr("引用"), { onValueChange(linePrefix(value, "> ")) }) { Oc(R.drawable.oc_quote, g.fgMuted, 18.dp) }
        ToolBtn(tr("无序列表"), { onValueChange(linePrefix(value, "- ")) }) { Oc(R.drawable.oc_list_unordered, g.fgMuted, 18.dp) }
        ToolBtn(tr("有序列表"), { onValueChange(linePrefix(value, "1. ")) }) { Oc(R.drawable.oc_list_ordered, g.fgMuted, 18.dp) }
        ToolBtn(tr("任务列表"), { onValueChange(linePrefix(value, "- [ ] ")) }) { Oc(R.drawable.oc_checklist, g.fgMuted, 18.dp) }
        ToolBtn(tr("提及用户"), { onValueChange(insert(value, "@")) }) { Oc(R.drawable.oc_mention, g.fgMuted, 18.dp) }
    }
}

@Composable
private fun RowScope.ToolBtn(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
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

/** 底部的「添加文件」条：点击选择图片或视频，上传中显示文件名 */
@Composable
private fun AttachStrip(uploading: String?, onAttach: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = uploading == null, role = Role.Button, onClick = onAttach)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_paperclip, g.fgMuted, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (uploading != null) trf("正在上传 {0}…", uploading) else tr("点击添加图片或视频"),
            fontSize = 13.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
 * 预览和右上角的全屏按钮都在整屏对话框里呈现，不再挤在输入框旁边。
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = tr("支持 Markdown"),
    contextRepo: String? = null,
    minLines: Int = 6,
    /** 网页版 Issue 的外框样式：编辑 / 预览切换与格式按钮都在框内，底部是「添加文件」条 */
    framed: Boolean = false,
    /** 非空时显示「添加文件」条；选择的文件上传后由调用方把地址插入正文 */
    onAttach: (() -> Unit)? = null,
    /** 正在上传的文件名；非空时添加文件条显示上传状态 */
    uploading: String? = null,
) {
    val g = Gh.c
    // null = 关闭；false = 全屏编辑；true = 全屏预览
    var sheet by remember { mutableStateOf<Boolean?>(null) }
    val preview = sheet == true
    // 外框样式里的编辑 / 预览在框内切换（与网页版一致），全屏按钮仍打开整屏工作区
    var inlinePreview by remember { mutableStateOf(false) }
    if (framed) {
        Column(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, g.border, RoundedCornerShape(6.dp)),
        ) {
            Column(Modifier.fillMaxWidth().background(g.canvasSubtle).padding(horizontal = 6.dp, vertical = 2.dp)) {
                MarkdownModeBar(inlinePreview, { inlinePreview = it }, onExpand = { sheet = false })
            }
            HDivider()
            if (inlinePreview) {
                MarkdownPreview(value.text, contextRepo, Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(12.dp))
            } else {
                TextField(
                    value, onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder, color = g.fgMuted) },
                    minLines = minLines,
                    shape = RectangleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                MentionSuggestions(value, onValueChange, contextRepo, Modifier.padding(horizontal = 6.dp))
                MarkdownToolbar(value, onValueChange, Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            }
            if (onAttach != null) {
                HDivider()
                AttachStrip(uploading, onAttach)
            }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            MarkdownModeBar(preview, { sheet = it }, onExpand = { sheet = false })
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value, onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(placeholder, color = g.fgMuted) },
                minLines = minLines,
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            if (!preview) MarkdownToolbar(value, onValueChange, Modifier.padding(top = 4.dp))
            MentionSuggestions(value, onValueChange, contextRepo, Modifier.padding(top = 4.dp))
            if (onAttach != null) AttachStrip(uploading, onAttach)
        }
    }
    sheet?.let { mode ->
        MarkdownFullscreenSheet(
            value = value,
            onValueChange = onValueChange,
            preview = mode,
            onPreviewChange = { sheet = it },
            contextRepo = contextRepo,
            placeholder = placeholder,
            onDismiss = { sheet = null },
        )
    }
}

/**
 * 整屏 Markdown 工作区：顶栏 + 工具栏 + 编辑框或渲染后的预览。
 * 预览用整页宽度渲染，长文档可滚动；编辑时占满剩余高度，适合在手机上写长内容。
 */
@Composable
fun MarkdownFullscreenSheet(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
    contextRepo: String?,
    onDismiss: () -> Unit,
    placeholder: String = tr("支持 Markdown"),
    trailing: (@Composable () -> Unit)? = null,
) {
    val g = Gh.c
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(g.canvas)
                .statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FmtBtn(tr("关闭"), onDismiss) { Oc(R.drawable.oc_x, g.fgMuted, 18.dp) }
                Text(
                    if (preview) tr("预览") else tr("编辑"),
                    Modifier.weight(1f).padding(start = 2.dp),
                    color = g.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                trailing?.invoke()
            }
            HDivider()
            // 内容占满整屏：工具栏移到底部（键盘上方），不再从上方挤压输入区
            if (preview) {
                Column(
                    Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { MarkdownPreview(value.text, contextRepo) }
            } else {
                OutlinedTextField(
                    value, onValueChange,
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp, vertical = 4.dp),
                    placeholder = { Text(placeholder, color = g.fgMuted) },
                    shape = RoundedCornerShape(6.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                MentionSuggestions(value, onValueChange, contextRepo, Modifier.padding(horizontal = 8.dp))
            }
            if (!preview) {
                HDivider()
                MarkdownToolbar(value, onValueChange, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
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

