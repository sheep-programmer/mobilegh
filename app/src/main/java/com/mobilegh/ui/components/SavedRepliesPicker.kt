package com.mobilegh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.SavedReply
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 用回复正文替换当前选区，光标落在插入内容末尾；空正文也可用于删除选区。 */
fun insertSavedReply(value: TextFieldValue, body: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.substring(0, start) + body + value.text.substring(end)
    return value.copy(text = text, selection = TextRange(start + body.length))
}

internal fun filterSavedReplies(replies: List<SavedReply>, query: String): List<SavedReply> {
    val needle = query.trim()
    if (needle.isEmpty()) return replies
    return replies.filter { it.title.contains(needle, true) || it.body.contains(needle, true) }
}

internal fun savedRepliesSettingsUrl(webBase: String = Net.loginBase): String =
    Net.loginEndpoint("/settings/replies", webBase.trimEnd('/'))

@Composable
fun SavedRepliesPicker(onDismiss: () -> Unit, onPick: (SavedReply) -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var replies by remember { mutableStateOf(listOf<SavedReply>()) }
    var endCursor by remember { mutableStateOf<String?>(null) }
    var hasNextPage by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(after: String? = null) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val page = GitHub.savedReplies(after)
                replies = if (after == null) page.items else replies + page.items.filter { new -> replies.none { it.id == new.id } }
                endCursor = page.endCursor
                hasNextPage = page.hasNextPage
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: tr("读取失败")
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    val visible = filterSavedReplies(replies, query)

    GhDialog(tr("保存的回复"), onDismiss, onConfirm = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            GhField(query, { query = it }, tr("搜索保存的回复"), placeholder = tr("搜索标题或正文"))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScrollWithBar()) {
                visible.forEach { reply ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable { onPick(reply) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        Text(reply.title.ifBlank { tr("无标题回复") }, color = g.fg, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        if (reply.body.isNotBlank()) {
                            Text(reply.body, Modifier.padding(top = 3.dp), color = g.fgMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HDivider()
                }
                if (loading) Loading(Modifier.fillMaxWidth().height(64.dp))
                error?.let { InlineError(it, retry = { load(endCursor.takeIf { loaded }) }) }
                if (loaded && !loading && error == null && visible.isEmpty()) {
                    Text(
                        if (replies.isEmpty()) tr("还没有保存的回复，可在 GitHub 设置中创建") else tr("没有匹配的保存回复"),
                        Modifier.padding(12.dp), color = g.fgMuted, fontSize = 13.sp,
                    )
                }
                if (hasNextPage && error == null) {
                    GhButton(if (loading) tr("加载中…") else tr("加载更多"), enabled = !loading, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        load(endCursor)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                GhButton(tr("管理保存的回复"), icon = R.drawable.oc_link_external) {
                    ctx.openBrowser(savedRepliesSettingsUrl())
                }
            }
        }
    }
}
