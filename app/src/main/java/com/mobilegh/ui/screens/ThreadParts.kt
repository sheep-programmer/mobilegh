package com.mobilegh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Issue
import com.mobilegh.data.Reactions
import com.mobilegh.data.User
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LabelChip
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Pill
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.launch

val REACTIONS = listOf(
    "+1" to "👍", "-1" to "👎", "laugh" to "😄", "hooray" to "🎉",
    "confused" to "😕", "heart" to "❤️", "rocket" to "🚀", "eyes" to "👀",
)

/**
 * 表情栏：已有的表情显示计数（点按切换自己的表态），「+」展开全部 8 种表情。
 * 先乐观更新，再以服务端结果为准（POST 返回 200 表示原本就有，等价于取消）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionBar(initial: Reactions?, modifier: Modifier = Modifier, onToggle: suspend (String) -> Boolean) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val counts = remember(initial) { mutableStateMapOf<String, Int>().also { m -> REACTIONS.forEach { (k, _) -> m[k] = initial?.count(k) ?: 0 } } }
    val mine = remember { mutableStateMapOf<String, Boolean>() }
    var picking by remember { mutableStateOf(false) }

    fun toggle(key: String) {
        val before = counts[key] ?: 0
        val was = mine[key] == true
        counts[key] = if (was) (before - 1).coerceAtLeast(0) else before + 1
        mine[key] = !was
        picking = false
        scope.launch {
            try {
                val on = onToggle(key)
                counts[key] = if (on) (if (was) before else before + 1) else (before - 1).coerceAtLeast(0)
                mine[key] = on
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                counts[key] = before
                mine[key] = was
                ctx.toast(e.message ?: "操作失败")
            }
        }
    }

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            REACTIONS.filter { (k, _) -> (counts[k] ?: 0) > 0 }.forEach { (k, e) ->
                val on = mine[k] == true
                Row(
                    Modifier.clickable { toggle(k) }
                        .background(if (on) g.accentSubtle else g.canvas, RoundedCornerShape(14.dp))
                        .border(1.dp, if (on) g.accent else g.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e, fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("${counts[k]}", fontSize = 12.sp, color = if (on) g.accent else g.fgMuted, fontWeight = FontWeight.Medium)
                }
            }
            Box(
                Modifier.clickable { picking = !picking }
                    .background(if (picking) g.canvasSubtle else g.canvas, RoundedCornerShape(14.dp))
                    .border(1.dp, g.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("☺ +", fontSize = 13.sp, color = g.fgMuted) }
        }
        if (picking) {
            Row(
                Modifier.padding(top = 8.dp).background(g.canvasSubtle, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                REACTIONS.forEach { (k, e) ->
                    Box(
                        Modifier.size(36.dp).clickable { toggle(k) }
                            .background(if (mine[k] == true) g.accentSubtle else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 20.sp) }
                }
            }
        }
    }
}

/** 时间线上的一条评论（或 Issue 正文）：头部 + 正文 + 表情栏，右上角 ⋯ 菜单 */
@Composable
fun TimelineCard(
    user: User?,
    time: String?,
    badge: String?,
    html: String?,
    baseUrl: String,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val g = Gh.c
    val nav = LocalNav.current
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().border(1.dp, g.border, shape)) {
        Row(
            Modifier.fillMaxWidth().background(g.canvasSubtle, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(user?.avatarUrl, 24.dp, Modifier.clickable { user?.let { nav.push(Screen.Profile(it.login)) } })
            Spacer(Modifier.width(8.dp))
            Text(user?.login ?: "ghost", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.fg, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(relTime(time), fontSize = 12.sp, color = g.fgMuted, maxLines = 1)
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Pill(badge)
            }
            Spacer(Modifier.weight(1f))
            if (menu.isNotEmpty()) MoreMenu(menu)
        }
        if (html.isNullOrBlank()) {
            Text("没有提供描述。", Modifier.padding(12.dp), fontSize = 13.sp, color = g.fgMuted)
        } else {
            HtmlView(html, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), baseUrl = baseUrl)
        }
        if (footer != null) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { footer() }
    }
}

/** 标签 / 指派人 / 里程碑 三行元数据，点按进入编辑 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaBlock(issue: Issue, onEdit: (String) -> Unit) {
    val g = Gh.c
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().border(1.dp, g.border, RoundedCornerShape(8.dp)),
    ) {
        @Composable
        fun row(icon: Int, title: String, kind: String, content: @Composable () -> Unit) {
            Row(Modifier.fillMaxWidth().clickable { onEdit(kind) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Oc(icon, g.fgMuted, 16.dp, Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, Modifier.width(56.dp), fontSize = 13.sp, color = g.fgMuted)
                Box(Modifier.weight(1f)) { content() }
                Oc(R.drawable.oc_pencil, g.fgMuted, 14.dp, Modifier.padding(top = 2.dp))
            }
        }
        row(R.drawable.oc_tag, "标签", "labels") {
            if (issue.labels.isEmpty()) Text("无", fontSize = 13.sp, color = g.fgMuted)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                issue.labels.forEach { LabelChip(it.name, it.color) }
            }
        }
        HDivider()
        row(R.drawable.oc_person, "指派人", "assignees") {
            if (issue.assignees.isEmpty()) Text("无", fontSize = 13.sp, color = g.fgMuted)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                issue.assignees.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a.avatarUrl, 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(a.login, fontSize = 13.sp, color = g.fg)
                    }
                }
            }
        }
        row(R.drawable.oc_milestone, "里程碑", "milestone") {
            Text(issue.milestone?.title ?: "无", fontSize = 13.sp, color = if (issue.milestone == null) g.fgMuted else g.fg)
        }
    }
}

private class Opt(val key: String, val label: String, val avatar: String? = null, val color: String? = null)

/** 标签（多选）/ 指派人（多选）/ 里程碑（单选，可清空）选择框，保存后调用 onSaved 刷新 */
@Composable
fun MetaDialog(kind: String, owner: String, name: String, number: Int, issue: Issue, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    val single = kind == "milestone"
    val title = when (kind) { "labels" -> "编辑标签"; "assignees" -> "编辑指派人"; else -> "选择里程碑" }
    val options by produceState<List<Opt>?>(null, kind) {
        value = try {
            when (kind) {
                "labels" -> GitHub.repoLabels(owner, name).map { Opt(it.name, it.name, color = it.color) }
                "assignees" -> GitHub.repoAssignees(owner, name).map { Opt(it.login, it.login, avatar = it.avatarUrl) }
                else -> GitHub.repoMilestones(owner, name).map { Opt(it.number.toString(), it.title) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ctx.toast(e.message ?: "加载失败")
            emptyList()
        }
    }
    val selected = remember {
        mutableStateListOf<String>().also {
            when (kind) {
                "labels" -> it.addAll(issue.labels.map { l -> l.name })
                "assignees" -> it.addAll(issue.assignees.map { a -> a.login })
                else -> issue.milestone?.let { m -> it.add(m.number.toString()) }
            }
        }
    }
    var saving by remember { mutableStateOf(false) }

    GhDialog(title, onDismiss, confirm = if (saving) "保存中…" else "保存", confirmEnabled = !saving && options != null, onConfirm = {
        saving = true
        scope.launch {
            try {
                when (kind) {
                    "labels" -> GitHub.setLabels(owner, name, number, selected.toList())
                    "assignees" -> GitHub.setAssignees(owner, name, number, selected.toList())
                    else -> GitHub.setMilestone(owner, name, number, selected.firstOrNull()?.toIntOrNull())
                }
                ctx.toast("已保存")
                onSaved()
                onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                saving = false
                ctx.toast(e.message ?: "保存失败（可能没有权限）")
            }
        }
    }) {
        val opts = options
        when {
            opts == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = g.fgMuted, strokeWidth = 2.dp)
            }
            opts.isEmpty() -> Text(
                when (kind) { "labels" -> "这个仓库还没有标签"; "assignees" -> "没有可指派的人"; else -> "没有进行中的里程碑" },
                color = g.fgMuted, fontSize = 14.sp,
            )
            else -> Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (single) Row(
                    Modifier.fillMaxWidth().clickable { selected.clear() }, verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected.isEmpty(), { selected.clear() }, colors = RadioButtonDefaults.colors(selectedColor = g.btnPrimary))
                    Text("无里程碑", color = g.fgMuted, fontSize = 14.sp)
                }
                opts.forEach { o ->
                    val on = o.key in selected
                    fun flip() {
                        if (single) { selected.clear(); selected.add(o.key) }
                        else if (on) selected.remove(o.key) else selected.add(o.key)
                    }
                    Row(Modifier.fillMaxWidth().clickable { flip() }, verticalAlignment = Alignment.CenterVertically) {
                        if (single) RadioButton(on, { flip() }, colors = RadioButtonDefaults.colors(selectedColor = g.btnPrimary))
                        else Checkbox(on, { flip() }, colors = CheckboxDefaults.colors(checkedColor = g.btnPrimary))
                        if (o.avatar != null) { Avatar(o.avatar, 22.dp); Spacer(Modifier.width(8.dp)) }
                        if (o.color != null) LabelChip(o.label, o.color) else Text(o.label, color = g.fg, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** 编辑评论 / Issue 正文（带 Markdown 工具栏与预览）；title 非空时同时可改标题 */
@Composable
fun EditDialog(
    heading: String,
    initialTitle: String?,
    initialBody: String,
    contextRepo: String,
    onDismiss: () -> Unit,
    onSave: (title: String, body: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle.orEmpty()) }
    var body by remember { mutableStateOf(TextFieldValue(initialBody)) }
    GhDialog(
        heading, onDismiss, confirm = "保存",
        confirmEnabled = body.text.isNotBlank() && (initialTitle == null || title.isNotBlank()),
        onConfirm = { onSave(title.trim(), body.text) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (initialTitle != null) {
                GhField(title, { title = it }, "标题")
            }
            MarkdownEditor(body, { body = it }, contextRepo = contextRepo, minLines = 6, modifier = Modifier.padding(top = if (initialTitle != null) 8.dp else 0.dp))
        }
    }
}
