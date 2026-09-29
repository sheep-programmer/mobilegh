package com.mobilegh.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.ui.theme.Gh

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
private fun FmtBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(34.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Glyph(s: String, weight: FontWeight = FontWeight.Normal, italic: Boolean = false, strike: Boolean = false) {
    Text(
        s, fontSize = 15.sp, color = Gh.c.fgMuted, fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
    )
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
                FmtBtn({ onValueChange(linePrefix(value, "### ")) }) { Glyph("H", FontWeight.Bold) }
                FmtBtn({ onValueChange(wrap(value, "**")) }) { Glyph("B", FontWeight.Bold) }
                FmtBtn({ onValueChange(wrap(value, "_")) }) { Glyph("I", italic = true) }
                FmtBtn({ onValueChange(wrap(value, "~~")) }) { Glyph("S", strike = true) }
                FmtBtn({ onValueChange(wrap(value, "`")) }) { Oc(R.drawable.oc_code, g.fgMuted, 18.dp) }
                FmtBtn({ onValueChange(wrap(value, "[", "](url)")) }) { Oc(R.drawable.oc_link, g.fgMuted, 18.dp) }
                FmtBtn({ onValueChange(linePrefix(value, "> ")) }) { Glyph("“", FontWeight.Bold) }
                FmtBtn({ onValueChange(linePrefix(value, "- ")) }) { Glyph("•", FontWeight.Bold) }
                FmtBtn({ onValueChange(linePrefix(value, "1. ")) }) { Glyph("1.", FontWeight.Bold) }
                FmtBtn({ onValueChange(linePrefix(value, "- [ ] ")) }) { Oc(R.drawable.oc_checklist, g.fgMuted, 18.dp) }
                FmtBtn({ onValueChange(insert(value, "@")) }) { Oc(R.drawable.oc_mention, g.fgMuted, 18.dp) }
            }
        }
        // 编辑 / 预览 分段切换
        Row(
            Modifier.padding(start = 6.dp).background(g.canvasSubtle, RoundedCornerShape(7.dp)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegBtn(R.drawable.oc_pencil, "编辑", !preview) { onPreviewChange(false) }
            SegBtn(R.drawable.oc_eye, "预览", preview) { onPreviewChange(true) }
        }
    }
}

@Composable
private fun SegBtn(icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    Row(
        Modifier.clickable(onClick = onClick)
            .background(if (active) g.canvas else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
        Text("没有可预览的内容", color = g.fgMuted, fontSize = 13.sp, modifier = modifier.padding(12.dp))
        return
    }
    val html by produceState<String?>(null, text, contextRepo) {
        value = null
        value = runCatching { GitHub.renderMarkdown(text, contextRepo) }
            .getOrElse { "<p>预览失败：${it.message}</p>" }
    }
    val h = html
    if (h == null) {
        Row(modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = g.fgMuted, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("渲染中…", color = g.fgMuted, fontSize = 13.sp)
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
    placeholder: String = "支持 Markdown",
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
        }
    }
}

