package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.DraftEntry
import com.mobilegh.data.Drafts
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.ScreenCodec
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh

/**
 * 草稿上下文键 → 列表里显示的位置说明。键的格式见各页面的 rememberDraft 调用，
 * 例如 new-issue:owner/repo、comment:owner/repo:12。不认识的键原样显示。
 */
internal fun draftLabel(context: String): String {
    val kind = context.substringBefore(':')
    val rest = context.substringAfter(':', "")
    val repo = rest.substringBefore(':')
    val num = rest.substringAfter(':', "").substringBefore(':')
    return when (kind) {
        "new-issue" -> trf("新建 Issue · {0}", repo)
        "comment" -> trf("评论 · {0}#{1}", repo, num)
        "review" -> trf("审查意见 · {0}#{1}", repo, num)
        "review-new", "review-edit", "review-reply" -> trf("行内评论 · {0}#{1}", repo, num)
        "new-pull" -> trf("新建 Pull Request · {0}", repo)
        "release" -> trf("Release · {0}", repo)
        "new-discussion" -> trf("新建讨论 · {0}", repo)
        "discussion" -> trf("讨论回复 · {0}#{1}", repo, num)
        "gist-comment" -> trf("Gist 评论 · {0}", rest)
        "new-file" -> trf("新建文件 · {0}", repo)
        "edit-file" -> trf("编辑文件 · {0}/{1}", repo, num)
        else -> context
    }
}

/** 草稿内容的预览：标题优先，其次正文第一行 */
internal fun draftPreview(entry: DraftEntry): String {
    val v = entry.value
    return v.title.ifBlank { v.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.trim().take(200)
        .ifBlank { if (v.answers.isNotEmpty()) tr("表单已填写部分内容") else tr("（只有标签、指派人等设置）") }
}

@Composable
fun DraftsScreen() {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val g = Gh.c
    val store = remember { Drafts.store(ctx) }
    val login = Session.login
    var version by remember { mutableStateOf(0) }
    val list = remember(login, version) { login?.let { runCatching { store.list(it) }.getOrDefault(emptyList()) }.orEmpty() }
    var clearAll by remember { mutableStateOf(false) }
    Page(
        tr("草稿"), subtitle = trf("{0} 条未发送的内容", list.size),
        actions = {
            if (list.isNotEmpty()) MoreMenu(listOf(MenuAction(tr("清空全部草稿"), danger = true) { clearAll = true }))
        },
    ) { pad ->
        if (list.isEmpty()) {
            EmptyState(tr("没有草稿。写到一半的 Issue、评论、PR 等会自动保存在这里"), R.drawable.oc_pencil, Modifier.padding(pad).fillMaxSize())
        } else {
            ScrollbarLazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(list, key = { it.key }) { entry ->
                    val meta = entry.meta
                    val target = meta?.origin?.let { runCatching { ScreenCodec.draftJson.decodeFromString(ScreenCodec.screen, it) }.getOrNull() }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (target != null) nav.push(target) else ctx.toast(tr("这条草稿来自旧版本，无法定位原页面"))
                            }
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Oc(R.drawable.oc_pencil, g.fgMuted)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                meta?.context?.let(::draftLabel) ?: tr("未知位置（旧版本草稿）"),
                                color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(draftPreview(entry), color = g.fg, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if ((meta?.savedAt ?: 0L) > 0L) {
                                Text(trf("保存于 {0}", relTime(java.time.Instant.ofEpochMilli(meta!!.savedAt).toString())), color = g.fgMuted, fontSize = 12.sp)
                            }
                        }
                        OcButton(R.drawable.oc_trash, {
                            store.remove(entry.key)
                            version++
                            ctx.toast(tr("已删除草稿"))
                        }, g.fgMuted, description = tr("删除草稿"))
                    }
                    HDivider()
                }
            }
        }
    }
    if (clearAll) GhDialog(tr("清空全部草稿？"), { clearAll = false }, confirm = tr("清空"), danger = true, onConfirm = {
        clearAll = false
        list.forEach { store.remove(it.key) }
        version++
    }) { Text(tr("所有未发送的 Issue、评论、PR 等草稿都会被删除，无法恢复。"), color = g.fg) }
}
